package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Isolated graphical fixture, never packaged or loaded in ordinary client/server runs. */
@Mod("factorycore_client_tests")
public final class TerminalClientVerification {
  private static final BlockPos ROOT = new BlockPos(1, 65, 1),
      DRIVE = new BlockPos(2, 65, 1),
      TERMINAL = new BlockPos(1, 65, 2),
      BUFFER = new BlockPos(3, 65, 1);
  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean ready = new AtomicBoolean(), checked = new AtomicBoolean();
  private final StringBuilder evidence = new StringBuilder();
  private BufferedInterfaceEntity fixtureBuffer;
  private boolean importEnabled;
  private DeviceRegistry.Handle cell;
  private ResourceKey key;
  private int stage, ticks, entered;
  private long previousRevision;

  public TerminalClientVerification() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private static ItemStack named(int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Client hopper variant"));
    return stack;
  }

  @SuppressWarnings(
      "PMD.CloseResource") // Minecraft and its integrated server are borrowed, not fixture-owned.
  private void tick(ClientTickEvent.Post event) {
    Minecraft client = Minecraft.getInstance();
    ticks++;
    if (stage == 12) return;
    if (failure.get() != null)
      throw new IllegalStateException("Graphical terminal fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "Graphical terminal stage timeout: "
              + stage
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    if (stage == 0
        && client.screen
            instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen onboarding) {
      onboarding.onClose();
      return;
    }
    if (stage == 0 && client.screen instanceof TitleScreen) {
      client.getWindow().setWindowed(1280, 960);
      client.options.guiScale().set(2);
      client.options.pauseOnLostFocus = false;
      client
          .createWorldOpenFlows()
          .createFreshLevel(
              "factorycore-terminal-verification",
              new LevelSettings(
                  "Terminal verification",
                  GameType.CREATIVE,
                  false,
                  Difficulty.PEACEFUL,
                  true,
                  new GameRules(),
                  WorldDataConfiguration.DEFAULT),
              new WorldOptions(0x7E4A1L, false, false),
              registries ->
                  registries
                      .registryOrThrow(Registries.WORLD_PRESET)
                      .getHolderOrThrow(WorldPresets.FLAT)
                      .value()
                      .createWorldDimensions(),
              client.screen);
      next();
      return;
    }
    if (stage == 1
        && client.player != null
        && client.screen == null
        && client.getSingleplayerServer() != null) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      server.execute(
          () -> {
            try {
              setup(server.getPlayerList().getPlayer(principal));
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      next();
      return;
    }
    if (stage == 2 && client.player != null) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      if (!ready.get()) {
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                var saved = DeviceSavedData.get(server);
                if (((TerminalEntity) player.serverLevel().getBlockEntity(TERMINAL))
                    .canUse(player)) {
                  if (!importEnabled) {
                    var codec = new PlatformResourceCodec(player.registryAccess());
                    for (var item :
                        new Item[] {
                          Items.COAL,
                          Items.COPPER_INGOT,
                          Items.DIAMOND,
                          Items.EMERALD,
                          Items.GOLD_INGOT,
                          Items.IRON_INGOT,
                          Items.LAPIS_LAZULI,
                          Items.REDSTONE,
                          Items.QUARTZ
                        })
                      check(
                          saved
                                  .registry()
                                  .insert(cell, codec.itemKey(new ItemStack(item)), 3)
                                  .amount()
                              == 3,
                          "Live seed stock admission");
                    fixtureBuffer.configure(
                        new NetworkPermissions.Actor(player.getUUID(), false),
                        BufferRegistry.Mode.IMPORT);
                    ((HopperBlockEntity) player.serverLevel().getBlockEntity(BUFFER.above()))
                        .setItem(0, named(23));
                    importEnabled = true;
                  }
                  if (saved.registry().stock(cell, key).total() == 23) ready.set(true);
                }
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        return;
      }
      if (client.player.distanceToSqr(4.5, 65, 4.5) > 4
          || !client.level.getBlockState(TERMINAL).is(TerminalContent.BLOCK.get())) return;
      client.player.setYRot(135);
      client.player.setXRot(10);
      var interaction =
          client.gameMode.useItemOn(
              client.player,
              InteractionHand.MAIN_HAND,
              new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.NORTH, TERMINAL, false));
      System.out.println(
          "FACTORYCORE_CLIENT_OPEN " + interaction + " player=" + client.player.position());
      next();
      return;
    }
    if (!(client.screen instanceof TerminalScreen screen)) return;
    var menu = screen.getMenu();
    var view = menu.view();
    if (stage == 3 && view.status().equals("ready") && view.rows().size() == 8) {
      check(view.next(), "Initial eight-row page has additional stock");
      capture(client, "scale-2-all-items.png");
      previousRevision = view.revision();
      search(screen, "iron");
      next();
      return;
    }
    if (stage == 4
        && view.revision() > previousRevision
        && view.status().equals("ready")
        && view.rows().size() == 2) {
      int selected = -1;
      for (int i = 0; i < view.rows().size(); i++)
        if (view.rows().get(i).name().equals("Client hopper variant")) selected = i;
      check(selected >= 0, "Actual hopper components appear separately from plain iron");
      for (var child : screen.children()) if (child instanceof EditBox edit) edit.setFocused(false);
      screen.keyPressed(GLFW.GLFW_KEY_1 + selected, 0, 0);
      click(screen, "Take 1");
      next();
      return;
    }
    if (stage == 5
        && !client.player.getInventory().getItem(0).isEmpty()
        && client.player.getInventory().getItem(0).getCount() == 1
        && view.rows().size() == 2
        && !view.busy()) {
      check(
          ItemStack.isSameItemSameComponents(client.player.getInventory().getItem(0), named(1)),
          "Native button withdrawal delivers exact item through real connection");
      var digest =
          TerminalPackets.fingerprint(
              new PlatformResourceCodec(client.level.registryAccess())
                  .itemKey(client.player.getInventory().getItem(0)));
      PacketDistributor.sendToServer(
          new TerminalPackets.Command(
              menu.containerId,
              menu.session(),
              view.revision(),
              TerminalPackets.DEPOSIT,
              0,
              1,
              "",
              digest));
      next();
      return;
    }
    if (stage == 6 && client.player.getInventory().getItem(0).isEmpty() && !view.busy()) {
      if (!checked.get()) {
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                var saved = DeviceSavedData.get(server);
                check(
                    saved.registry().stock(cell, key).total() == 23,
                    "Deposit restores all 23 exact hopper items");
                check(
                    ((BufferedInterfaceEntity) player.serverLevel().getBlockEntity(BUFFER))
                            .inventory()
                            .total(key)
                        == 0,
                    "Real buffer drained");
                check(
                    ((HopperBlockEntity) player.serverLevel().getBlockEntity(BUFFER.above()))
                        .isEmpty(),
                    "Real hopper drained");
                checked.set(true);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        return;
      }
      client.options.guiScale().set(3);
      client.resizeDisplay();
      next();
      return;
    }
    if (stage == 7 && ticks - entered > 10) {
      check(
          screen.children().stream()
              .anyMatch(child -> child instanceof EditBox edit && edit.getValue().equals("iron")),
          "Search text survives actual GUI-scale reinitialization");
      capture(client, "scale-3-iron.png");
      client.options.guiScale().set(4);
      client.resizeDisplay();
      next();
      return;
    }
    if (stage == 8 && ticks - entered > 10) {
      capture(client, "scale-4-iron.png");
      search(screen, "");
      next();
      return;
    }
    if (stage == 9 && view.rows().size() == 8 && view.next() && !view.busy()) {
      previousRevision = view.revision();
      click(screen, "Next");
      next();
      return;
    }
    if (stage == 10
        && ticks - entered >= 4
        && view.revision() > previousRevision
        && view.rows().size() == 2
        && !view.next()) {
      capture(client, "scale-4-next-page.png");
      click(screen, "Home");
      next();
      return;
    }
    if (stage == 11 && view.rows().size() == 8 && view.next()) {
      try {
        Files.createDirectories(report.getParent());
        Files.writeString(
            report,
            "PASS\n"
                + evidence
                + "Input: native search Enter, row number key, Take 1/Next/Home mouse handlers; deposit uses real C2S payload. Physical Shift gesture remains unverified.\n");
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
      next();
      client.level.disconnect();
      client.disconnect();
      client.stop();
    }
  }

  private void next() {
    stage++;
    entered = ticks;
    System.out.println("FACTORYCORE_CLIENT_STAGE " + stage);
  }

  @SuppressWarnings(
      "PMD.CloseResource") // The player's live ServerLevel remains owned by Minecraft.
  private void setup(ServerPlayer player) {
    var level = player.serverLevel();
    level.setDayTime(6000);
    for (int x = -2; x < 8; x++)
      for (int z = -2; z < 8; z++)
        level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 3);
    level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(player.getUUID());
    level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    cell = CellHandleData.read(drive.cell(0));
    level.setBlock(TERMINAL, TerminalContent.BLOCK.get().defaultBlockState(), 3);
    level.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get().defaultBlockState(), 3);
    var buffer = (BufferedInterfaceEntity) level.getBlockEntity(BUFFER);
    fixtureBuffer = buffer;
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), player.getUUID());
    level.setBlock(
        BUFFER.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN),
        3);
    var codec = new PlatformResourceCodec(level.registryAccess());
    key = codec.itemKey(named(1));
    player.getInventory().clearContent();
    player.teleportTo(4.5, 65, 4.5);
    player.setYRot(135);
    player.setXRot(10);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static void search(TerminalScreen screen, String text) {
    for (var child : screen.children())
      if (child instanceof EditBox edit) {
        edit.setValue("");
        edit.setFocused(true);
        screen.setFocused(edit);
        for (char character : text.toCharArray()) screen.charTyped(character, 0);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        return;
      }
    throw new IllegalStateException("Search widget missing");
  }

  private static void click(TerminalScreen screen, String text) {
    for (var child : screen.children())
      if (child instanceof Button button && button.getMessage().getString().equals(text)) {
        check(button.active, "Action enabled: " + text);
        screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
        return;
      }
    throw new IllegalStateException("Action missing: " + text);
  }

  @SuppressWarnings("PMD.CloseResource") // Screenshot borrows the game's active Window.
  private void capture(Minecraft client, String name) {
    try {
      Files.createDirectories(report.getParent().resolve("screenshots"));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot prepare screenshot directory", error);
    }
    var window = client.getWindow();
    evidence
        .append(name)
        .append(" framebuffer=")
        .append(window.getWidth())
        .append('x')
        .append(window.getHeight())
        .append(" gui=")
        .append(window.getGuiScaledWidth())
        .append('x')
        .append(window.getGuiScaledHeight())
        .append(" scale=")
        .append(window.getGuiScale())
        .append('\n');
    Screenshot.grab(
        report.getParent().toFile(),
        name,
        client.getMainRenderTarget(),
        component -> System.out.println("FACTORYCORE_CLIENT_SCREENSHOT " + component.getString()));
  }
}
