package dev.izzy.factorycore.clienttesting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.MachineSettingsScreen;
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
import net.minecraft.client.gui.screens.Screen;
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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Isolated graphical fixture, never packaged or loaded in ordinary client/server runs. */
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
  private final RestartCheckpoint restartCheckpoint = new RestartCheckpoint();
  private DeviceRegistry.Handle cell;
  private ResourceKey key;
  private static final int DONE = 99;
  private static final BlockPos GATEWAY = new BlockPos(0, 65, 1),
      BRANCH = new BlockPos(-1, 65, 1),
      PROGRAM = new BlockPos(1, 65, 0),
      ACCESS_POINT = new BlockPos(1, 66, 1);
  private int stage, ticks, entered, rule, craftStep, policyMask;
  private boolean sampleSeeded;
  private long previousRevision;

  public TerminalClientVerification() {
    if (Boolean.getBoolean("factorycore.client.restart")) new WorldRestartVerification().register();
    else NeoForge.EVENT_BUS.addListener(this::tick);
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
    if (stage == DONE) return;
    if (failure.get() != null)
      throw new IllegalStateException("Graphical terminal fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "Graphical terminal stage timeout: "
              + stage
              + " craftStep="
              + craftStep
              + (client.screen instanceof TerminalScreen terminal
                  ? " grid=" + terminal.getMenu().gridView()
                  : "")
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    if (stage >= 61) {
      tourTick(client);
      return;
    }
    if (stage >= 39) {
      machineTick(client);
      return;
    }
    if (stage >= 12) {
      bufferTick(client);
      return;
    }
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
    if (stage == 11 && craftStep < 8 && (craftStep > 0 || view.rows().size() == 8 && view.next())) {
      craftTick(client, screen);
      return;
    }
    if (stage == 11 && craftStep == 8) {
      client.player.closeContainer();
      client.options.guiScale().set(2);
      client.resizeDisplay();
      next();
    }
  }

  private int plankSlot = -1;

  /** Manual grid: cell click, own-slot ghost, Row→, right-click clear, Craft and two scales. */
  @SuppressWarnings("PMD.CloseResource") // Borrowed active client/server/window.
  private void craftTick(Minecraft client, TerminalScreen screen) {
    var menu = screen.getMenu();
    var grid = menu.gridView();
    var server = client.getSingleplayerServer();
    var principal = client.player.getUUID();
    double left = (screen.width - 344) / 2.0, top = (screen.height - 232) / 2.0;
    switch (craftStep) {
      case 0 -> {
        server.execute(
            () ->
                server
                    .getPlayerList()
                    .getPlayer(principal)
                    .getInventory()
                    .setItem(20, new ItemStack(Items.OAK_LOG)));
        craftStep++;
      }
      case 1 -> {
        if (!client.player.getInventory().getItem(20).is(Items.OAK_LOG)) return;
        screen.mouseClicked(left + TerminalScreen.GRID_X + 9, top + TerminalScreen.GRID_Y + 9, 0);
        var digest =
            TerminalPackets.fingerprint(
                new PlatformResourceCodec(client.level.registryAccess())
                    .itemKey(client.player.getInventory().getItem(20)));
        PacketDistributor.sendToServer(
            new TerminalCraftingPackets.Command(
                menu.containerId,
                menu.session(),
                menu.view().revision(),
                grid.gridRevision(),
                TerminalCraftingPackets.SET_OWN,
                0,
                20,
                1,
                digest));
        craftStep++;
      }
      case 2 -> {
        if (!grid.result().equals("minecraft:oak_planks")) return;
        check(grid.resultCount() == 4, "Actual server recipe preview for one log");
        checkNoOverlap(screen);
        capture(client, "terminal-grid-scale-4.png");
        screen.mouseClicked(left + TerminalScreen.GRID_X + 27, top + TerminalScreen.GRID_Y + 9, 0);
        for (var child : screen.children())
          if (child instanceof EditBox edit) edit.setFocused(false);
        screen.keyPressed(GLFW.GLFW_KEY_1, 0, 0);
        click(screen, "Row→");
        craftStep++;
      }
      case 3 -> {
        if (grid.cells().get(1).isEmpty() || !grid.result().isEmpty()) return;
        check(grid.status().equals("ready"), "Row ghost breaks the recipe without moving stock");
        screen.mouseClicked(left + TerminalScreen.GRID_X + 27, top + TerminalScreen.GRID_Y + 9, 1);
        craftStep++;
      }
      case 4 -> {
        if (!grid.cells().get(1).isEmpty() || !grid.result().equals("minecraft:oak_planks")) return;
        click(screen, "Craft");
        craftStep++;
      }
      case 5 -> {
        var inventory = client.player.getInventory();
        plankSlot = inventory.findSlotMatchingItem(new ItemStack(Items.OAK_PLANKS));
        if (!grid.status().equals("crafted") || plankSlot < 0) return;
        check(
            inventory.getItem(20).isEmpty()
                && inventory.countItem(Items.OAK_LOG) == 0
                && inventory.countItem(Items.OAK_PLANKS) == 4,
            "Actual Craft button consumes the log and returns four planks to a free slot");
        client.options.guiScale().set(2);
        client.resizeDisplay();
        entered = ticks;
        craftStep++;
      }
      case 6 -> {
        if (ticks - entered < 10) return;
        checkNoOverlap(screen);
        capture(client, "terminal-grid-scale-2.png");
        server.execute(
            () ->
                server
                    .getPlayerList()
                    .getPlayer(principal)
                    .getInventory()
                    .setItem(plankSlot, ItemStack.EMPTY));
        click(screen, "Clear");
        craftStep++;
      }
      case 7 -> {
        if (!grid.cells().stream().allMatch(String::isEmpty)
            || !client.player.getInventory().getItem(plankSlot).isEmpty()) return;
        evidence.append(
            "terminal-grid: own ghost, Row→, right-click clear, Craft 1 log -> 4 planks\n");
        craftStep++;
      }
      default -> {}
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed active client/server/window.
  private void bufferTick(Minecraft client) {
    if (stage == 12 && client.screen == null && ticks - entered > 4) {
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(BUFFER), Direction.SOUTH, BUFFER, false));
      next();
      return;
    }
    if (!(client.screen instanceof BufferSettingsScreen screen)) return;
    var menu = screen.getMenu();
    var view = menu.view();
    if (ticks - entered < 4 || view.busy()) return;
    switch (stage) {
      case 13 -> {
        if (!view.editable() || view.slots().size() != 9) return;
        check(
            view.mode().equals("IMPORT") && !view.dirty(),
            "Native buffer menu opens saved Import settings");
        capture(client, "buffer-scale-2-settings.png");
        click(screen, "Import");
        next();
      }
      case 14 -> {
        if (!view.mode().equals("EXPORT")) return;
        click(screen, "Export");
        next();
      }
      case 15 -> {
        if (!view.mode().equals("STOCK")) return;
        edit(screen, 0, "8", true);
        next();
      }
      case 16 -> {
        if (view.rate() != 8) return;
        edit(screen, 1, "8", true);
        next();
      }
      case 17 -> {
        if (view.target() != 8 || view.rules().size() != rule) return;
        String[] rules = {
          "minecraft:iron_ingot",
          "minecraft:gold_ingot",
          "minecraft:copper_ingot",
          "minecraft:coal",
          "minecraft:redstone",
          "minecraft:diamond",
          "#minecraft:logs"
        };
        if (rule < rules.length) {
          edit(screen, 2, rules[rule], false);
          click(screen, rule == 6 ? "Tag" : "Item ID");
          rule++;
          entered = ticks;
          return;
        }
        check(
            view.dirty() && view.filter().equals("INCLUDE"),
            "Native registry/tag edits remain separate draft");
        capture(client, "buffer-scale-2-rules-page-1.png");
        click(screen, "Next");
        next();
      }
      case 18 -> {
        capture(client, "buffer-scale-2-rules-page-2.png");
        if (!sampleSeeded) {
          var server = client.getSingleplayerServer();
          var principal = client.player.getUUID();
          server.execute(
              () -> {
                try {
                  server.getPlayerList().getPlayer(principal).getInventory().setItem(9, named(2));
                } catch (RuntimeException error) {
                  failure.set(error);
                }
              });
          sampleSeeded = true;
        }
        click(screen, "Sample");
        next();
      }
      case 19 -> {
        if (client.player.getInventory().getItem(9).getCount() != 2) return;
        pointer(client, screen, 87, 111);
        next();
      }
      case 20 -> {
        sample(screen);
        next();
      }
      case 21 -> {
        if (view.rules().size() != 8) return;
        check(
            client.player.getInventory().getItem(9).getCount() == 2 && !menu.inventoryTab(),
            "Native exact ghost click keeps both sample items");
        click(screen, "Clear");
        next();
      }
      case 22 -> {
        if (!view.rules().isEmpty()) return;
        click(screen, "Sample");
        pointer(client, screen, 87, 111);
        next();
      }
      case 23 -> {
        sample(screen);
        next();
      }
      case 24 -> {
        if (view.rules().size() != 1) return;
        check(
            view.rules().getFirst().contains("Client hopper variant"),
            "Exact native sample rule includes component name");
        click(screen, "Apply");
        next();
      }
      case 25 -> {
        if (view.dirty()
            || !view.status().equals("applied")
            || view.slots().getFirst().count() != 8) return;
        click(screen, "Inventory");
        next();
      }
      case 26 -> {
        capture(client, "buffer-scale-2-stock.png");
        click(screen, "Take 1");
        next();
      }
      case 27 -> {
        if (client.player.getInventory().getItem(9).getCount() != 3
            || view.slots().getFirst().count() != 8) return;
        check(
            ItemStack.isSameItemSameComponents(client.player.getInventory().getItem(9), named(1)),
            "Stock Take1 delivers exact named item and replenishes goal");
        deposit(client, menu, 9, 1);
        next();
      }
      case 28 -> {
        if (!view.status().equals("full")) return;
        check(
            client.player.getInventory().getItem(9).getCount() == 3
                && view.slots().getFirst().count() == 8,
            "Full Stock goal retains player deposit without extraction");
        capture(client, "buffer-scale-2-full.png");
        click(screen, "Stock");
        next();
      }
      case 29 -> {
        if (!view.mode().equals("PAUSED")) return;
        click(screen, "Apply");
        next();
      }
      case 30 -> {
        if (view.dirty() || !view.status().equals("applied")) return;
        deposit(client, menu, 9, 1);
        next();
      }
      case 31 -> {
        if (client.player.getInventory().getItem(9).getCount() != 2
            || view.slots().getFirst().count() != 9) return;
        click(screen, "Take 64");
        next();
      }
      case 32 -> {
        if (client.player.getInventory().getItem(9).getCount() != 10
            || view.slots().getFirst().count() != 1) return;
        check(
            view.status().equals("partial") && view.moved() == 8 && view.requested() == 64,
            "Take64 reports actual eight of64 under shared rate");
        deposit(client, menu, 9, 8);
        next();
      }
      case 33 -> {
        if (client.player.getInventory().getItem(9).getCount() != 2
            || view.slots().getFirst().count() != 9) return;
        checked.set(false);
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                var saved = DeviceSavedData.get(server);
                check(
                    saved.registry().stock(cell, key).total() == 14
                        && fixtureBuffer.inventory().total(key) == 9
                        && player.getInventory().getItem(9).getCount() == 2
                        && player.getInventory().getItem(0).isEmpty(),
                    "Exact conservation: cell14 + buffer9 + original sample2 =25");
                var deployed = saved.buffers().deployment(fixtureBuffer.handle().id());
                check(
                    deployed.mode() == BufferRegistry.Mode.PAUSED
                        && deployed.policy().rate() == 8
                        && deployed.policy().target() == 8
                        && deployed.policy().filter().terms().getFirst()
                            instanceof ResourceFilter.Exact,
                    "Client Apply persists exact paused policy");
                checked.set(true);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        client.options.guiScale().set(3);
        client.resizeDisplay();
        next();
      }
      case 34 -> {
        if (!checked.get()) return;
        capture(client, "buffer-scale-3-inventory.png");
        click(screen, "Settings");
        next();
      }
      case 35 -> {
        capture(client, "buffer-scale-3-settings.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        next();
      }
      case 36 -> {
        capture(client, "buffer-scale-4-settings.png");
        click(screen, "Inventory");
        next();
      }
      case 37 -> {
        capture(client, "buffer-scale-4-inventory.png");
        next();
      }
      case 38 -> {
        client.player.closeContainer();
        ready.set(false);
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                var level = player.serverLevel();
                level.setBlock(
                    new BlockPos(2, 65, 2),
                    MachineInterfaceContent.BLOCK
                        .get()
                        .defaultBlockState()
                        .setValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties
                                .FACING,
                            Direction.SOUTH),
                    3);
                ((MachineInterfaceEntity) level.getBlockEntity(new BlockPos(2, 65, 2)))
                    .place(new ItemStack(MachineInterfaceContent.ITEM.get()));
                level.setBlock(new BlockPos(2, 65, 3), Blocks.CHEST.defaultBlockState(), 3);
                ready.set(true);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        client.options.guiScale().set(2);
        client.resizeDisplay();
        next();
      }
      default -> throw new IllegalStateException("Unexpected buffer client stage " + stage);
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed integrated server/client levels.
  private void machineTick(Minecraft client) {
    var location = new BlockPos(2, 65, 2);
    if (ticks - entered < 5) return;
    if (stage == 39 && ready.get() && client.screen == null) {
      next();
      return;
    }
    if (stage == 40 && ready.get()) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      server.execute(
          () -> {
            try {
              var player = server.getPlayerList().getPlayer(principal);
              if (((MachineInterfaceEntity) player.serverLevel().getBlockEntity(location))
                  .canUse(player)) ready.set(false);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 40
        && !ready.get()
        && client.screen == null
        && client.level.getBlockState(location).is(MachineInterfaceContent.BLOCK.get())) {
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(location), Direction.NORTH, location, false));
      next();
      return;
    }
    if (stage == 45 && client.screen == null) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      if (!checked.get()) {
        server.execute(
            () -> {
              try {
                var machine =
                    (MachineInterfaceEntity)
                        server
                            .getPlayerList()
                            .getPlayer(principal)
                            .serverLevel()
                            .getBlockEntity(location);
                check(
                    machine.label().isEmpty() && machine.settings().rate() == 64,
                    "Native Cancel discards the complete machine draft");
                checked.set(true);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        return;
      }
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(location), Direction.NORTH, location, false));
      next();
      return;
    }
    if (stage == 47 && client.screen == null) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      server.execute(
          () -> {
            try {
              var machine =
                  (MachineInterfaceEntity)
                      server
                          .getPlayerList()
                          .getPlayer(principal)
                          .serverLevel()
                          .getBlockEntity(location);
              check(
                  machine.label().equals("smelter")
                      && machine.settings().rate() == 8
                      && machine.settings().firstSlot() == 2
                      && machine.settings().slotCount() == 1
                      && machine.settings().priority() == -7
                      && machine.settings().access()
                          == dev.izzy.factorycore.core.storage.ConnectorSettings.Access.READ_ONLY,
                  "Native Apply publishes exact complete machine settings");
              checked.set(true);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      next();
      return;
    }
    if (stage == 48 && checked.get() && client.screen == null) {
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(location), Direction.NORTH, location, false));
      next();
      return;
    }
    if (stage == 50 && client.screen == null) {
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(location), Direction.NORTH, location, false));
      next();
      return;
    }
    if (stage == 59 && !checked.get()) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      server.execute(
          () -> {
            try {
              var player = server.getPlayerList().getPlayer(principal);
              var chest =
                  (net.minecraft.world.level.block.entity.ChestBlockEntity)
                      player.serverLevel().getBlockEntity(location.south());
              if (chest.getItem(2).getCount() != 8) return;
              check(
                  chest.getItem(0).isEmpty() && chest.getItem(1).isEmpty(),
                  "Stock respects selected machine window");
              check(
                  DeviceSavedData.get(server).registry().stock(cell, key).total() == 6
                      && player.getInventory().getItem(9).getCount() == 2
                      && fixtureBuffer.inventory().total(key) == 9,
                  "Actual client Stock conserves cell6 + machine8 + buffer9 + sample2 =25");
              checked.set(true);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 59 && checked.get()) {
      capture(client, "machine-preset-scale-4-stock.png");
      client.player.closeContainer();
      checked.set(false);
      jump(61);
      return;
    }
    if (stage == 60 && !checked.get()) {
      var server = client.getSingleplayerServer();
      var principal = client.player.getUUID();
      server.execute(
          () -> {
            if (checked.get()) return;
            try {
              if (restartCheckpoint.prepare(
                  server.getPlayerList().getPlayer(principal),
                  report.getParent().resolve("restart.properties"))) checked.set(true);
            } catch (IOException | RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 60 && checked.get()) {
      checked.set(false);
      jump(72);
      return;
    }
    if (!(client.screen instanceof MachineSettingsScreen screen)
        || !screen.getMenu().view().editable()) return;
    machineScreenTick(client, screen);
  }

  private void writeReport(Minecraft client) {
    try {
      Files.writeString(
          report,
          "PASS\n"
              + evidence
              + "Tour: gateway policy toggle (actual sneak-use C2S), program editor, Factory Guide pages 1-2 (actual item use), wireless handheld terminal beyond wired reach, terminal Fluids/Energy views; no overlapping controls.\n"
              + "Input: terminal search/row/Take1/Next/Home; buffer mode/rate/goal/rules/paging/exact-sample/Apply/Take1/Take64; machine Reset/Cancel/Apply, preset mode/exact sample/goal/Deploy and actual Stock, native text and GUI-scale draft retention. Deposits use real C2S payloads; physical Shift remains unverified.\n"
              + "Conservation: named cell6 + machine8 + buffer9 + original sample2 =25. Machine smelter: READ_WRITE, priority-7, first2/count1/rate8; Stock goal8 exact named sample. Restart checkpoint adds1200mB water (400 held),300mB lava and500FE.\n");
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    stage = DONE;
    client.level.disconnect();
    client.disconnect();
    client.stop();
  }

  /**
   * Screens added after the original flow. Before the restart checkpoint (61–71): gateway policy
   * editor, program editor, Factory Guide and wireless terminal (80–83). After it, with fluids and
   * FE stocked (72–76): terminal Fluids/Energy views. Every capture also asserts that no visible
   * controls overlap.
   */
  @SuppressWarnings("PMD.CloseResource") // Borrowed active client and integrated server.
  private void tourTick(Minecraft client) {
    if (ticks - entered < 5) return;
    var server = client.getSingleplayerServer();
    var principal = client.player.getUUID();
    switch (stage) {
      case 61 -> {
        if (client.screen != null) return;
        client.options.guiScale().set(2);
        client.resizeDisplay();
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                var level = player.serverLevel();
                level.setBlock(
                    GATEWAY,
                    NetworkContent.GATEWAY
                        .get()
                        .defaultBlockState()
                        .setValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties
                                .FACING,
                            Direction.WEST),
                    3);
                ((NetworkNodeEntity) level.getBlockEntity(GATEWAY)).commission(null);
                level.setBlock(BRANCH, NetworkContent.CABLE.get().defaultBlockState(), 3);
                level.setBlock(
                    PROGRAM,
                    dev.izzy.factorycore.platform.program.ProgramContent.BLOCK
                        .get()
                        .defaultBlockState(),
                    3);
                player.teleportTo(0.5, 65, 3.5);
                player.setYRot(180);
                player.setXRot(20);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        next();
      }
      case 62 -> {
        if (!checked.get()) {
          server.execute(
              () -> {
                var membership =
                    WorldNetworks.get(server)
                        .membership(
                            new NodeRegistry.Position("minecraft:overworld", BRANCH.asLong()));
                if (membership.status() == NetworkTopology.Status.VALID) checked.set(true);
              });
          return;
        }
        checked.set(false);
        shift(client, true);
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(GATEWAY), Direction.UP, GATEWAY, false));
        next();
      }
      case 63 -> {
        if (!(client.screen
                instanceof dev.izzy.factorycore.platform.client.GatewayPolicyScreen screen)
            || !screen.getMenu().editable()) return;
        shift(client, false);
        policyMask = screen.getMenu().policy().mask();
        var first =
            screen.children().stream()
                .filter(child -> child instanceof Button)
                .map(child -> (Button) child)
                .findFirst()
                .orElseThrow();
        screen.mouseClicked(
            first.getX() + first.getWidth() / 2.0, first.getY() + first.getHeight() / 2.0, 0);
        next();
      }
      case 64 -> {
        if (!(client.screen
                instanceof dev.izzy.factorycore.platform.client.GatewayPolicyScreen screen)
            || screen.getMenu().policy().mask() == policyMask) return;
        check(
            (screen.getMenu().policy().mask() ^ policyMask) == 1,
            "Actual policy click flips exactly the first branch permission");
        for (var header : new String[] {"branch", "inherited", "kinds"})
          check(
              client.font.width(Component.translatable("gui.factorycore.gateway_policy." + header))
                  <= 94,
              "Policy column header fits: " + header);
        checkNoOverlap(screen);
        capture(client, "gateway-policy-scale-2.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        next();
      }
      case 65 -> {
        if (ticks - entered < 10) return;
        checkNoOverlap(client.screen);
        capture(client, "gateway-policy-scale-4.png");
        client.player.closeContainer();
        client.options.guiScale().set(2);
        client.resizeDisplay();
        evidence.append("gateway-policy: owner toggled the first branch permission\n");
        next();
      }
      case 66 -> {
        if (client.screen != null) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(PROGRAM), Direction.UP, PROGRAM, false));
        next();
      }
      case 67 -> {
        if (!(client.screen instanceof dev.izzy.factorycore.platform.client.ProgramScreen screen)
            || ticks - entered < 10) return;
        checkNoOverlap(screen);
        capture(client, "program-editor-scale-2.png");
        client.player.closeContainer();
        server.execute(
            () ->
                server
                    .getPlayerList()
                    .getPlayer(principal)
                    .getInventory()
                    .setItem(
                        0,
                        new ItemStack(
                            dev.izzy.factorycore.platform.guide.GuideContent.GUIDE.get())));
        next();
      }
      case 68 -> {
        if (client.screen != null
            || !client
                .player
                .getInventory()
                .getItem(0)
                .is(dev.izzy.factorycore.platform.guide.GuideContent.GUIDE.get())) return;
        client.player.getInventory().selected = 0;
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next();
      }
      case 69 -> {
        if (!(client.screen
            instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen screen)) return;
        capture(client, "guide-page-1.png");
        screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
        next();
      }
      case 70 -> {
        capture(client, "guide-page-2.png");
        client.screen.onClose();
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(principal);
              player.getInventory().setItem(0, ItemStack.EMPTY);
              player.teleportTo(4.5, 65, 4.5);
              player.setYRot(135);
              player.setXRot(10);
            });
        evidence.append("guide: item use opened the Factory Guide; paged 1 -> 2\n");
        next();
      }
      case 71 -> {
        if (client.screen != null || !client.player.getInventory().getItem(0).isEmpty()) return;
        jump(80);
      }
      case 80 -> {
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                var level = player.serverLevel();
                level.setBlock(
                    ACCESS_POINT,
                    dev.izzy.factorycore.platform.wireless.WirelessContent.ACCESS_POINT
                        .get()
                        .defaultBlockState(),
                    3);
                level.setBlock(new BlockPos(20, 64, 4), Blocks.STONE.defaultBlockState(), 3);
                var handheld =
                    new ItemStack(
                        dev.izzy.factorycore.platform.wireless.WirelessContent.HANDHELD.get());
                handheld.set(
                    dev.izzy.factorycore.platform.wireless.WirelessContent.BINDING.get(),
                    new dev.izzy.factorycore.platform.wireless.WirelessContent.Binding(
                        level.dimension().location().toString(), ACCESS_POINT.asLong()));
                handheld
                    .getCapability(
                        net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM)
                    .receiveEnergy(1_000, false);
                player.getInventory().setItem(0, handheld);
                player.getInventory().selected = 0;
                player.teleportTo(20.5, 65, 4.5);
                player.setYRot(90);
                player.setXRot(10);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        next();
      }
      case 81 -> {
        powerAccessPoint(server);
        if (client.screen != null
            || client.player.distanceToSqr(20.5, 65, 4.5) > 1
            || !client
                .player
                .getInventory()
                .getItem(0)
                .is(dev.izzy.factorycore.platform.wireless.WirelessContent.HANDHELD.get())) return;
        client.player.getInventory().selected = 0;
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next();
      }
      case 82 -> {
        powerAccessPoint(server);
        if (!(client.screen instanceof TerminalScreen screen)) return;
        var view = screen.getMenu().view();
        if (!view.status().equals("ready") || view.rows().isEmpty() || ticks - entered < 10) return;
        checkNoOverlap(screen);
        capture(client, "wireless-terminal-scale-2.png");
        evidence.append(
            "wireless: bound handheld opened the terminal "
                + Math.round(Math.sqrt(client.player.distanceToSqr(Vec3.atCenterOf(ACCESS_POINT))))
                + " blocks from the access point; rows="
                + view.rows().size()
                + "\n");
        client.player.closeContainer();
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(principal);
              player.getInventory().setItem(0, ItemStack.EMPTY);
              player.teleportTo(4.5, 65, 4.5);
              player.setYRot(135);
              player.setXRot(10);
            });
        next();
      }
      case 83 -> {
        if (client.screen != null
            || !client.player.getInventory().getItem(0).isEmpty()
            || client.player.distanceToSqr(4.5, 65, 4.5) > 1) return;
        jump(60);
      }
      case 72 -> {
        if (client.screen != null) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.UP, TERMINAL, false));
        next();
      }
      case 73 -> {
        if (!(client.screen instanceof TerminalScreen screen)
            || !screen.getMenu().view().status().equals("ready")) return;
        previousRevision = screen.getMenu().view().revision();
        click(screen, "Items");
        next();
      }
      case 74, 75 -> {
        if (!(client.screen instanceof TerminalScreen screen)) return;
        var view = screen.getMenu().view();
        if (view.revision() <= previousRevision
            || view.busy()
            || view.rows().isEmpty()
            || ticks - entered < 10) return;
        String name = stage == 74 ? "Fluids" : "Energy";
        checkNoOverlap(screen);
        capture(client, "terminal-" + name.toLowerCase(java.util.Locale.ROOT) + "-scale-2.png");
        evidence.append(
            "terminal-"
                + name
                + ": "
                + view.rows().stream().map(row -> row.name() + "=" + row.total()).toList()
                + "\n");
        previousRevision = view.revision();
        click(screen, name);
        next();
      }
      case 76 -> {
        client.player.closeContainer();
        writeReport(client);
      }
      default -> throw new IllegalStateException("Unknown tour stage " + stage);
    }
  }

  /** Keeps the fixture access point above its 16 FE/tick upkeep while the handheld is in use. */
  @SuppressWarnings("PMD.CloseResource") // Borrowed integrated server.
  private static void powerAccessPoint(net.minecraft.server.MinecraftServer server) {
    server.execute(
        () -> {
          if (server.overworld().getBlockEntity(ACCESS_POINT)
              instanceof dev.izzy.factorycore.platform.wireless.AccessPointEntity point)
            point.energyStorage().receiveEnergy(1_000, false);
        });
  }

  /** Sends the actual sneak state the server reads for gateway sneak-use. */
  private static void shift(Minecraft client, boolean down) {
    client.player.connection.send(
        new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
            client.player,
            down
                ? net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action
                    .PRESS_SHIFT_KEY
                : net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action
                    .RELEASE_SHIFT_KEY));
  }

  @SuppressWarnings("PMD.CloseResource") // Borrowed active client.
  private void machineScreenTick(Minecraft client, MachineSettingsScreen screen) {
    switch (stage) {
      case 41 -> {
        edit(screen, 0, "discarded", false);
        click(screen, "Reset");
        check(machineLabel(screen).isEmpty(), "Native Reset restores saved machine label");
        fillMachine(screen);
        next();
      }
      case 42 -> {
        check(machineLabel(screen).equals("smelter"), "Machine draft populated at scale2");
        capture(client, "machine-scale-2-draft.png");
        client.options.guiScale().set(3);
        client.resizeDisplay();
        next();
      }
      case 43 -> {
        check(machineLabel(screen).equals("smelter"), "Machine draft survives GUI scale3");
        capture(client, "machine-scale-3-draft.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        next();
      }
      case 44 -> {
        check(machineLabel(screen).equals("smelter"), "Machine draft survives GUI scale4");
        capture(client, "machine-scale-4-draft.png");
        click(screen, "Cancel");
        checked.set(false);
        next();
      }
      case 46 -> {
        check(machineLabel(screen).isEmpty(), "Reopened form has saved values after Cancel");
        capture(client, "machine-scale-4-reopened.png");
        fillMachine(screen);
        checked.set(false);
        click(screen, "Apply & Close");
        next();
      }
      case 49 -> {
        click(screen, "Withdraw only");
        click(screen, "Deposit only");
        click(screen, "Apply & Close");
        next();
      }
      case 51 -> {
        click(screen, "Presets");
        next();
      }
      case 52 -> {
        click(screen, "Paused");
        next();
      }
      case 53 -> {
        click(screen, "Import");
        next();
      }
      case 54 -> {
        click(screen, "Export");
        next();
      }
      case 55 -> {
        check(
            screen.getMenu().presetView().mode()
                == dev.izzy.factorycore.core.storage.MachinePresets.Mode.STOCK,
            "Actual mode commands author Stock draft");
        click(screen, "Copy sample");
        next();
      }
      case 56 -> {
        check(
            screen.getMenu().presetView().rules().size() == 1,
            "Actual own named sample creates exact rule");
        edit(screen, 5, "8", true);
        client.options.guiScale().set(2);
        client.resizeDisplay();
        next();
      }
      case 57 -> {
        capture(client, "machine-preset-scale-2-draft.png");
        client.options.guiScale().set(3);
        client.resizeDisplay();
        next();
      }
      case 58 -> {
        capture(client, "machine-preset-scale-3-draft.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        click(screen, "Deploy");
        checked.set(false);
        next();
      }
      default -> {}
    }
  }

  private static String machineLabel(Screen screen) {
    return screen.children().stream()
        .filter(c -> c instanceof EditBox)
        .map(c -> ((EditBox) c).getValue())
        .findFirst()
        .orElseThrow();
  }

  private static void fillMachine(Screen screen) {
    edit(screen, 0, "smelter", false);
    edit(screen, 1, "-7", false);
    edit(screen, 2, "2", false);
    edit(screen, 3, "1", false);
    edit(screen, 4, "8", false);
    click(screen, "Deposit and withdraw");
  }

  private static void edit(Screen screen, int index, String text, boolean enter) {
    var fields =
        screen.children().stream()
            .filter(child -> child instanceof EditBox)
            .map(child -> (EditBox) child)
            .toList();
    for (var field : fields) field.setFocused(false);
    var field = fields.get(index);
    field.setValue("");
    field.setFocused(true);
    screen.setFocused(field);
    for (char c : text.toCharArray()) screen.charTyped(c, 0);
    if (enter) screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
  }

  @SuppressWarnings(
      "PMD.CloseResource") // Minecraft owns the native Window and its registered cursor callback.
  private static void pointer(Minecraft client, BufferSettingsScreen screen, int x, int y) {
    var window = client.getWindow();
    double guiX = (screen.width - 320) / 2.0 + x, guiY = (screen.height - 232) / 2.0 + y;
    double cursorX = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
    double cursorY = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
    var callback = GLFW.glfwSetCursorPosCallback(window.getWindow(), null);
    GLFW.glfwSetCursorPosCallback(window.getWindow(), callback);
    check(callback != null, "Minecraft owns a registered native cursor callback");
    callback.invoke(window.getWindow(), cursorX, cursorY);
  }

  private static void sample(BufferSettingsScreen screen) {
    var slot = screen.getSlotUnderMouse();
    check(
        slot != null && slot.getContainerSlot() == 9,
        "Actual renderer hovers sample inventory slot");
    screen.mouseClicked((screen.width - 320) / 2.0 + 87, (screen.height - 232) / 2.0 + 111, 0);
  }

  private static void deposit(Minecraft client, BufferSettingsMenu menu, int slot, int amount) {
    var stack = client.player.getInventory().getItem(slot);
    var digest =
        TerminalPackets.fingerprint(
            new PlatformResourceCodec(client.level.registryAccess()).itemKey(stack));
    PacketDistributor.sendToServer(
        new BufferSettingsPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            BufferSettingsPackets.DEPOSIT,
            slot,
            amount,
            "",
            digest));
  }

  private void jump(int target) {
    stage = target - 1;
    next();
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

  /** Visible controls must not overlap, and button labels must fit, at the current GUI scale. */
  private static void checkNoOverlap(Screen screen) {
    var widgets =
        screen.children().stream()
            .filter(
                child ->
                    child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                        && widget.visible)
            .map(child -> (net.minecraft.client.gui.components.AbstractWidget) child)
            .toList();
    for (int first = 0; first < widgets.size(); first++)
      for (int second = first + 1; second < widgets.size(); second++) {
        var a = widgets.get(first);
        var b = widgets.get(second);
        check(
            a.getRight() <= b.getX()
                || b.getRight() <= a.getX()
                || a.getBottom() <= b.getY()
                || b.getBottom() <= a.getY(),
            "Controls overlap: " + a.getMessage().getString() + " / " + b.getMessage().getString());
      }
    var font = Minecraft.getInstance().font;
    for (var widget : widgets)
      if (widget instanceof Button button)
        check(
            font.width(button.getMessage()) <= button.getWidth() - 4,
            "Label does not fit its button: " + button.getMessage().getString());
  }

  private static void click(Screen screen, String text) {
    for (var child : screen.children())
      if (child instanceof Button button
          && button.visible
          && button.getMessage().getString().equals(text)) {
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
