package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.storage.CablePortBlock;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.LabelToolItem;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q9: the Label Tool's row key labels every machine of the clicked machine's type along its cable
 * run. Each fixture is larger than the template, so each runs in its own batch.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class LabelRowGameTests {
  /**
   * These fixtures sit high above the template, away from the positions every batch reuses (a
   * controller at 1, 1, 1): built there, a row's ports and network records disturbed later batches'
   * tests even after removal.
   */
  private static final int LIFT = 40;

  private static final BlockPos ROOT = new BlockPos(1, 1 + LIFT, 1),
      DRIVE = new BlockPos(1, 1 + LIFT, 2);
  private static final int LINE = 8;

  private LabelRowGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static ItemStack tool(ServerPlayer player, String label) {
    var tool = new ItemStack(LabelToolContent.ITEM.get());
    tool.set(LabelToolContent.DATA.get(), new LabelToolContent.Data(label, new CompoundTag()));
    player.setItemInHand(InteractionHand.MAIN_HAND, tool);
    return tool;
  }

  /**
   * Cable x = 2..9 at z = 1 beside the root; a furnace south of each, a chest north of the first.
   */
  private static List<BlockPos> line(GameTestHelper h, Net net) {
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var furnaces = new ArrayList<BlockPos>();
    for (int x = 2; x < 2 + LINE; x++) {
      net.cable(new BlockPos(x, 1 + LIFT, 1));
      h.setBlock(new BlockPos(x, 1 + LIFT, 2), Blocks.FURNACE);
      furnaces.add(new BlockPos(x, 1 + LIFT, 2));
    }
    h.setBlock(new BlockPos(2, 1 + LIFT, 0), Blocks.CHEST);
    return furnaces;
  }

  private static int labelledPorts(GameTestHelper h, List<BlockPos> cables, String label) {
    int count = 0;
    for (var cable : cables)
      if (h.getBlockState(cable).getBlock() instanceof CablePortBlock
          && h.getBlockEntity(cable) instanceof MachineInterfaceEntity port
          && port.label().equals(label)) count++;
    return count;
  }

  private static void end(ServerPlayer player) {
    player.connection.disconnect(Component.literal("Label row fixture complete"));
  }

  /**
   * These fixtures reach past the template, which the next batch's placement does not clear, so
   * each test removes what it built; left behind, its cables would join later tests' networks.
   */
  private static void clear(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
    for (int x = x0; x <= x1; x++)
      for (int y = y0; y <= y1; y++)
        for (int z = z0; z <= z1; z++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
  }

  /** Everything {@link #line} builds. */
  private static void clearLine(GameTestHelper h) {
    clear(h, 1, 1 + LIFT, 0, 2 + LINE, 1 + LIFT, 2);
  }

  @GameTest(template = "empty", batch = "label_row_line", timeoutTicks = 400)
  public static void oneUseLabelsEveryFurnaceOnTheRunAndSkipsTheChest(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    var furnaces = line(h, net);
    var cables = furnaces.stream().map(BlockPos::north).toList();
    var tool = tool(player, "oven");
    LabelToolItem.RowResult[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(cables.getLast()))
        .thenExecute(
            () -> result[0] = LabelToolItem.labelRow(player, tool, h.absolutePos(furnaces.get(3))))
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0] != null
                        && result[0].labelled() == LINE
                        && result[0].refused() == 0
                        && !result[0].capped(),
                    "One use labels all eight: " + result[0]))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    labelledPorts(h, cables, "oven") == LINE,
                    "Every furnace's cable is a port labelled oven"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  h.getBlockState(new BlockPos(2, 1 + LIFT, 0)).is(Blocks.CHEST),
                  "The chest is untouched");
              for (var cable : cables)
                h.assertTrue(
                    h.getBlockState(cable).getValue(BlockStateProperties.FACING) == Direction.SOUTH,
                    "Each port is aimed at its furnace, not the chest");
            })
        .thenWaitUntil(() -> net.assertValid(cables.getLast()))
        .thenExecute(
            () -> {
              // Relabelling a row of existing ports changes all of them in one batch. Checked one
              // at a time, the first relabel's rebuild refused the other seven.
              tool.set(
                  LabelToolContent.DATA.get(),
                  new LabelToolContent.Data("kiln", new CompoundTag()));
              var again = LabelToolItem.labelRow(player, tool, h.absolutePos(furnaces.get(5)));
              h.assertTrue(
                  again != null && again.labelled() == LINE && again.refused() == 0,
                  "All eight relabelled: " + again);
              h.assertTrue(labelledPorts(h, cables, "kiln") == LINE, "Relabelled at once");
            })
        .thenWaitUntil(() -> net.assertValid(cables.getLast()))
        .thenExecute(
            () -> {
              clearLine(h);
              end(player);
            })
        .thenSucceed();
  }

  private static void useOn(ServerPlayer player, ItemStack tool, BlockPos at) {
    tool.getItem()
        .useOn(
            new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
  }

  private static String toolLabel(ItemStack tool) {
    return tool.getOrDefault(LabelToolContent.DATA.get(), LabelToolContent.Data.EMPTY).label();
  }

  /** Q10: the pick key takes a machine's label onto the tool, which then labels another. */
  @GameTest(template = "empty", batch = "label_pick", timeoutTicks = 400)
  public static void aPickedLabelLabelsTheNextMachine(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    var furnaces = line(h, net);
    var first = furnaces.get(0);
    var second = furnaces.get(4);
    var tool = tool(player, "oven");
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(first.north(), second.north()))
        .thenExecute(() -> useOn(player, tool, h.absolutePos(first)))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    labelledPorts(h, List.of(first.north()), "oven") == 1, "First is labelled"))
        .thenWaitUntil(() -> net.assertValid(first.north()))
        .thenExecute(
            () -> {
              tool.set(
                  LabelToolContent.DATA.get(),
                  new LabelToolContent.Data("kiln", new CompoundTag()));
              h.assertTrue(
                  !LabelToolItem.pickInto(
                      player, tool, h.absolutePos(new BlockPos(2, 1 + LIFT, 0))),
                  "A chest has no label to pick");
              h.assertTrue(toolLabel(tool).equals("kiln"), "A failed pick keeps the label");
              h.assertTrue(
                  LabelToolItem.pickInto(player, tool, h.absolutePos(first.north())),
                  "Picked from the port");
              h.assertTrue(toolLabel(tool).equals("oven"), "The port's label: " + toolLabel(tool));
              tool.set(
                  LabelToolContent.DATA.get(),
                  new LabelToolContent.Data("kiln", new CompoundTag()));
              h.assertTrue(
                  LabelToolItem.pickInto(player, tool, h.absolutePos(first)),
                  "Picked from the machine");
              h.assertTrue(
                  toolLabel(tool).equals("oven"), "The machine's label: " + toolLabel(tool));
              useOn(player, tool, h.absolutePos(second));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    labelledPorts(h, List.of(second.north()), "oven") == 1,
                    "The picked label went on the second machine"))
        .thenExecute(
            () -> {
              clearLine(h);
              end(player);
            })
        .thenSucceed();
  }

  /** Q10: a stranger cannot read another network's labels. */
  @GameTest(template = "empty", batch = "label_pick_foreign", timeoutTicks = 400)
  public static void aStrangerPicksNothing(GameTestHelper h) {
    var owner = player(h);
    var stranger = player(h);
    var net = Net.in(h, owner.getUUID());
    var furnaces = line(h, net);
    var first = furnaces.getFirst();
    var ownerTool = tool(owner, "oven");
    var strangerTool = tool(stranger, "kiln");
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(first.north()))
        .thenExecute(() -> useOn(owner, ownerTool, h.absolutePos(first)))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    labelledPorts(h, List.of(first.north()), "oven") == 1, "First is labelled"))
        .thenWaitUntil(() -> net.assertValid(first.north()))
        .thenExecute(
            () -> {
              h.assertTrue(
                  !LabelToolItem.pickInto(stranger, strangerTool, h.absolutePos(first)),
                  "No VIEW, no pick");
              h.assertTrue(toolLabel(strangerTool).equals("kiln"), "Label unchanged");
              clearLine(h);
              end(owner);
              end(stranger);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", batch = "label_row_foreign", timeoutTicks = 400)
  public static void aStrangerIsRefusedOnEveryMachineOfTheRow(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, UUID.randomUUID());
    var furnaces = line(h, net);
    var cables = furnaces.stream().map(BlockPos::north).toList();
    var tool = tool(player, "oven");
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(cables.getLast()))
        .thenExecute(
            () -> {
              var result = LabelToolItem.labelRow(player, tool, h.absolutePos(furnaces.getFirst()));
              h.assertTrue(
                  result != null && result.labelled() == 0 && result.refused() == LINE,
                  "Every machine is refused without EDIT: " + result);
              for (var cable : cables)
                h.assertTrue(
                    !(h.getBlockState(cable).getBlock() instanceof CablePortBlock),
                    "No cable became a port");
              clearLine(h);
              end(player);
            })
        .thenSucceed();
  }
}
