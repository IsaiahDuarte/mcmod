package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.RestartCheckpoint.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.client.MachineSettingsScreen;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;

/** Real second-JVM reopen. Expected observations never feed a production load/restore API. */
final class WorldRestartVerification {
  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final Properties expected;
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean initial = new AtomicBoolean(), replenished = new AtomicBoolean();
  private int stage, ticks, entered, openAttempts, lastOpen = Integer.MIN_VALUE;
  private final AtomicBoolean reopenReady = new AtomicBoolean(), reopenProbe = new AtomicBoolean();
  private final AtomicReference<String> reopenDiagnostic = new AtomicReference<>("not probed");
  private final ChunkLifecycleVerification chunks = new ChunkLifecycleVerification();
  private final StagedRecoveryVerification staging = new StagedRecoveryVerification();
  private final AtomicBoolean stagingDone = new AtomicBoolean();
  private final AtomicBoolean chunksDone = new AtomicBoolean(), splitDone = new AtomicBoolean();
  private final SplitRootVerification split = new SplitRootVerification();

  WorldRestartVerification() {
    try {
      expected = RestartCheckpoint.read(Path.of(System.getProperty("factorycore.client.seed")));
      long seedPid = Long.parseLong(expected.getProperty("pid"));
      require(seedPid != ProcessHandle.current().pid(), "Restart uses a distinct JVM");
      require(ProcessHandle.of(seedPid).map(p -> !p.isAlive()).orElse(true), "Seed JVM has exited");
      Files.createDirectories(report.getParent());
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  void register() {
    chunks.register();
    split.register();
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns its server, world and render target.
  private void tick(ClientTickEvent.Post event) {
    var client = Minecraft.getInstance();
    ticks++;
    if (stage == 7) return;
    if (failure.get() != null)
      throw new IllegalStateException("World restart fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "World restart timeout stage="
              + stage
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName())
              + " attempts="
              + openAttempts
              + " server="
              + reopenDiagnostic.get()
              + (client.screen instanceof MachineSettingsScreen machine
                  ? " preset=" + machine.getMenu().presetView()
                  : ""));
    if (stage == 0 && client.screen instanceof TitleScreen) {
      client.options.pauseOnLostFocus = false;
      client
          .createWorldOpenFlows()
          .openWorld(
              "factorycore-terminal-verification",
              () -> {
                throw new IllegalStateException("Opening saved fixture world was cancelled");
              });
      next();
      return;
    }
    if (client.player == null || client.getSingleplayerServer() == null) return;
    var server = client.getSingleplayerServer();
    var principal = client.player.getUUID();
    if (stage == 1 && !initial.get()) {
      server.execute(
          () -> {
            if (initial.get()) return;
            try {
              var player = server.getPlayerList().getPlayer(principal);
              var machine = (MachineInterfaceEntity) player.serverLevel().getBlockEntity(MACHINE);
              if (machine == null || !machine.canUse(player)) return;
              verifyInitial(player, machine);
              var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(MACHINE.south());
              require(
                  player.getInventory().getItem(10).isEmpty(),
                  "Destination slot for foreign removal is empty");
              var removed = chest.removeItem(2, 3);
              require(removed.getCount() == 3, "Actual chest removal takes exactly3");
              chest.setChanged();
              player.getInventory().setItem(10, removed);
              player.inventoryMenu.broadcastChanges();
              initial.set(true);
            } catch (Exception error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 1 && initial.get()) {
      next();
      return;
    }
    if (stage == 2 && !replenished.get()) {
      server.execute(
          () -> {
            if (replenished.get()) return;
            try {
              var player = server.getPlayerList().getPlayer(principal);
              var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(MACHINE.south());
              if (chest.getItem(2).getCount() != 8) return;
              var drive = (StorageDriveEntity) player.serverLevel().getBlockEntity(DRIVE);
              var buffer = (BufferedInterfaceEntity) player.serverLevel().getBlockEntity(BUFFER);
              var codec = new PlatformResourceCodec(player.registryAccess());
              var key = codec.itemKey(player.getInventory().getItem(9));
              require(
                  DeviceSavedData.get(server)
                              .registry()
                              .stock(CellHandleData.read(drive.cell(0)), key)
                              .total()
                          == 3
                      && buffer.inventory().total(key) == 9
                      && player.getInventory().getItem(9).getCount() == 2
                      && player.getInventory().getItem(10).getCount() == 3
                      && codec.itemKey(chest.getItem(2)).equals(key),
                  "Resumed Stock conserves cell3 + machine8 + buffer9 + player5 =25");
              replenished.set(true);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 2 && replenished.get() && !stagingDone.get()) {
      server.execute(
          () -> {
            if (stagingDone.get()) return;
            try {
              if (staging.tick(server.getPlayerList().getPlayer(principal), expected))
                stagingDone.set(true);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 2 && stagingDone.get() && !chunksDone.get()) {
      server.execute(
          () -> {
            if (chunksDone.get()) return;
            try {
              if (chunks.tick(server.getPlayerList().getPlayer(principal))) chunksDone.set(true);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 2 && chunksDone.get() && !splitDone.get()) {
      server.execute(
          () -> {
            if (splitDone.get()) return;
            try {
              if (split.tick(server.getPlayerList().getPlayer(principal))) splitDone.set(true);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    // Split-root returns as soon as the home chunk exists. Its ordinary network may still be
    // rebuilding; a client block packet alone does not prove that the server accepts this menu.
    if ((stage == 2 || stage == 3) && splitDone.get() && reopenProbe.compareAndSet(false, true))
      server.execute(
          () -> {
            try {
              var player = server.getPlayerList().getPlayer(principal);
              if (player == null) {
                reopenReady.set(false);
                reopenDiagnostic.set("player absent");
                return;
              }
              var chunk =
                  player
                      .serverLevel()
                      .getChunkSource()
                      .getChunkNow(MACHINE.getX() >> 4, MACHINE.getZ() >> 4);
              var entity = chunk == null ? null : chunk.getBlockEntity(MACHINE);
              var machine = entity instanceof MachineInterfaceEntity found ? found : null;
              boolean canUse = machine != null && machine.canUse(player);
              reopenReady.set(
                  canUse
                      && player.getMainHandItem().isEmpty()
                      && player.getOffhandItem().isEmpty()
                      && !player.isShiftKeyDown());
              var runtime = WorldNetworks.get(server);
              reopenDiagnostic.set(
                  "canUse="
                      + canUse
                      + " membership="
                      + (machine == null
                          ? "absent"
                          : runtime.membership(machine.position()).status())
                      + " main="
                      + player.getMainHandItem()
                      + " off="
                      + player.getOffhandItem()
                      + " diagnostic="
                      + runtime.diagnostic());
            } catch (RuntimeException error) {
              failure.set(error);
            } finally {
              reopenProbe.set(false);
            }
          });
    if ((stage == 2 || stage == 3)
        && splitDone.get()
        && reopenReady.get()
        && (openAttempts == 0 || ticks - lastOpen >= 20)
        && ticks - entered > 30
        && client.screen == null
        && client.player.distanceToSqr(4.5, 65, 4.5) < 4
        && client.level.getBlockState(MACHINE).is(MachineInterfaceContent.BLOCK.get())) {
      lastOpen = ticks;
      openAttempts++;
      System.out.println(
          "FACTORYCORE_RESTART_OPEN attempt=" + openAttempts + " server=" + reopenDiagnostic.get());
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(MACHINE), Direction.NORTH, MACHINE, false));
      if (stage == 2) next();
      return;
    }
    if (stage == 3
        && client.screen instanceof MachineSettingsScreen screen
        && screen.getMenu().view().editable()) {
      for (var child : screen.children())
        if (child instanceof Button button
            && button.visible
            && button.getMessage().getString().equals("Presets")) {
          screen.mouseClicked(button.getX() + 10, button.getY() + 8, 0);
          next();
          return;
        }
      throw new IllegalStateException("Reopened machine has no Presets tab");
    }
    if (stage == 4
        && ticks - entered > 10
        && client.screen instanceof MachineSettingsScreen screen) {
      var view = screen.getMenu().presetView();
      require(
          view.mode() == MachinePresets.Mode.STOCK && view.goal() == 8 && view.rules().size() == 1,
          "Native reopened editor projects saved exact Stock goal8");
      for (var child : screen.children())
        if (child instanceof AbstractWidget widget && widget.visible)
          require(
              widget.getX() >= 0
                  && widget.getY() >= 0
                  && widget.getX() + widget.getWidth() <= screen.width
                  && widget.getY() + widget.getHeight() <= screen.height,
              "Every visible control fits the actual default-window GUI");
      System.out.println(
          "FACTORYCORE_RESTART_GUI "
              + screen.width
              + "x"
              + screen.height
              + " scale="
              + client.getWindow().getGuiScale());
      Screenshot.grab(
          report.getParent().toFile(),
          "restarted-machine-stock.png",
          client.getMainRenderTarget(),
          c -> System.out.println("FACTORYCORE_RESTART_SCREENSHOT " + c.getString()));
      client.player.closeContainer();
      next();
      return;
    }
    if (stage == 5 && client.screen == null && ticks - entered > 5) {
      var terminal = new BlockPos(1, 65, 2);
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(terminal), Direction.NORTH, terminal, false));
      next();
      return;
    }
    if (stage == 6 && ticks - entered > 10 && client.screen instanceof TerminalScreen screen) {
      var view = screen.getMenu().view();
      if (!view.status().equals("ready") || view.rows().isEmpty()) return;
      require(
          view.search().equals("@minecraft") && view.sort() == TerminalSearchSupport.QUANTITY,
          "Fresh JVM terminal projects saved nondefault player query and quantity sort");
      require(
          screen.children().stream()
              .anyMatch(
                  child -> child instanceof EditBox edit && edit.getValue().equals("@minecraft")),
          "Actual reopened search widget restores text");
      Screenshot.grab(
          report.getParent().toFile(),
          "restarted-terminal-preferences.png",
          client.getMainRenderTarget(),
          c -> System.out.println("FACTORYCORE_RESTART_SCREENSHOT " + c.getString()));
      try {
        Files.writeString(
            report,
            "PASS\nseedPid="
                + expected.getProperty("pid")
                + " restartPid="
                + ProcessHandle.current().pid()
                + "\nFresh JVM opened existing world without seeding or restoring from expected state.\n"
                + "Per-player schema1 terminal query @minecraft and quantity sort survived normal save and fresh JVM; actual reopened terminal and search widget project them.\n"
                + "Accepted known staging delivered5 after restart (source12 + provider8 =20); owned cleanup returned5 (source17 + provider3 =20). Post-insert uncertainty stayed frozen with no replay/refund.\n"
                + "Full canonical SavedData matched before mutation; physical/player resources and provider identity matched.\n"
                + "1200mB water:400 held/800 available;300mB lava;500FE. Native Stock editor retained goal8/exact rule.\n"
                + "After real chest removal3, resumed Stock conserved cell3 + machine8 + buffer9 + player5 =25.\n"
                + "Native chunk unload/reload retained leases/state, rejected cached offline capabilities and resumed Stock: cell2 + machine8 + buffer9 + player6 =25.\n"
                + "Actual root unload suspended continuously loaded child storage/preset for40 ticks without loading root; restored root resumed child Stock with cell13 + machine8 + player3 =24 gold, hold4 retained.\n");
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
      next();
      client.level.disconnect();
      client.disconnect();
      client.stop();
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed world belongs to Minecraft.
  private void verifyInitial(ServerPlayer player, MachineInterfaceEntity machine) throws Exception {
    var level = player.serverLevel();
    var saved = DeviceSavedData.get(player.server);
    var canonical = saved.save(new CompoundTag(), player.registryAccess());
    var previous = TagParser.parseTag(expected.getProperty("canonical"));
    require(canonical.equals(previous), "Entire canonical SavedData equals pre-save observation");
    Files.writeString(report.getParent().resolve("restored-canonical.snbt"), canonical + "\n");
    require(
        saved.registry().world().toString().equals(expected.getProperty("world"))
            && player.getUUID().toString().equals(expected.getProperty("principal")),
        "World and authenticated principal preserved");
    require(
        player
            .getPersistentData()
            .getCompound(Player.PERSISTED_NBT_TAG)
            .getCompound("FactoryCoreTerminal")
            .equals(TagParser.parseTag(expected.getProperty("terminalPreferences"))),
        "Exact player terminal preference compound matches pre-save observation");
    var root = (NetworkNodeEntity) level.getBlockEntity(ROOT);
    require(
        root.handle().id().toString().equals(expected.getProperty("root"))
            && machine.handle().id().toString().equals(expected.getProperty("machine"))
            && machine.generation() == Long.parseLong(expected.getProperty("configuration"))
            && machine.label().equals("smelter"),
        "Physical root and machine identity/settings preserved");
    var deployment = saved.machinePresets().get(machine.handle().id());
    require(
        machine.presetAuthorized(deployment), "Loaded preset retains current original authority");
    var handle = WorldNetworks.get(player.server).connectors().bind(machine).handle();
    require(
        handle != null && handle.backing().toString().equals(expected.getProperty("backing")),
        "Foreign provider component backing preserved");
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    var codec = new PlatformResourceCodec(player.registryAccess());
    var key = codec.itemKey(player.getInventory().getItem(9));
    var buffer = (BufferedInterfaceEntity) level.getBlockEntity(BUFFER);
    var chest = (ChestBlockEntity) level.getBlockEntity(MACHINE.south());
    require(
        saved.registry().stock(CellHandleData.read(drive.cell(0)), key).total() == 6
            && buffer.inventory().total(key) == 9
            && chest.getItem(2).getCount() == 8
            && codec.itemKey(chest.getItem(2)).equals(key)
            && player.getInventory().getItem(9).getCount() == 2,
        "Physical and canonical resources preserve cell6 + machine8 + buffer9 + player2 =25");
    var fluid = CellHandleData.read(drive.cell(1));
    var water = saved.registry().stock(fluid, codec.fluidKey(new FluidStack(Fluids.WATER, 1)));
    var lava = saved.registry().stock(fluid, codec.fluidKey(new FluidStack(Fluids.LAVA, 1)));
    require(
        water.loaded()
            && water.total() == 1200
            && water.reserved() == 400
            && water.available() == 800
            && lava.loaded()
            && lava.total() == 300,
        "Mixed fluids and original400mB hold persist");
    var bank = (EnergyBankEntity) level.getBlockEntity(BANK);
    require(
        bank.getEnergyStored() == 500
            && saved.registry().stock(bank.handle(), ResourceKey.ENERGY).loaded(),
        "Finite bank reactivates500FE");
    System.out.println("FACTORYCORE_RESTART_INITIAL_STATE_MATCHED");
  }

  private void next() {
    stage++;
    entered = ticks;
    System.out.println("FACTORYCORE_RESTART_STAGE " + stage);
  }
}
