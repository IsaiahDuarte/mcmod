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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), DRIVE = new BlockPos(1, 1, 2);
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
      net.cable(new BlockPos(x, 1, 1));
      h.setBlock(new BlockPos(x, 1, 2), Blocks.FURNACE);
      furnaces.add(new BlockPos(x, 1, 2));
    }
    h.setBlock(new BlockPos(2, 1, 0), Blocks.CHEST);
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
                  h.getBlockState(new BlockPos(2, 1, 0)).is(Blocks.CHEST),
                  "The chest is untouched");
              for (var cable : cables)
                h.assertTrue(
                    h.getBlockState(cable).getValue(BlockStateProperties.FACING) == Direction.SOUTH,
                    "Each port is aimed at its furnace, not the chest");
            })
        .thenWaitUntil(() -> net.assertValid(cables.getLast()))
        .thenExecute(() -> end(player))
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
              end(player);
            })
        .thenSucceed();
  }

  /**
   * A tower of 17 levels: a cable spine with an arm cable and a furnace on each side per level, 68
   * furnaces in all, each with its own cable.
   */
  @GameTest(template = "empty", batch = "label_row_cap", timeoutTicks = 600)
  public static void aRowStopsAtSixtyFourMachines(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    var root = new BlockPos(2, 1, 2);
    net.controller(root);
    net.drive(new BlockPos(3, 1, 3), CellTier.STARTER_ITEM);
    var arms = new ArrayList<BlockPos>();
    var furnaces = new ArrayList<BlockPos>();
    for (int y = 2; y < 19; y++) {
      net.cable(new BlockPos(2, y, 2));
      for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        var arm = new BlockPos(2 + side[0], y, 2 + side[1]);
        var furnace = new BlockPos(2 + 2 * side[0], y, 2 + 2 * side[1]);
        net.cable(arm);
        h.setBlock(furnace, Blocks.FURNACE);
        arms.add(arm);
        furnaces.add(furnace);
      }
    }
    var tool = tool(player, "oven");
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(new BlockPos(2, 18, 2)))
        .thenExecute(
            () -> {
              var result = LabelToolItem.labelRow(player, tool, h.absolutePos(furnaces.getFirst()));
              h.assertTrue(
                  result != null
                      && result.capped()
                      && result.labelled() == LabelToolItem.MAX_ROW
                      && result.refused() == 0,
                  "Stops at 64 of 68: " + result);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    labelledPorts(h, arms, "oven") == LabelToolItem.MAX_ROW,
                    "64 labelled ports, found " + labelledPorts(h, arms, "oven")))
        .thenExecute(
            () -> {
              int ports = 0;
              for (var arm : arms)
                if (h.getBlockState(arm).getBlock() instanceof CablePortBlock) ports++;
              h.assertTrue(ports == LabelToolItem.MAX_ROW, "Exactly 64 cables became ports");
              end(player);
            })
        .thenSucceed();
  }
}
