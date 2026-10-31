package dev.izzy.factorycore.clienttesting;

import com.mojang.blaze3d.platform.InputConstants;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.FactoryCoreKeys;
import dev.izzy.factorycore.platform.client.GatewayPolicyScreen;
import dev.izzy.factorycore.platform.client.PortScreen;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.client.ui.ToggleButton;
import dev.izzy.factorycore.platform.guide.GuideContent;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.storage.BufferSettingsPackets;
import dev.izzy.factorycore.platform.storage.PortContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
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
  private final Captures captures = new Captures("terminal", report, evidence);
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
  private int searchStep, scrollStep;
  private final ProgramAuthoringClientVerification programAuthoring =
      new ProgramAuthoringClientVerification();
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
    if (stage == 0 && client.screen instanceof AccessibilityOnboardingScreen onboarding) {
      onboarding.onClose();
      return;
    }
    if (stage == 0 && client.screen instanceof TitleScreen) {
      var window = client.getWindow();
      double ratio = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
      window.setWindowed((int) Math.round(1708 / ratio), (int) Math.round(960 / ratio));
      client.options.guiScale().set(2);
      client.options.pauseOnLostFocus = false;
      client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
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
                    for (int i = 0; i < 100; i++) {
                      var scrollItem = new ItemStack(Items.STONE);
                      scrollItem.set(
                          DataComponents.CUSTOM_NAME,
                          Component.literal(String.format(Locale.ROOT, "Scroll %03d", i)));
                      check(
                          saved.registry().insert(cell, codec.itemKey(scrollItem), 1).amount() == 1,
                          "Scroll seed admission");
                    }
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
    if (stage == 3 && searchStep == 6 && client.screen == null && ticks - entered > 4) {
      client.gameMode.useItemOn(
          client.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.NORTH, TERMINAL, false));
      searchStep = 7;
      entered = ticks;
      return;
    }
    if (!(client.screen instanceof TerminalScreen screen)) return;
    var menu = screen.getMenu();
    var view = menu.view();
    if (stage == 3) {
      searchTick(client, screen);
      return;
    }
    if (stage == 4
        && view.revision() > previousRevision
        && view.status() == TerminalPackets.Status.READY
        && view.rows().size() == 2) {
      int selected = -1;
      for (int i = 0; i < view.rows().size(); i++)
        if (view.rows().get(i).name().equals("Client hopper variant")) selected = i;
      check(selected >= 0, "Actual hopper components appear separately from plain iron");
      for (var child : screen.children()) if (child instanceof EditBox edit) edit.setFocused(false);
      screen.keyPressed(GLFW.GLFW_KEY_1 + selected, 0, 0);
      screen.mouseClicked(screen.itemX(selected) + 9, screen.itemY(selected) + 9, 0);
      next();
      return;
    }
    if (stage == 5 && menu.getCarried().getCount() == 23 && !view.busy()) {
      check(
          ItemStack.isSameItemSameComponents(menu.getCarried(), named(1)),
          "Native left-click withdraws exact components onto the cursor");
      screen.mouseClicked(screen.itemX(0) + 9, screen.itemY(0) + 9, 0);
      next();
      return;
    }
    if (stage == 6 && menu.getCarried().isEmpty() && !view.busy()) {
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
      captures.grab(client, "scale-3-iron.png");
      client.options.guiScale().set(4);
      client.resizeDisplay();
      next();
      return;
    }
    if (stage == 8 && ticks - entered > 10) {
      captures.grab(client, "scale-4-iron.png");
      search(screen, "");
      next();
      return;
    }
    if (stage == 9 && view.rows().size() == view.size() && view.next() && !view.busy()) {
      previousRevision = view.revision();
      screen.mouseScrolled(screen.itemX(0) + 9, screen.itemY(0) + 9, 0, -1);
      next();
      return;
    }
    if (stage == 10
        && ticks - entered >= 4
        && view.revision() > previousRevision
        && view.offset() > 0) {
      if (scrollStep == 0) {
        captures.grab(client, "scale-4-next-page.png");
        double x = screen.itemX(0) + 18 * screen.columns() + 5;
        double y = screen.itemY(0) + screen.rows() * 18 - 2;
        screen.mouseClicked(x, screen.itemY(0) + 2, 0);
        screen.mouseDragged(x, y, 0, 0, y - screen.itemY(0));
        screen.mouseReleased(x, y, 0);
        previousRevision = view.revision();
        scrollStep++;
        return;
      }
      int lastRow =
          Math.max(
              0,
              ((view.total() + screen.columns() - 1) / screen.columns() - screen.rows())
                  * screen.columns());
      if (view.offset() != lastRow) return;
      captures.grab(client, "terminal-scrollbar-scale-4.png");
      for (int i = 0; i < 20; i++)
        screen.mouseScrolled(screen.itemX(0) + 9, screen.itemY(0) + 9, 0, 1);
      next();
      return;
    }
    if (stage == 11
        && craftStep < 8
        && (craftStep > 0
            || view.offset() == 0 && view.rows().size() == view.size() && view.next())) {
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

  /** Real typed queries, native sort button, and a physically reopened terminal. */
  private void searchTick(Minecraft client, TerminalScreen screen) {
    var view = screen.getMenu().view();
    if (view.status() != TerminalPackets.Status.READY || view.busy() || ticks - entered < 5) return;
    switch (searchStep) {
      case 0 -> {
        if (view.rows().size() != Math.min(view.size(), 110) || view.total() != 110) return;
        if (view.size() != screen.columns() * screen.rows()) return;
        check(
            view.size() == screen.columns() * screen.rows(),
            "Grid requests its full adaptive viewport");
        check(
            view.sort() == TerminalSearchSupport.NAME
                && view.rows().getFirst().name().equals("Client hopper variant"),
            "Name sorting uses display names rather than registry order");
        checkNoOverlap(screen);
        captures.grab(client, "scale-2-all-items.png");
        search(screen, "hopper variant");
      }
      case 1 -> {
        if (!view.search().equals("hopper variant") || view.rows().size() != 1) return;
        check(view.rows().getFirst().total().equals("23"), "Display-only query finds named stock");
        captures.grab(client, "terminal-search-display-name.png");
        search(screen, "@minecraft");
      }
      case 2 -> {
        if (!view.search().equals("@minecraft")
            || view.rows().size() != Math.min(view.size(), 110)
            || view.total() != 110) return;
        captures.grab(client, "terminal-search-mod.png");
        search(screen, "#minecraft:coals");
      }
      case 3 -> {
        if (!view.search().equals("#minecraft:coals") || view.rows().size() != 1) return;
        check(view.rows().getFirst().icon().equals("minecraft:coal"), "Loaded tag finds only coal");
        captures.grab(client, "terminal-search-tag.png");
        // Q61: a query using a tag, quoted text, OR, NOT and a count; steps 20 and 21 then
        // return to step 4.
        search(screen, SYNTAX_QUERY);
        searchStep = 19;
      }
      case 20 -> {
        if (!view.search().equals(SYNTAX_QUERY) || view.rows().size() != 1) return;
        check(
            view.rows().getFirst().name().equals("Client hopper variant"),
            "Only the named stack of 23 passes >5; coal (3) is filtered by count");
        captures.grab(client, "terminal-search-syntax.png");
        search(screen, "iron (coal");
      }
      case 21 -> {
        if (!view.search().equals("iron (coal") || !view.rows().isEmpty()) return;
        captures.grab(client, "terminal-search-invalid.png");
        evidence.append(
            "terminal-search: Q61 query "
                + SYNTAX_QUERY
                + " finds only the named stack of 23; malformed \"iron (coal\" shows no rows,"
                + " marked red\n");
        search(screen, "@minecraft");
        searchStep = 3;
      }
      case 4 -> {
        if (!view.search().equals("@minecraft")
            || view.rows().size() != Math.min(view.size(), 110)
            || view.total() != 110) return;
        click(screen, "Name");
      }
      case 5 -> {
        if (view.sort() != TerminalSearchSupport.QUANTITY) return;
        check(view.rows().getFirst().total().equals("23"), "Exact quantity sort puts23 ahead of3");
        captures.grab(client, "terminal-sort-quantity.png");
        // Q63: hold the order (as Shift does) while the top entry drops to 2 on the server.
        screen.holdOrder(true);
        screen.shownEntries();
        changeHopperVariant(client, -21);
        searchStep = 21;
      }
      case 22 -> {
        if (view.rows().getFirst().total().equals("23")
            || view.rows().stream().noneMatch(row -> row.total().equals("2"))) return;
        var shown = screen.shownEntries();
        check(
            shown.getFirst().name().equals("Client hopper variant")
                && shown.getFirst().total().equals("2")
                && !view.rows().getFirst().name().equals("Client hopper variant"),
            "Held order keeps the entry in place with its new count while the server re-sorts");
        captures.grab(client, "terminal-sort-frozen.png");
        evidence.append(
            "terminal-sort: held order kept Client hopper variant first at 2 while Qty order moved"
                + " it\n");
        screen.holdOrder(false);
        changeHopperVariant(client, 21);
      }
      case 23 -> {
        if (!view.rows().getFirst().total().equals("23")) return;
        client.player.closeContainer();
        searchStep = 5;
      }
      case 7 -> {
        if (!view.search().equals("@minecraft") || view.sort() != TerminalSearchSupport.QUANTITY)
          return;
        check(view.rows().getFirst().total().equals("23"), "Reopen restores player's sorted query");
        captures.grab(client, "terminal-search-reopened.png");
        // Q67: save this search and sort as view 1 from the ★ row, then capture the row.
        screen.viewsOpen(true);
        screen.clickView(0, false, false);
        searchStep = 29;
      }
      case 30 -> {
        if (view.saved().isEmpty() || !view.saved().getFirst().set()) return;
        check(
            view.saved().getFirst().search().equals("@minecraft")
                && view.saved().getFirst().sort() == TerminalSearchSupport.QUANTITY,
            "An empty saved view takes the current search and sort");
        scaleBeforeViews = client.options.guiScale().get();
        client.options.guiScale().set(4);
        client.resizeDisplay();
      }
      case 31 -> {
        captures.grab(client, "terminal-saved-views-scale-4.png");
        client.options.guiScale().set(1);
        client.resizeDisplay();
      }
      case 32 -> {
        captures.grab(client, "terminal-saved-views-scale-1.png");
        client.options.guiScale().set(scaleBeforeViews);
        client.resizeDisplay();
        screen.clickView(0, true, false);
      }
      case 33 -> {
        if (view.saved().getFirst().set()) return;
        evidence.append(
            "terminal-saved-views: view 1 saved @minecraft / Qty from the ★ row, captured at"
                + " scales 4 and 1, then emptied with right-click\n");
        screen.viewsOpen(false);
      }
      case 34 -> {
        // The sort tools return on the screen tick after the row closes.
        click(screen, "Qty");
        searchStep = 7;
      }
      case 8 -> {
        // The sort button cycles Name → Qty → Mod → ID → New → Name (Q62 added ID and New).
        if (view.sort() != TerminalSearchSupport.MOD) return;
        click(screen, "Mod");
      }
      case 9 -> {
        // Each click sends the mode after the one shown, so a repeat before the reply is harmless.
        if (view.sort() == TerminalSearchSupport.ID) click(screen, "ID");
        else if (view.sort() == TerminalSearchSupport.RECENT) click(screen, "New");
        if (view.sort() != TerminalSearchSupport.NAME) return;
        search(screen, "");
      }
      case 10 -> {
        if (!view.search().isEmpty()
            || view.rows().size() != Math.min(view.size(), 110)
            || view.total() != 110) return;
        evidence.append(
            "terminal-search: display name, registry, @mod, #loaded tag; name/quantity sort; physical reopen preserves accepted query/sort\n");
        previousRevision = view.revision();
        search(screen, "iron");
        next();
        return;
      }
      default -> {
        return;
      }
    }
    searchStep++;
    entered = ticks;
  }

  private int scaleBeforeViews;

  private static final String SYNTAX_QUERY =
      "(#minecraft:coals | \"hopper variant\") !@factorycore >5";

  /** Q63: adds (or with a negative amount, removes) named iron in the cell, on the server. */
  private void changeHopperVariant(Minecraft client, int amount) {
    var server = client.getSingleplayerServer();
    server.execute(
        () -> {
          var registry = DeviceSavedData.get(server).registry();
          var key = new PlatformResourceCodec(server.registryAccess()).itemKey(named(1));
          if (amount > 0) registry.insert(cell, key, amount);
          else registry.extract(cell, key, -amount, null);
        });
  }

  private int plankSlot = -1;

  /** Manual grid: cell click, own-slot ghost, Row→, right-click clear, Craft and two scales. */
  private void craftTick(Minecraft client, TerminalScreen screen) {
    var menu = screen.getMenu();
    var grid = menu.gridView();
    var server = client.getSingleplayerServer();
    var principal = client.player.getUUID();
    double left = screen.getGuiLeft(), top = screen.getGuiTop();
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
        if (!screen.craftingOpen()) {
          click(screen, "Crafting »");
          return;
        }
        if (!client.player.getInventory().getItem(20).is(Items.OAK_LOG)) return;
        screen.mouseClicked(left + screen.gridX() + 9, top + screen.gridY() + 9, 0);
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
        // Opening the panel narrows the browser; wait for the resized view before using a row.
        if (!grid.result().equals("minecraft:oak_planks") || !screen.windowSettled()) return;
        check(grid.resultCount() == 4, "Actual server recipe preview for one log");
        checkNoOverlap(screen);
        captures.grab(client, "terminal-grid-scale-4.png");
        screen.mouseClicked(left + screen.gridX() + 27, top + screen.gridY() + 9, 0);
        for (var child : screen.children())
          if (child instanceof EditBox edit) edit.setFocused(false);
        screen.keyPressed(GLFW.GLFW_KEY_1, 0, 0);
        click(screen, "Use selected");
        craftStep++;
      }
      case 3 -> {
        if (grid.cells().get(1).isEmpty() || !grid.result().isEmpty()) return;
        check(
            grid.status() == TerminalCraftingPackets.GridStatus.READY,
            "Row ghost breaks the recipe without moving stock");
        screen.mouseClicked(left + screen.gridX() + 27, top + screen.gridY() + 9, 1);
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
        if (grid.status() != TerminalCraftingPackets.GridStatus.CRAFTED || plankSlot < 0) return;
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
        captures.grab(client, "terminal-grid-scale-2.png");
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
        captures.grab(client, "buffer-scale-2-settings.png");
        // H3a: the buffer mode is a row of choices.
        click(screen, "Export");
        next();
      }
      case 14 -> {
        if (!view.mode().equals("EXPORT")) return;
        click(screen, "Stock");
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
          // B1: typed IDs are behind "Add by ID…" (ghost slots are the default).
          if (rule == 0) click(screen, "Add by ID…");
          edit(screen, 2, rules[rule], false);
          click(screen, rule == 6 ? "Tag" : "Item ID");
          rule++;
          entered = ticks;
          return;
        }
        check(
            view.dirty() && view.filter().equals("INCLUDE"),
            "Native registry/tag edits remain separate draft");
        captures.grab(client, "buffer-scale-2-filter-slots.png");
        next();
      }
      case 18 -> {
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
        pointer(client, screen, 43, 85);
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
        pointer(client, screen, 43, 85);
        next();
      }
      case 23 -> {
        sample(screen);
        next();
      }
      case 24 -> {
        if (view.rules().size() != 1) return;
        check(
            view.rules().getFirst().label().contains("Client hopper variant"),
            "Exact native sample rule includes component name");
        click(screen, "Apply");
        next();
      }
      case 25 -> {
        if (view.dirty()
            || view.status() != BufferSettingsPackets.Status.APPLIED
            || view.slots().getFirst().count() != 8) return;
        click(screen, "Inventory");
        next();
      }
      case 26 -> {
        captures.grab(client, "buffer-scale-2-stock.png");
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
        if (view.status() != BufferSettingsPackets.Status.FULL) return;
        check(
            client.player.getInventory().getItem(9).getCount() == 3
                && view.slots().getFirst().count() == 8,
            "Full Stock goal retains player deposit without extraction");
        captures.grab(client, "buffer-scale-2-full.png");
        // H3a: the mode choices are in the Settings view.
        click(screen, "Settings");
        click(screen, "Paused");
        next();
      }
      case 29 -> {
        if (!view.mode().equals("PAUSED")) return;
        click(screen, "Apply");
        click(screen, "Inventory");
        next();
      }
      case 30 -> {
        if (view.dirty() || view.status() != BufferSettingsPackets.Status.APPLIED) return;
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
            view.status() == BufferSettingsPackets.Status.PARTIAL
                && view.moved() == 8
                && view.requested() == 64,
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
        captures.grab(client, "buffer-scale-3-inventory.png");
        click(screen, "Settings");
        next();
      }
      case 35 -> {
        captures.grab(client, "buffer-scale-3-settings.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        next();
      }
      case 36 -> {
        captures.grab(client, "buffer-scale-4-settings.png");
        click(screen, "Inventory");
        next();
      }
      case 37 -> {
        captures.grab(client, "buffer-scale-4-inventory.png");
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
                // H7: an Exporter on the chest replaces the removed Machine-mode Interface.
                Ports.exporter(level, new BlockPos(2, 65, 2), Direction.SOUTH);
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

  /**
   * H7: an Exporter keeps the chest stocked through its own screen. The server binds it to chest
   * slot 2 under the label "smelter" (the binding the restart check expects); the real screen then
   * gets a filter through its recipe-viewer drop, Keep 8 typed into its box, and Apply.
   */
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
              var port = (MachineInterfaceEntity) player.serverLevel().getBlockEntity(location);
              // The Exporter's filter is an Item rule (any components). Leave only the named
              // variant in the cell, so Stock picks it, as the old exact sample did.
              var registry = DeviceSavedData.get(server).registry();
              var plain =
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                          player.registryAccess())
                      .itemKey(new ItemStack(Items.IRON_INGOT));
              long stray =
                  registry.stock(cell, plain).total() - registry.stock(cell, plain).reserved();
              if (stray > 0) registry.extract(cell, plain, stray, null);
              if (port.canUse(player)
                  && port.applySettings(
                          new dev.izzy.factorycore.core.network.NetworkPermissions.Actor(
                              principal, false),
                          new ConnectorSettings(
                              dev.izzy.factorycore.core.resource.ResourceKind.ITEM,
                              ConnectorSettings.Access.READ_WRITE,
                              0,
                              2,
                              1,
                              8),
                          "smelter")
                      == dev.izzy.factorycore.core.network.NetworkAuthority.Decision.ALLOWED)
                ready.set(false);
            } catch (RuntimeException error) {
              failure.set(error);
            }
          });
      return;
    }
    if (stage == 40
        && !ready.get()
        && client.screen == null
        && client.level.getBlockState(location).is(PortContent.EXPORTER.get())) {
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
              var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(location.south());
              if (chest.getItem(2).getCount() != 8) {
                if (ticks % 40 == 0) {
                  var port = (MachineInterfaceEntity) player.serverLevel().getBlockEntity(location);
                  System.out.println(
                      "FACTORYCORE_EXPORTER chest="
                          + chest.getItem(0)
                          + "/"
                          + chest.getItem(1)
                          + "/"
                          + chest.getItem(2)
                          + " preset="
                          + DeviceSavedData.get(server).machinePresets().get(port.handle().id())
                          + " status="
                          + dev.izzy.factorycore.platform.network.WorldNetworks.get(server)
                              .machinePresets()
                              .status(port));
                }
                return;
              }
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
      captures.grab(client, "exporter-scale-4-stock.png");
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
    if (!(client.screen instanceof PortScreen screen) || !screen.getMenu().presetView().editable())
      return;
    switch (stage) {
      case 41 -> {
        if (screen.getMenu().presetView().rules().isEmpty()) {
          if ((ticks - entered) % 10 == 5)
            screen.addFilterId(
                dev.izzy.factorycore.core.resource.ResourceKind.ITEM, key.registryId());
          return;
        }
        edit(screen, 0, "8", false);
        captures.grab(client, "exporter-scale-2-draft.png");
        client.options.guiScale().set(3);
        client.resizeDisplay();
        next();
      }
      case 42 -> {
        check(
            screen.getMenu().presetView().rules().size() == 1,
            "A recipe-viewer drop adds one filter rule");
        captures.grab(client, "exporter-scale-3-draft.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        click(screen, "Apply");
        checked.set(false);
        jump(59);
      }
      default -> {}
    }
  }

  private void writeReport(Minecraft client) {
    try {
      Files.writeString(
          report,
          "PASS\n"
              + evidence
              + "Tour: gateway policy toggle (actual sneak-use C2S), program editor, Factory Guide pages 1-2 (actual item use), wireless handheld terminal beyond wired reach, terminal Fluids/Energy views; no overlapping controls.\n"
              + "Art: every Factory Core block in a front/top view, connected cable run with a sideways gateway, and every item in a double chest at GUI scales 2 and 4.\n"
              + "Input: terminal live search, cursor pickup/deposit, wheel and scrollbar drag; buffer mode/rate/goal/rules/paging/exact-sample/Apply/Take1/Take64; machine Reset/Cancel/Apply, preset mode/exact sample/goal/Apply and actual Stock, native text and GUI-scale draft retention. Deposits use real C2S payloads; physical Shift remains unverified.\n"
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
   * FE stocked (72–76): terminal Fluids/Energy views. Then the art gallery (90–97): block rows,
   * cable run and item icons. Every screen capture also asserts that no visible controls overlap.
   */
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
                        .setValue(BlockStateProperties.FACING, Direction.WEST),
                    3);
                ((NetworkNodeEntity) level.getBlockEntity(GATEWAY)).commission(null);
                level.setBlock(BRANCH, NetworkContent.CABLE.get().defaultBlockState(), 3);
                level.setBlock(PROGRAM, ProgramContent.BLOCK.get().defaultBlockState(), 3);
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
        if (!(client.screen instanceof GatewayPolicyScreen screen) || !screen.getMenu().editable())
          return;
        shift(client, false);
        // U8: the screen opens on its presets; Custom reveals the toggles (next tick).
        if (screen.children().stream()
            .anyMatch(
                child ->
                    child instanceof Button button
                        && button.visible
                        && button.active
                        && button.getMessage().getString().equals("Custom"))) {
          click(screen, "Custom");
          return;
        }
        policyMask = screen.getMenu().policy().mask();
        // The first visible "View" toggle is the subnetwork's own View grant (bit 0).
        var first =
            screen.children().stream()
                .filter(child -> child instanceof ToggleButton toggle && toggle.visible)
                .map(child -> (ToggleButton) child)
                .filter(toggle -> toggle.getMessage().getString().equals("View"))
                .findFirst()
                .orElseThrow();
        screen.mouseClicked(
            first.getX() + first.getWidth() / 2.0, first.getY() + first.getHeight() / 2.0, 0);
        next();
      }
      case 64 -> {
        if (!(client.screen instanceof GatewayPolicyScreen screen)
            || screen.getMenu().policy().mask() == policyMask) return;
        check(
            (screen.getMenu().policy().mask() ^ policyMask) == 1,
            "Actual policy click flips exactly the first branch permission");
        for (var header : new String[] {"branch", "inherited", "kinds"})
          check(
              client
                      .font
                      .split(
                          Component.translatable("gui.factorycore.gateway_policy." + header),
                          GatewayPolicyScreen.columnWidth())
                      .size()
                  <= GatewayPolicyScreen.HEADER_LINES,
              "Policy column header fits its lines: " + header);
        checkNoOverlap(screen);
        captures.grab(client, "gateway-policy-scale-2.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        next();
      }
      case 65 -> {
        if (ticks - entered < 10) return;
        checkNoOverlap(client.screen);
        captures.grab(client, "gateway-policy-scale-4.png");
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
        if (!(client.screen instanceof ProgramScreen screen) || ticks - entered < 10) return;
        checkNoOverlap(screen);
        if (!programAuthoring.tick(client, screen, ticks, name -> captures.grab(client, name)))
          return;
        checkNoOverlap(screen);
        captures.grab(client, "program-editor-scale-2.png");
        evidence.append(
            "program-authoring: typed source, atomic undo/redo, native item/local-label selection, cursor completion, highlighted GUI scales2/4 and read-only resolved rule preview\n");
        client.player.closeContainer();
        server.execute(
            () ->
                server
                    .getPlayerList()
                    .getPlayer(principal)
                    .getInventory()
                    .setItem(0, new ItemStack(GuideContent.GUIDE.get())));
        next();
      }
      case 68 -> {
        if (client.screen != null
            || !client.player.getInventory().getItem(0).is(GuideContent.GUIDE.get())) return;
        client.player.getInventory().selected = 0;
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next();
      }
      case 69 -> {
        if (!(client.screen instanceof BookViewScreen screen)) return;
        captures.grab(client, "guide-page-1.png");
        screen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0);
        next();
      }
      case 70 -> {
        captures.grab(client, "guide-page-2.png");
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
                    ACCESS_POINT, WirelessContent.ACCESS_POINT.get().defaultBlockState(), 3);
                level.setBlock(new BlockPos(20, 64, 4), Blocks.STONE.defaultBlockState(), 3);
                var handheld = new ItemStack(WirelessContent.HANDHELD.get());
                handheld.set(
                    WirelessContent.BINDING.get(),
                    new WirelessContent.Binding(
                        level.dimension().location().toString(), ACCESS_POINT.asLong()));
                handheld.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1_000, false);
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
            || !client.player.getInventory().getItem(0).is(WirelessContent.HANDHELD.get())) return;
        client.player.getInventory().selected = 0;
        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        next();
      }
      case 82 -> {
        powerAccessPoint(server);
        if (!(client.screen instanceof TerminalScreen screen)) return;
        var view = screen.getMenu().view();
        if (view.status() != TerminalPackets.Status.READY
            || view.rows().isEmpty()
            || ticks - entered < 10) return;
        checkNoOverlap(screen);
        captures.grab(client, "wireless-terminal-scale-2.png");
        evidence.append(
            "wireless: bound handheld opened the terminal "
                + Math.round(Math.sqrt(client.player.distanceToSqr(Vec3.atCenterOf(ACCESS_POINT))))
                + " blocks from the access point; rows="
                + view.rows().size()
                + "\n");
        client.player.closeContainer();
        // Q14: move the handheld out of the hand so only the open key can reach it.
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(principal);
              player.getInventory().setItem(20, player.getInventory().removeItemNoUpdate(0));
            });
        jump(84);
      }
      case 84 -> {
        powerAccessPoint(server);
        if (client.screen != null
            || !client.player.getInventory().getItem(0).isEmpty()
            || !client.player.getInventory().getItem(20).is(WirelessContent.HANDHELD.get())) return;
        FactoryCoreKeys.OPEN_WIRELESS.setKey(
            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_O));
        KeyMapping.resetMapping();
        KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_O));
        next();
      }
      case 85 -> {
        powerAccessPoint(server);
        if (!(client.screen instanceof TerminalScreen screen)
            || screen.getMenu().view().status() != TerminalPackets.Status.READY) return;
        screen.keyPressed(GLFW.GLFW_KEY_O, 0, 0);
        evidence.append(
            "wireless: the open key opened the terminal from inventory slot 20 (hand empty)"
                + " and closed it again\n");
        next();
      }
      case 86 -> {
        if (client.screen != null) return;
        FactoryCoreKeys.OPEN_WIRELESS.setKey(InputConstants.UNKNOWN);
        KeyMapping.resetMapping();
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(principal);
              player.getInventory().setItem(20, ItemStack.EMPTY);
              player.teleportTo(4.5, 65, 4.5);
              player.setYRot(135);
              player.setXRot(10);
            });
        jump(83);
      }
      case 83 -> {
        if (client.screen != null
            || !client.player.getInventory().getItem(20).isEmpty()
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
            || screen.getMenu().view().status() != TerminalPackets.Status.READY) return;
        previousRevision = screen.getMenu().view().revision();
        click(screen, "Fluids");
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
        captures.grab(client, "terminal-" + name.toLowerCase(Locale.ROOT) + "-scale-2.png");
        evidence.append(
            "terminal-"
                + name
                + ": "
                + view.rows().stream().map(row -> row.name() + "=" + row.total()).toList()
                + "\n");
        previousRevision = view.revision();
        if (stage == 74) click(screen, "Energy");
        next();
      }
      case 76 -> {
        client.player.closeContainer();
        jump(90);
      }
      case 90 -> {
        if (client.screen != null) return;
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                int placed = ArtGallery.build(player.serverLevel());
                evidence.append("art-gallery: placed ").append(placed).append(" blocks\n");
                ArtGallery.teleport(player, ArtGallery.FRONT);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        client.options.hideGui = true;
        next();
      }
      case 91, 92, 93 -> {
        var view =
            stage == 91 ? ArtGallery.FRONT : stage == 92 ? ArtGallery.TOP : ArtGallery.CABLES;
        if (!aim(client, view) || ticks - entered < 80) return;
        captures.grab(
            client,
            stage == 91
                ? "art-blocks-front.png"
                : stage == 92 ? "art-blocks-top.png" : "art-cables.png");
        var following =
            stage == 91 ? ArtGallery.TOP : stage == 92 ? ArtGallery.CABLES : ArtGallery.CHEST_VIEW;
        boolean chest = stage == 93;
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(principal);
                if (chest)
                  evidence
                      .append("art-items: chest holds ")
                      .append(ArtGallery.fillChest(player.serverLevel()))
                      .append(" Factory Core items\n");
                ArtGallery.teleport(player, following);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        if (chest) client.options.hideGui = false;
        next();
      }
      case 94 -> {
        if (!aim(client, ArtGallery.CHEST_VIEW)
            || ticks - entered < 20
            || !client.level.getBlockState(ArtGallery.CHEST).is(Blocks.CHEST)) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(
                Vec3.atCenterOf(ArtGallery.CHEST), Direction.NORTH, ArtGallery.CHEST, false));
        next();
      }
      case 95 -> {
        if (!(client.screen instanceof ContainerScreen screen)
            || screen.getMenu().getItems().stream().filter(stack -> !stack.isEmpty()).count() < 32
            || ticks - entered < 10) return;
        captures.grab(client, "art-items-scale-2.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
        next();
      }
      case 96 -> {
        if (ticks - entered < 10) return;
        captures.grab(client, "art-items-scale-4.png");
        client.player.closeContainer();
        client.options.guiScale().set(2);
        client.resizeDisplay();
        // Return to the functional fixture so the separate restart run reopens there.
        server.execute(
            () -> {
              var player = server.getPlayerList().getPlayer(principal);
              player.getAbilities().flying = false;
              player.onUpdateAbilities();
              player.teleportTo(player.serverLevel(), 4.5, 65, 4.5, 135, 10);
            });
        next();
      }
      case 97 -> {
        if (client.screen != null || client.player.distanceToSqr(4.5, 65, 4.5) > 1) return;
        checked.set(false);
        searchStep = 0;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.NORTH, TERMINAL, false));
        next();
      }
      case 98 -> {
        if (checked.get()) {
          writeReport(client);
          return;
        }
        if (searchStep == 3 || !(client.screen instanceof TerminalScreen screen)) return;
        var view = screen.getMenu().view();
        if (view.status() != TerminalPackets.Status.READY || view.busy()) return;
        if (searchStep == 0) {
          search(screen, "@minecraft");
          searchStep++;
        } else if (searchStep == 1 && view.search().equals("@minecraft")) {
          click(screen, "Name");
          searchStep++;
        } else if (searchStep == 2 && view.sort() == TerminalSearchSupport.QUANTITY) {
          captures.grab(client, "terminal-preferences-before-restart.png");
          client.player.closeContainer();
          searchStep++;
          server.execute(
              () -> {
                try {
                  RestartCheckpoint.recordTerminalPreferences(
                      server.getPlayerList().getPlayer(principal),
                      report.getParent().resolve("restart.properties"));
                  checked.set(true);
                } catch (IOException | RuntimeException error) {
                  failure.set(error);
                }
              });
        }
      }
      default -> throw new IllegalStateException("Unknown tour stage " + stage);
    }
  }

  /** Holds the camera on an art view once the server teleport has arrived; false until then. */
  private static boolean aim(Minecraft client, ArtGallery.View view) {
    var player = client.player;
    if (player.distanceToSqr(view.x(), view.y(), view.z()) > 1) return false;
    player.getAbilities().flying = true;
    player.setDeltaMovement(Vec3.ZERO);
    player.setYRot(view.yaw());
    player.setXRot(view.pitch());
    player.yRotO = view.yaw();
    player.xRotO = view.pitch();
    player.setYHeadRot(view.yaw());
    return true;
  }

  /** Keeps the fixture access point above its 16 FE/tick upkeep while the handheld is in use. */
  private static void powerAccessPoint(MinecraftServer server) {
    server.execute(
        () -> {
          if (server.overworld().getBlockEntity(ACCESS_POINT) instanceof AccessPointEntity point)
            point.energyStorage().receiveEnergy(1_000, false);
        });
  }

  /** Sends the actual sneak state the server reads for gateway sneak-use. */
  private static void shift(Minecraft client, boolean down) {
    client.player.connection.send(
        new ServerboundPlayerCommandPacket(
            client.player,
            down
                ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
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

  private static void pointer(Minecraft client, BufferSettingsScreen screen, int x, int y) {
    var window = client.getWindow();
    double guiX = screen.getGuiLeft() + x, guiY = screen.getGuiTop() + y;
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
    screen.mouseClicked(screen.getGuiLeft() + 43, screen.getGuiTop() + 85, 0);
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
    level.setBlock(BUFFER, InterfaceContent.state(InterfaceKind.BUFFER, Direction.NORTH), 3);
    var buffer = (BufferedInterfaceEntity) level.getBlockEntity(BUFFER);
    fixtureBuffer = buffer;
    buffer.place(new ItemStack(InterfaceContent.ITEM.get()), player.getUUID());
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
        return;
      }
    throw new IllegalStateException("Search widget missing");
  }

  /** Visible controls must not overlap, and button labels must fit, at the current GUI scale. */
  private static void checkNoOverlap(Screen screen) {
    var widgets =
        screen.children().stream()
            .filter(child -> child instanceof AbstractWidget widget && widget.visible)
            .map(child -> (AbstractWidget) child)
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
}
