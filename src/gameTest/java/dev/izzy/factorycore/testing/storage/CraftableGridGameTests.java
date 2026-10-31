package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.TerminalSearchSupport;
import dev.izzy.factorycore.platform.storage.WorldStorageSearch;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestMenus;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A3: the Items grid marks craftable rows and, with "Show craftable", lists craftables without
 * stock as zero-count rows that can be requested. Patterns in a branch count only where the gateway
 * publishes CRAFT (ADR 0033).
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CraftableGridGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      GATEWAY = new BlockPos(2, 1, 1),
      CHILD_UNIT = new BlockPos(3, 1, 1),
      TERMINAL = new BlockPos(0, 1, 1);

  /** Ticks between commands so the session's command rate gate admits each one. */
  private static final int GAP = 5;

  private record Fixture(
      ServerPlayer player,
      TerminalEntity terminal,
      NetworkNodeEntity gate,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ResourceKey log) {}

  private CraftableGridGameTests() {}

  private static ItemStack pattern(int[] cells, Item input, Item output, int count) {
    var stacks = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int cell : cells) stacks.set(cell, new ItemStack(input));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, stacks, new ItemStack(output, count), List.of()));
    return stack;
  }

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gate = h.getBlockEntity(GATEWAY);
    gate.commission(null);
    h.setBlock(CHILD_UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(CHILD_UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(
        player,
        h.getBlockEntity(TERMINAL),
        gate,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.OAK_LOG)));
  }

  private static TerminalMenu menu(Fixture f) {
    return TestMenus.terminal(f.player);
  }

  private static void open(GameTestHelper h, Fixture f) {
    h.useBlock(TERMINAL, f.player);
    h.assertTrue(f.player.containerMenu instanceof TerminalMenu, "Terminal opens");
    menu(f).broadcastChanges();
  }

  private static void command(Fixture f, int action, int selection, int amount) {
    var m = menu(f);
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            action,
            selection,
            amount,
            "",
            new byte[32]));
    m.broadcastChanges();
  }

  private static TerminalPackets.Row row(Fixture f, String id) {
    for (var row : menu(f).view().rows()) if (row.icon().equals(id)) return row;
    return null;
  }

  private static int index(Fixture f, String id) {
    var rows = menu(f).view().rows();
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).icon().equals(id)) return i;
    return -1;
  }

  /** Waits until the grid shows {@code size} rows and the search is idle. */
  private static void shows(GameTestHelper h, Fixture f, int size) {
    menu(f).broadcastChanges();
    var view = menu(f).view();
    h.assertTrue(
        view.status() == TerminalPackets.Status.READY && !view.busy() && view.total() == size,
        "Grid shows " + size + " rows: " + view.rows());
  }

  private static void end(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Craftable grid fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void publishedCraftablesAreMarkedListedAndRequested(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Terminal ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 2);
              open(h, f);
            })
        .thenWaitUntil(() -> shows(h, f, 1))
        .thenExecute(
            () -> {
              h.assertTrue(
                  !row(f, "minecraft:oak_log").craftable() && !menu(f).view().craftables(),
                  "Logs have no pattern; the toggle starts off");
              command(f, TerminalPackets.CRAFTABLES, 1, 1);
            })
        .thenWaitUntil(() -> shows(h, f, 3))
        .thenExecute(
            () -> {
              var stick = row(f, "minecraft:stick");
              var planks = row(f, "minecraft:oak_planks");
              h.assertTrue(
                  menu(f).view().craftables()
                      && stick != null
                      && stick.craftable()
                      && stick.total().equals("0")
                      && planks != null
                      && planks.craftable()
                      && planks.total().equals("0"),
                  "Published branch craftables appear as zero-count rows: "
                      + menu(f).view().rows());
              command(f, TerminalPackets.REQUEST, index(f, "minecraft:stick"), 1);
              var jobs = menu(f).jobView();
              h.assertTrue(
                  jobs.craftables().stream().anyMatch(e -> e.icon().equals("minecraft:stick")),
                  "A request scrolls the Jobs list to the item: " + jobs.craftables());
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              // A request for a row that is not craftable selects nothing.
              long before = menu(f).jobView().revision();
              command(f, TerminalPackets.REQUEST, index(f, "minecraft:oak_log"), 1);
              h.assertTrue(
                  menu(f).jobView().revision() == before, "A non-craftable row is not requested");
              f.player.closeContainer();
              open(h, f);
            })
        .thenWaitUntil(() -> shows(h, f, 3))
        .thenExecute(
            () -> {
              h.assertTrue(menu(f).view().craftables(), "Show craftable is remembered per player");
              command(f, TerminalPackets.CRAFTABLES, 0, 1);
            })
        .thenWaitUntil(() -> shows(h, f, 1))
        .thenExecute(
            () -> {
              h.assertTrue(
                  row(f, "minecraft:stick") == null, "Turning the toggle off hides zero rows");
              end(f);
            })
        .thenSucceed();
  }

  private static void requestById(Fixture f, String id) {
    var m = menu(f);
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            TerminalPackets.REQUEST,
            0,
            1,
            id,
            new byte[32]));
    m.broadcastChanges();
  }

  /** A5: a recipe viewer names a plain item ID; only an item a visible pattern makes is opened. */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void recipeViewerRequestsOnlyCraftableItems(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Terminal ready"))
        .thenExecute(() -> open(h, f))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              h.assertTrue(menu(f).jobView().total() == 2, "Two craftables listed");
            })
        .thenExecute(
            () -> {
              long before = menu(f).jobView().revision();
              requestById(f, "minecraft:diamond");
              h.assertTrue(
                  menu(f).jobView().revision() == before, "An item nothing makes is ignored");
              requestById(f, "not a valid id");
              h.assertTrue(menu(f).jobView().revision() == before, "A malformed ID is ignored");
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              requestById(f, "minecraft:stick");
              h.assertTrue(
                  menu(f).jobView().craftables().stream()
                      .anyMatch(e -> e.icon().equals("minecraft:stick")),
                  "A craftable item ID scrolls Jobs to it");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void gatewayWithoutCraftPublishesNoCraftables(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Terminal ready"))
        .thenExecute(
            () -> {
              var branch = EnumSet.allOf(NetworkPermissions.Permission.class);
              branch.remove(NetworkPermissions.Permission.CRAFT);
              h.assertTrue(
                  f.saved
                          .nodes()
                          .setPolicy(
                              f.gate.handle(),
                              new GatewayPolicy(
                                  branch,
                                  EnumSet.allOf(NetworkPermissions.Permission.class),
                                  EnumSet.allOf(ResourceKind.class)))
                      == NodeRegistry.Failure.OK,
                  "Policy updated");
              WorldNetworks.changed(h.getLevel(), h.absolutePos(GATEWAY));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.terminal.canUse(f.player) && f.saved.registry().stock(f.cell, f.log).loaded(),
                    "Rebuilt network and cell online"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().insert(f.cell, f.log, 2).amount() == 2, "Logs stored");
              open(h, f);
            })
        .thenWaitUntil(() -> shows(h, f, 1))
        .thenExecute(() -> command(f, TerminalPackets.CRAFTABLES, 1, 1))
        .thenIdle(GAP)
        .thenWaitUntil(() -> shows(h, f, 1))
        .thenExecute(
            () -> {
              h.assertTrue(
                  menu(f).view().craftables()
                      && menu(f).view().rows().stream().noneMatch(TerminalPackets.Row::craftable),
                  "Without branch CRAFT nothing is craftable here: " + menu(f).view().rows());
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void craftableRowsPageWithinTheWindowBound(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Terminal ready"))
        .thenExecute(
            () -> {
              var tooMany = new HashSet<ResourceKey>();
              for (int i = 0; i <= WorldStorageSearch.MAX_CRAFTABLE; i++)
                tooMany.add(
                    new ResourceKey(ResourceKind.ITEM, "factorycore_tests:x" + i, new byte[0]));
              boolean refused = false;
              try {
                WorldNetworks.get(h.getLevel().getServer())
                    .transfers()
                    .search()
                    .start(
                        UUID.randomUUID(),
                        new NetworkPermissions.Actor(f.player.getUUID(), false),
                        dev.izzy.factorycore.platform.Positions.of(
                            h.getLevel(), h.absolutePos(TERMINAL)),
                        ResourceKind.ITEM,
                        "",
                        0,
                        tooMany,
                        true);
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(refused, "More than MAX_CRAFTABLE keys are refused");
              f.saved.registry().insert(f.cell, f.log, 2);
              open(h, f);
              command(f, TerminalPackets.CRAFTABLES, 1, 1);
            })
        .thenWaitUntil(() -> shows(h, f, 3))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.WINDOW, 2, 1))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              var view = menu(f).view();
              h.assertTrue(
                  view.offset() == 2 && view.rows().size() == 1 && view.total() == 3,
                  "A one-row window at offset 2 shows the last row: " + view);
            })
        .thenExecute(
            () -> {
              var last = menu(f).view().rows().getFirst();
              h.assertTrue(
                  last.icon().equals("minecraft:stick") && last.craftable(),
                  "Name order puts Stick last, still flagged: " + last);
              end(f);
            })
        .thenSucceed();
  }

  private static List<String> icons(Fixture f) {
    return menu(f).view().rows().stream().map(TerminalPackets.Row::icon).toList();
  }

  /** Waits until the grid shows exactly {@code ids}, in order. */
  private static void order(GameTestHelper h, Fixture f, String... ids) {
    shows(h, f, ids.length);
    h.assertTrue(icons(f).equals(List.of(ids)), "Grid order: " + icons(f));
  }

  /**
   * Q62: the Recent and ID sorts, the Stored / All / Craftable view, the view saved per player, and
   * an old saved "Craftable" flag read as the All view.
   */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void recentAndIdSortsAndTheThreeViews(GameTestHelper h) {
    var f = setup(h);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    var zeta = new ItemStack(Items.DIRT);
    zeta.set(DataComponents.CUSTOM_NAME, Component.literal("Zeta"));
    var dirt = codec.itemKey(zeta);
    String log = "minecraft:oak_log", plank = "minecraft:oak_planks", soil = "minecraft:dirt";
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Terminal ready"))
        .thenExecute(
            () -> {
              // Changed in the order planks, Zeta, logs (the extract is the last change).
              f.saved.registry().insert(f.cell, f.log, 2);
              f.saved.registry().insert(f.cell, planks, 3);
              f.saved.registry().insert(f.cell, dirt, 1);
              f.saved.registry().extract(f.cell, f.log, 1, null);
              open(h, f);
            })
        .thenWaitUntil(() -> order(h, f, log, plank, soil))
        .thenExecute(() -> command(f, TerminalPackets.SORT, TerminalSearchSupport.RECENT, 1))
        .thenWaitUntil(() -> order(h, f, log, soil, plank))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.SORT, TerminalSearchSupport.ID, 1))
        // By name Zeta is last; by ID minecraft:dirt is first.
        .thenWaitUntil(() -> order(h, f, soil, log, plank))
        .thenIdle(GAP)
        .thenExecute(
            () -> command(f, TerminalPackets.CRAFTABLES, TerminalPackets.VIEW_CRAFTABLE, 1))
        .thenWaitUntil(() -> order(h, f, plank, "minecraft:stick"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  row(f, plank).total().equals("3")
                      && row(f, "minecraft:stick").total().equals("0"),
                  "Craftable shows stocked and unstocked craftables only");
              f.player.closeContainer();
              open(h, f);
            })
        .thenWaitUntil(() -> order(h, f, plank, "minecraft:stick"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  menu(f).view().view() == TerminalPackets.VIEW_CRAFTABLE
                      && TerminalSearchSupport.mode(menu(f).view().sort())
                          == TerminalSearchSupport.ID,
                  "The view and the ID sort are remembered per player");
              f.player.closeContainer();
              // A preference saved before Q62 has only the Craftable flag.
              var saved =
                  f.player
                      .getPersistentData()
                      .getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                      .getCompound("FactoryCoreTerminal");
              saved.remove("View");
              saved.putBoolean("Craftable", true);
              open(h, f);
            })
        .thenWaitUntil(() -> order(h, f, soil, log, plank, "minecraft:stick"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  menu(f).view().view() == TerminalPackets.VIEW_ALL,
                  "An old Craftable flag reads as the All view");
              end(f);
            })
        .thenSucceed();
  }

  private static void search(Fixture f, String text) {
    var m = menu(f);
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            TerminalPackets.SEARCH,
            0,
            1,
            text,
            new byte[32]));
    m.broadcastChanges();
  }

  private static void viewIs(
      GameTestHelper h, Fixture f, String search, int sort, int view, String message) {
    menu(f).broadcastChanges();
    var shown = menu(f).view();
    h.assertTrue(
        shown.search().equals(search)
            && shown.sort() == sort
            && shown.view() == view
            && shown.status() == TerminalPackets.Status.READY
            && !shown.busy(),
        message + ": " + shown.search() + " " + shown.sort() + " " + shown.view());
  }

  /**
   * Q67: a saved view keeps the search, sort mode and direction and the item view, survives
   * reopening the terminal, and applies them in one command without touching layout flags. An empty
   * view applies nothing; right-click (clear) empties one.
   */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void savedViewsRestoreSearchSortAndView(GameTestHelper h) {
    var f = setup(h);
    int quantityUp = TerminalSearchSupport.QUANTITY | TerminalSearchSupport.REVERSED;
    int nameTall = TerminalSearchSupport.NAME | TerminalSearchSupport.TALL;
    h.startSequence()
        .thenWaitUntil(
            () ->
                TestMenus.ready(
                    h, f.terminal.canUse(f.player), f.terminal.getBlockPos(), "Terminal ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 2);
              open(h, f);
            })
        .thenWaitUntil(() -> shows(h, f, 1))
        .thenExecute(() -> search(f, "oak"))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.SORT, quantityUp, 1))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.CRAFTABLES, TerminalPackets.VIEW_ALL, 1))
        .thenWaitUntil(() -> viewIs(h, f, "oak", quantityUp, TerminalPackets.VIEW_ALL, "Set up"))
        .thenExecute(() -> command(f, TerminalPackets.VIEWS, 1, TerminalPackets.VIEW_SAVE))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    menu(f)
                            .view()
                            .saved()
                            .get(1)
                            .equals(
                                new TerminalPackets.SavedView(
                                    true, "oak", quantityUp, TerminalPackets.VIEW_ALL))
                        && !menu(f).view().saved().get(0).set(),
                    "View 2 holds the search, sort and view: " + menu(f).view().saved()))
        .thenIdle(GAP)
        .thenExecute(() -> search(f, ""))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.SORT, nameTall, 1))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.CRAFTABLES, TerminalPackets.VIEW_STORED, 1))
        .thenWaitUntil(() -> viewIs(h, f, "", nameTall, TerminalPackets.VIEW_STORED, "Changed"))
        .thenExecute(
            () -> {
              f.player.closeContainer();
              open(h, f);
            })
        .thenWaitUntil(() -> viewIs(h, f, "", nameTall, TerminalPackets.VIEW_STORED, "Reopened"))
        .thenExecute(() -> command(f, TerminalPackets.VIEWS, 0, TerminalPackets.VIEW_APPLY))
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              viewIs(h, f, "", nameTall, TerminalPackets.VIEW_STORED, "An empty view does nothing");
              command(f, TerminalPackets.VIEWS, 1, TerminalPackets.VIEW_APPLY);
            })
        .thenWaitUntil(
            () ->
                viewIs(
                    h,
                    f,
                    "oak",
                    quantityUp | TerminalSearchSupport.TALL,
                    TerminalPackets.VIEW_ALL,
                    "View 2 comes back after reopening, and Tall stays on"))
        .thenIdle(GAP)
        .thenExecute(() -> command(f, TerminalPackets.VIEWS, 1, TerminalPackets.VIEW_CLEAR))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    menu(f).view().saved().stream().noneMatch(TerminalPackets.SavedView::set),
                    "Right-click empties it"))
        .thenExecute(
            () -> {
              var views =
                  f.player
                      .getPersistentData()
                      .getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                      .getCompound("FactoryCoreTerminal")
                      .getList("Views", net.minecraft.nbt.Tag.TAG_COMPOUND);
              h.assertTrue(
                  views.size() == TerminalPackets.SAVED_VIEWS
                      && views.stream()
                          .allMatch(tag -> ((net.minecraft.nbt.CompoundTag) tag).isEmpty()),
                  "The emptied view is saved empty: " + views);
              end(f);
            })
        .thenSucceed();
  }
}
