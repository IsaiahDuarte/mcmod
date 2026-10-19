package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Cable arms and their selection/collision shape follow eligible wired neighbors. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CableConnectionGameTests {
  private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos GATEWAY = new BlockPos(3, 1, 1);

  private CableConnectionGameTests() {}

  @GameTest(template = "empty")
  public static void armsAndShapeFollowEligibleNeighbors(GameTestHelper helper) {
    helper.setBlock(CONTROLLER, NetworkContent.CONTROLLER.get());
    helper.setBlock(GATEWAY.south(), NetworkContent.CABLE.get());
    // A north-facing gateway only exposes its north (downstream) and south (upstream) faces.
    helper.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.NORTH));
    helper.setBlock(CABLE.below(), Blocks.STONE);
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    var stack = new ItemStack(NetworkContent.CABLE_ITEM.get());
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var floor = helper.absolutePos(CABLE.below());
    var placed =
        stack
            .getItem()
            .useOn(
                new UseOnContext(
                    player,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false)));
    helper.assertTrue(placed.consumesAction(), "Actual cable item placement succeeded");

    var state = helper.getBlockState(CABLE);
    helper.assertTrue(
        state.getValue(NetworkCableBlock.property(Direction.WEST)), "Arm toward the controller");
    helper.assertFalse(
        state.getValue(NetworkCableBlock.property(Direction.EAST)),
        "No arm toward a gateway side face that is not a port");
    helper.assertFalse(
        state.getValue(NetworkCableBlock.property(Direction.DOWN)), "No arm toward plain stone");
    var shape =
        state.getShape(helper.getLevel(), helper.absolutePos(CABLE), CollisionContext.empty());
    helper.assertTrue(
        shape.min(Direction.Axis.X) == 0 && shape.max(Direction.Axis.X) == 10 / 16.0,
        "Shape spans the west arm and stops at the core on the east");
    helper.assertTrue(
        shape.min(Direction.Axis.Y) == 6 / 16.0 && shape.max(Direction.Axis.Y) == 10 / 16.0,
        "Shape has no vertical arms");

    // Neighbor updates add and remove arms, including toward the gateway's upstream port.
    helper.setBlock(CABLE.south(), NetworkContent.CABLE.get());
    helper.assertTrue(
        helper.getBlockState(CABLE).getValue(NetworkCableBlock.property(Direction.SOUTH)),
        "Arm appears when a neighbor cable is placed");
    helper.assertTrue(
        helper.getBlockState(GATEWAY.south()).getValue(NetworkCableBlock.property(Direction.NORTH)),
        "Arm toward the gateway's upstream port after a neighbor update");
    helper.setBlock(CONTROLLER, Blocks.AIR);
    helper.assertFalse(
        helper.getBlockState(CABLE).getValue(NetworkCableBlock.property(Direction.WEST)),
        "Arm disappears when the controller is removed");
    helper.succeed();
  }
}
