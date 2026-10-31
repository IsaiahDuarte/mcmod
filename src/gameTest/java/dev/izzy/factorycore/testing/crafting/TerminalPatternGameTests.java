package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.TerminalPatternPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Ergonomics Q3: the terminal's Patterns view lists the Crafters its segment can use (a gateway
 * that withholds CRAFT hides its branch's), filters them by the terminal search, scrolls a bounded
 * window, and moves patterns through the cursor only with EDIT where the Crafter is.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalPatternGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      TERMINAL = new BlockPos(0, 1, 1),
      NORTH = new BlockPos(1, 1, 0),
      SOUTH = new BlockPos(1, 1, 2),
      ABOVE = new BlockPos(1, 2, 1),
      GATEWAY = new BlockPos(2, 1, 1),
      BRANCH = new BlockPos(3, 1, 1);

  private TerminalPatternGameTests() {}

  private static ItemStack pattern(Item input, Item output) {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(input));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(UUID.randomUUID(), 1, 0, cells, new ItemStack(output), List.of()));
    return stack;
  }

  private static UUID id(ItemStack stack) {
    return stack.get(CraftingContent.PATTERN.get()).id();
  }

  private static CrafterEntity crafter(GameTestHelper h, BlockPos pos, ItemStack... patterns) {
    h.setBlock(pos, CraftingContent.CRAFTER.get());
    CrafterEntity crafter = h.getBlockEntity(pos);
    for (int slot = 0; slot < patterns.length; slot++)
      crafter.patterns().insertItem(slot, patterns[slot], false);
    return crafter;
  }

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static TerminalEntity terminal(GameTestHelper h) {
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return h.getBlockEntity(TERMINAL);
  }

  private static TerminalMenu menu(GameTestHelper h, ServerPlayer player) {
    h.assertTrue(player.containerMenu instanceof TerminalMenu, "Terminal open");
    return (TerminalMenu) player.containerMenu;
  }

  /** Opens the terminal and its Patterns view (the client's first WINDOW command). */
  private static TerminalMenu open(GameTestHelper h, ServerPlayer player) {
    h.useBlock(TERMINAL, player);
    var m = menu(h, player);
    m.broadcastChanges();
    patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0);
    return m;
  }

  private static TerminalPatternPackets.View patterns(
      GameTestHelper h, ServerPlayer player, int action, int selection, int slot) {
    var m = menu(h, player);
    m.patternCommand(
        player,
        new TerminalPatternPackets.Command(
            m.containerId, m.session(), m.patternView().revision(), action, selection, slot));
    m.broadcastChanges();
    return m.patternView();
  }

  private static int row(GameTestHelper h, TerminalPatternPackets.View view, BlockPos pos) {
    var crafters = view.crafters();
    for (int index = 0; index < crafters.size(); index++)
      if (crafters.get(index).position().equals(h.absolutePos(pos))) return index;
    return -1;
  }

  /**
   * A network with two Crafters and a gateway to a branch holding a third. {@code craft} says
   * whether the gateway publishes the branch's patterns; the policy is set before the network first
   * forms, so no rebuild runs mid-test (M145).
   */
  private static ServerPlayer branchFixture(GameTestHelper h, boolean craft) {
    var player = player(h);
    Net.in(h, player.getUUID()).controller(ROOT);
    crafter(h, NORTH, pattern(Items.OAK_LOG, Items.OAK_PLANKS));
    crafter(h, SOUTH, pattern(Items.OAK_PLANKS, Items.STICK));
    h.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gate = h.getBlockEntity(GATEWAY);
    gate.commission(null);
    if (!craft) {
      var branch = EnumSet.allOf(NetworkPermissions.Permission.class);
      branch.remove(NetworkPermissions.Permission.CRAFT);
      h.assertTrue(
          DeviceSavedData.get(h.getLevel().getServer())
                  .nodes()
                  .setPolicy(
                      gate.handle(),
                      new GatewayPolicy(
                          branch,
                          EnumSet.allOf(NetworkPermissions.Permission.class),
                          EnumSet.allOf(ResourceKind.class)))
              == NodeRegistry.Failure.OK,
          "Gateway withholds CRAFT");
    }
    crafter(h, BRANCH, pattern(Items.COAL, Items.TORCH));
    return player;
  }

  /**
   * Both Crafters on the network are listed with each slot's output, and the branch Crafter as
   * published while its gateway allows CRAFT. The search keeps only Crafters with a matching
   * output.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void craftersAndAPublishedBranchAreListed(GameTestHelper h) {
    var player = branchFixture(h, true);
    var terminal = terminal(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(() -> open(h, player))
        .thenWaitUntil(
            () -> {
              var view = patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0);
              h.assertTrue(
                  view.total() == 3
                      && row(h, view, NORTH) >= 0
                      && row(h, view, SOUTH) >= 0
                      && row(h, view, BRANCH) >= 0
                      && view.crafters().get(row(h, view, BRANCH)).published()
                      && !view.crafters().get(row(h, view, NORTH)).published(),
                  "Both Crafters and the published branch Crafter are listed: " + view);
              var north = view.crafters().get(row(h, view, NORTH));
              h.assertTrue(
                  north.patterns().getFirst().icon().equals("minecraft:oak_planks")
                      && north.patterns().get(1).icon().isEmpty(),
                  "A row shows each slot's output: " + north);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              m.command(
                  player,
                  new TerminalPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision(),
                      TerminalPackets.SEARCH,
                      0,
                      1,
                      "stick",
                      new byte[32]));
              m.broadcastChanges();
              var view = m.patternView();
              h.assertTrue(
                  view.total() == 1
                      && row(h, view, SOUTH) == 0
                      && view.crafters().getFirst().patterns().getFirst().match(),
                  "The search keeps the Crafter that makes sticks: " + view);
            })
        .thenSucceed();
  }

  /** A gateway that withholds CRAFT hides its branch's Crafter; the network's own stay listed. */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aBranchWithoutCraftIsHidden(GameTestHelper h) {
    var player = branchFixture(h, false);
    var terminal = terminal(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(() -> open(h, player))
        .thenWaitUntil(
            () -> {
              var view = patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0);
              h.assertTrue(
                  view.total() == 2
                      && row(h, view, NORTH) >= 0
                      && row(h, view, SOUTH) >= 0
                      && row(h, view, BRANCH) < 0,
                  "Only the network's own Crafters are listed: " + view);
              h.assertTrue(
                  WorldNetworks.get(h.getLevel().getServer())
                          .membership(Positions.of(h.getLevel(), h.absolutePos(BRANCH)))
                          .status()
                      == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID,
                  "The branch itself is online, only hidden");
            })
        .thenSucceed();
  }

  /** The owner takes the third of three patterns from a remote Crafter and inserts it elsewhere. */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aPatternIsTakenAndInsertedRemotely(GameTestHelper h) {
    var player = player(h);
    Net.in(h, player.getUUID()).controller(ROOT);
    ItemStack first = pattern(Items.OAK_LOG, Items.OAK_PLANKS),
        second = pattern(Items.OAK_PLANKS, Items.STICK),
        third = pattern(Items.COAL, Items.TORCH);
    var crafter = crafter(h, NORTH, first.copy(), second.copy(), third.copy());
    var terminal = terminal(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(() -> open(h, player))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0).total() == 1,
                    "The Crafter is listed"))
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              h.assertTrue(m.patternView().crafters().getFirst().editable(), "Owner may edit");
              var view = patterns(h, player, TerminalPatternPackets.CLICK, 0, 2);
              h.assertTrue(
                  view.status() == TerminalPatternPackets.Status.TAKEN
                      && id(m.getCarried()).equals(id(third))
                      && crafter.patterns().getStackInSlot(2).isEmpty()
                      && crafter.patternCount() == 2
                      && view.crafters().getFirst().patterns().get(2).icon().isEmpty(),
                  "The third pattern is on the cursor: " + view);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              var view = patterns(h, player, TerminalPatternPackets.CLICK, 0, 6);
              h.assertTrue(
                  view.status() == TerminalPatternPackets.Status.INSERTED
                      && m.getCarried().isEmpty()
                      && id(crafter.patterns().getStackInSlot(6)).equals(id(third))
                      && view.crafters()
                          .getFirst()
                          .patterns()
                          .get(6)
                          .icon()
                          .equals("minecraft:torch"),
                  "It goes back into the slot clicked: " + view);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(h, player);
              m.setCarried(new ItemStack(Items.DIAMOND));
              var view = patterns(h, player, TerminalPatternPackets.CLICK, 0, 3);
              h.assertTrue(
                  view.status() == TerminalPatternPackets.Status.REFUSED
                      && m.getCarried().is(Items.DIAMOND)
                      && crafter.patterns().getStackInSlot(3).isEmpty(),
                  "Anything but a pattern is refused: " + view);
              m.setCarried(ItemStack.EMPTY);
            })
        .thenSucceed();
  }

  /** A member without EDIT sees the patterns but can neither take nor insert one. */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void withoutEditPatternsStayPut(GameTestHelper h) {
    var owner = player(h);
    var member = player(h);
    Net.in(h, owner.getUUID()).controller(ROOT);
    var held = pattern(Items.OAK_LOG, Items.OAK_PLANKS);
    var crafter = crafter(h, NORTH, held.copy());
    var terminal = terminal(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(owner), "Terminal ready"))
        .thenExecute(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .setGrants(
                                new NetworkPermissions.Actor(owner.getUUID(), false),
                                Positions.of(h.getLevel(), h.absolutePos(TERMINAL)),
                                member.getUUID(),
                                NetworkPermissions.AccessRole.MEMBER.grants())
                        == NetworkPermissions.Change.OK,
                    "Member granted, without EDIT"))
        .thenExecute(() -> open(h, member))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    patterns(h, member, TerminalPatternPackets.WINDOW, 0, 0).total() == 1,
                    "The member sees the Crafter"))
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(h, member);
              h.assertFalse(
                  m.patternView().crafters().getFirst().editable(), "The row says it is locked");
              var view = patterns(h, member, TerminalPatternPackets.CLICK, 0, 0);
              h.assertTrue(
                  view.status() == TerminalPatternPackets.Status.DENIED
                      && m.getCarried().isEmpty()
                      && id(crafter.patterns().getStackInSlot(0)).equals(id(held)),
                  "A take without EDIT is denied: " + view);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(h, member);
              var mine = pattern(Items.COAL, Items.TORCH);
              m.setCarried(mine.copy());
              var view = patterns(h, member, TerminalPatternPackets.CLICK, 0, 1);
              h.assertTrue(
                  view.status() == TerminalPatternPackets.Status.DENIED
                      && id(m.getCarried()).equals(id(mine))
                      && crafter.patterns().getStackInSlot(1).isEmpty(),
                  "An insert without EDIT is denied: " + view);
              m.setCarried(ItemStack.EMPTY);
            })
        .thenSucceed();
  }

  /** Four Crafters scroll through a window of three, a far scroll stopping at the last one. */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void theCrafterListIsBoundedAndScrolls(GameTestHelper h) {
    var player = player(h);
    Net.in(h, player.getUUID()).controller(ROOT);
    for (var pos : List.of(NORTH, SOUTH, ABOVE, GATEWAY))
      crafter(h, pos, pattern(Items.OAK_LOG, Items.OAK_PLANKS));
    var terminal = terminal(h);
    var first = new ArrayList<BlockPos>();
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(() -> open(h, player))
        .thenWaitUntil(
            () -> {
              var view = patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0);
              h.assertTrue(
                  view.total() == 4
                      && view.offset() == 0
                      && view.crafters().size() == TerminalPatternPackets.ROWS,
                  "Four Crafters, three shown: " + view);
              for (var row : view.crafters()) first.add(row.position());
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var view = patterns(h, player, TerminalPatternPackets.WINDOW, 1, 0);
              h.assertTrue(
                  view.offset() == 1
                      && view.crafters().size() == 3
                      && view.crafters().getFirst().position().equals(first.get(1)),
                  "One step scrolls by one Crafter: " + view);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var view =
                  patterns(
                      h,
                      player,
                      TerminalPatternPackets.WINDOW,
                      TerminalPatternPackets.MAX_OFFSET,
                      0);
              h.assertTrue(
                  view.offset() == 3
                      && view.crafters().size() == 1
                      && !first.contains(view.crafters().getFirst().position()),
                  "A far scroll stops at the last Crafter: " + view);
              boolean refused = false;
              try {
                new TerminalPatternPackets.Command(
                    0,
                    UUID.randomUUID(),
                    0,
                    TerminalPatternPackets.CLICK,
                    TerminalPatternPackets.ROWS,
                    0);
              } catch (IllegalArgumentException expected) {
                refused = true;
              }
              h.assertTrue(refused, "A click outside the window cannot be sent");
            })
        .thenSucceed();
  }

  /**
   * Q107: the Label Tool labels a Crafter (instead of opening its screen), the Patterns view names
   * it by the label, and the label survives a reload and breaking and placing the Crafter again.
   * Without EDIT the tool is refused.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aLabelledCrafterIsNamedByItsLabel(GameTestHelper h) {
    var player = player(h);
    Net.in(h, player.getUUID()).controller(ROOT);
    var crafter = crafter(h, NORTH, pattern(Items.OAK_LOG, Items.OAK_PLANKS));
    var terminal = terminal(h);
    var tool = new ItemStack(LabelToolContent.ITEM.get());
    tool.set(LabelToolContent.DATA.get(), new LabelToolContent.Data("Planks", new CompoundTag()));
    var stranger = TestPlayers.mock(h);
    TestPlayers.connect(stranger);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              stranger.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
              label(h, stranger);
              h.assertTrue(crafter.label().isEmpty(), "Without EDIT nothing is labelled");
              player.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
              label(h, player);
              h.assertTrue(
                  crafter.label().equals("Planks") && player.containerMenu == player.inventoryMenu,
                  "The tool labels the Crafter instead of opening it: " + crafter.label());
              player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
              open(h, player);
            })
        .thenWaitUntil(
            () -> {
              var view = patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0);
              h.assertTrue(
                  row(h, view, NORTH) >= 0
                      && view.crafters().get(row(h, view, NORTH)).label().equals("Planks"),
                  "The Patterns view names the Crafter by its label: " + view);
            })
        .thenExecute(
            () -> {
              player.closeContainer();
              var access = h.getLevel().registryAccess();
              var saved = crafter.saveWithFullMetadata(access);
              var reloaded =
                  (CrafterEntity)
                      BlockEntity.loadStatic(
                          h.absolutePos(SOUTH), crafter.getBlockState(), saved, access);
              h.assertTrue(
                  reloaded != null && reloaded.label().equals("Planks"),
                  "A reload keeps the label");
              var item = new ItemStack(CraftingContent.CRAFTER.get());
              item.applyComponents(crafter.collectComponents());
              h.setBlock(NORTH, net.minecraft.world.level.block.Blocks.AIR);
              h.setBlock(SOUTH, CraftingContent.CRAFTER.get());
              CrafterEntity placed = h.getBlockEntity(SOUTH);
              placed.applyComponents(item.getComponents(), item.getComponentsPatch());
              h.assertTrue(
                  placed.label().equals("Planks")
                      && "Planks".equals(item.getHoverName().getString()),
                  "Breaking and placing keeps the label, carried as the item's name: "
                      + placed.label());
            })
        .thenSucceed();
  }

  private static void label(GameTestHelper h, ServerPlayer player) {
    var at = h.absolutePos(NORTH);
    player.gameMode.useItemOn(
        player,
        h.getLevel(),
        player.getMainHandItem(),
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
  }

  private static void search(GameTestHelper h, ServerPlayer player, String text) {
    var m = menu(h, player);
    m.command(
        player,
        new dev.izzy.factorycore.platform.storage.TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            dev.izzy.factorycore.platform.storage.TerminalPackets.SEARCH,
            0,
            1,
            text,
            new byte[32]));
    m.broadcastChanges();
  }

  /**
   * Q100: the filter lists only Crafters with a free slot (Not full) or hidden ones too (All); the
   * search mode matches inputs or Crafter labels; a hidden Crafter is saved hidden; Shift-click
   * stores an inventory pattern in the first listed Crafter with a free slot.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void filterModesHidingAndShiftClickStore(GameTestHelper h) {
    var player = player(h);
    Net.in(h, player.getUUID()).controller(ROOT);
    var full = new ItemStack[TerminalPatternPackets.SLOTS];
    for (int slot = 0; slot < full.length; slot++)
      full[slot] = pattern(Items.OAK_LOG, Items.OAK_PLANKS);
    var wood = crafter(h, NORTH, full);
    wood.label("Wood shop");
    var torches = crafter(h, SOUTH, pattern(Items.COAL, Items.TORCH));
    var terminal = terminal(h);
    var spare = pattern(Items.OAK_PLANKS, Items.STICK);
    // Commands share the session's rate gate, so each waits a few ticks after the last.
    int gap = 5;
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(() -> open(h, player))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    patterns(h, player, TerminalPatternPackets.WINDOW, 0, 0).total() == 2,
                    "Both Crafters are listed"))
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var view =
                  patterns(
                      h, player, TerminalPatternPackets.FILTER, TerminalPatternPackets.NOT_FULL, 0);
              h.assertTrue(
                  view.total() == 1
                      && row(h, view, SOUTH) == 0
                      && view.filter() == TerminalPatternPackets.NOT_FULL,
                  "Not full leaves out the full Crafter: " + view);
            })
        .thenIdle(gap)
        .thenExecute(
            () ->
                patterns(
                    h, player, TerminalPatternPackets.FILTER, TerminalPatternPackets.VISIBLE, 0))
        .thenIdle(gap)
        .thenExecute(() -> search(h, player, "coal"))
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var outputs = menu(h, player).patternView();
              h.assertTrue(outputs.total() == 0, "No pattern makes coal: " + outputs);
            })
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var inputs =
                  patterns(
                      h, player, TerminalPatternPackets.MODE, TerminalPatternPackets.INPUTS, 0);
              h.assertTrue(
                  inputs.total() == 1
                      && row(h, inputs, SOUTH) == 0
                      && inputs.crafters().getFirst().patterns().getFirst().match(),
                  "The torch pattern takes coal: " + inputs);
            })
        .thenIdle(gap)
        .thenExecute(() -> search(h, player, "shop"))
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var names =
                  patterns(h, player, TerminalPatternPackets.MODE, TerminalPatternPackets.NAMES, 0);
              h.assertTrue(
                  names.total() == 1 && row(h, names, NORTH) == 0,
                  "The label is searched: " + names);
            })
        .thenIdle(gap)
        .thenExecute(
            () ->
                patterns(h, player, TerminalPatternPackets.MODE, TerminalPatternPackets.OUTPUTS, 0))
        .thenIdle(gap)
        .thenExecute(() -> search(h, player, ""))
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var listed = menu(h, player).patternView();
              var hidden =
                  patterns(h, player, TerminalPatternPackets.HIDE, row(h, listed, SOUTH), 0);
              h.assertTrue(
                  hidden.status() == TerminalPatternPackets.Status.HIDDEN
                      && torches.hidden()
                      && hidden.total() == 1
                      && torches
                          .saveWithoutMetadata(h.getLevel().registryAccess())
                          .getBoolean("Hidden"),
                  "Hidden from the list, and saved hidden: " + hidden);
            })
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var all =
                  patterns(h, player, TerminalPatternPackets.FILTER, TerminalPatternPackets.ALL, 0);
              h.assertTrue(
                  all.total() == 2 && all.crafters().get(row(h, all, SOUTH)).hidden(),
                  "All lists it, marked hidden: " + all);
            })
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var shown =
                  patterns(
                      h,
                      player,
                      TerminalPatternPackets.HIDE,
                      row(h, menu(h, player).patternView(), SOUTH),
                      0);
              h.assertTrue(
                  shown.status() == TerminalPatternPackets.Status.SHOWN && !torches.hidden(),
                  "Shown again: " + shown);
              player.getInventory().setItem(5, spare.copy());
            })
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var stored = patterns(h, player, TerminalPatternPackets.STORE, 5, 0);
              h.assertTrue(
                  stored.status() == TerminalPatternPackets.Status.INSERTED
                      && player.getInventory().getItem(5).isEmpty()
                      && id(torches.patterns().getStackInSlot(1)).equals(id(spare))
                      && wood.patternCount() == TerminalPatternPackets.SLOTS,
                  "The full Crafter is skipped; the next free slot takes it: " + stored);
              player.getInventory().setItem(6, new ItemStack(Items.DIAMOND));
            })
        .thenIdle(gap)
        .thenExecute(
            () -> {
              var refused = patterns(h, player, TerminalPatternPackets.STORE, 6, 0);
              h.assertTrue(
                  refused.status() == TerminalPatternPackets.Status.REFUSED
                      && player.getInventory().getItem(6).is(Items.DIAMOND),
                  "Anything but a pattern stays: " + refused);
            })
        .thenSucceed();
  }
}
