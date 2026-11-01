package dev.izzy.factorycore.testing.tunnel;

import dev.izzy.factorycore.platform.tunnel.TunnelBlock;
import dev.izzy.factorycore.platform.tunnel.TunnelContent;
import dev.izzy.factorycore.platform.tunnel.TunnelEntity;
import dev.izzy.factorycore.testing.ConnectorFixture;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q37 (M36, ADR 0071): an input Tunnel forwards items, fluids and FE inserted at its front to the
 * blocks in front of its outputs, in turn, and its outputs repeat its redstone signal; pairing
 * needs Manage; an end off the network carries nothing.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TunnelGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 2, 1),
      INPUT = new BlockPos(2, 2, 2),
      INPUT_FRONT = new BlockPos(2, 2, 3),
      SPLIT = new BlockPos(4, 2, 1),
      OUT_A = new BlockPos(4, 2, 2),
      OUT_B = new BlockPos(4, 2, 0),
      TARGET_A = new BlockPos(5, 2, 2),
      TARGET_B = new BlockPos(5, 2, 0);

  private TunnelGameTests() {}

  private static TunnelEntity tunnel(GameTestHelper h, BlockPos at, Direction facing) {
    h.setBlock(
        at,
        TunnelContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, facing));
    return h.getBlockEntity(at);
  }

  /** A player's sneak-use with an empty hand, through the block's own interaction. */
  private static void sneakUse(GameTestHelper h, ServerPlayer player, BlockPos at) {
    var position = h.absolutePos(at);
    player.setShiftKeyDown(true);
    h.getLevel()
        .getBlockState(position)
        .useWithoutItem(
            h.getLevel(),
            player,
            new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
    player.setShiftKeyDown(false);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void anInputForwardsItemsFluidsEnergyAndRedstoneToItsOutputs(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var stranger = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    TestPlayers.connect(stranger);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.cable(new BlockPos(2, 2, 1), new BlockPos(3, 2, 1), SPLIT);
    var input = tunnel(h, INPUT, Direction.SOUTH);
    var a = tunnel(h, OUT_A, Direction.EAST);
    var b = tunnel(h, OUT_B, Direction.EAST);
    h.setBlock(TARGET_A, ConnectorFixture.BLOCK.get());
    h.setBlock(TARGET_B, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity targetA = h.getBlockEntity(TARGET_A);
    ConnectorFixture.ProviderEntity targetB = h.getBlockEntity(TARGET_B);
    var level = h.getLevel();
    var inputAt = h.absolutePos(INPUT);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(INPUT, OUT_A, OUT_B))
        .thenExecute(
            () -> {
              sneakUse(h, stranger, INPUT);
              h.assertTrue(
                  input.role() == TunnelBlock.Role.UNSET,
                  "Without Manage a player cannot set up a tunnel");
              sneakUse(h, owner, INPUT);
              sneakUse(h, owner, OUT_A);
              sneakUse(h, owner, OUT_B);
              sneakUse(h, owner, INPUT);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    input.role() == TunnelBlock.Role.INPUT
                        && a.role() == TunnelBlock.Role.OUTPUT
                        && b.role() == TunnelBlock.Role.OUTPUT
                        && input.frequency().equals(a.frequency())
                        && input.frequency().equals(b.frequency())
                        && input.outputs().size() == 2,
                    "Sneak-use makes an input, then two outputs of it: "
                        + input.role()
                        + " "
                        + a.role()
                        + " "
                        + b.role()
                        + " "
                        + input.outputs().size()))
        .thenExecute(
            () -> {
              h.assertTrue(
                  level.getCapability(Capabilities.ItemHandler.BLOCK, inputAt, Direction.NORTH)
                          == null
                      && level.getCapability(
                              Capabilities.ItemHandler.BLOCK, h.absolutePos(OUT_A), Direction.EAST)
                          == null,
                  "Only an input's front face accepts anything");
              var items =
                  level.getCapability(Capabilities.ItemHandler.BLOCK, inputAt, Direction.SOUTH);
              h.assertTrue(
                  items.insertItem(0, new ItemStack(Items.IRON_INGOT, 10), true).isEmpty()
                      && targetA.items.getStackInSlot(0).isEmpty()
                      && targetB.items.getStackInSlot(0).isEmpty(),
                  "A simulated insert moves nothing");
              h.assertTrue(
                  items.insertItem(0, new ItemStack(Items.IRON_INGOT, 10), false).isEmpty()
                      && items.insertItem(0, new ItemStack(Items.IRON_INGOT, 10), false).isEmpty(),
                  "Inserted items are forwarded");
              h.assertTrue(
                  targetA.items.getStackInSlot(0).getCount() == 10
                      && targetB.items.getStackInSlot(0).getCount() == 10,
                  "Outputs are used in turn: "
                      + targetA.items.getStackInSlot(0)
                      + " "
                      + targetB.items.getStackInSlot(0));
              // Fill both targets: what does not fit comes back.
              var left = items.insertItem(0, new ItemStack(Items.DIRT, 64 * 4), false);
              int stored = 0;
              for (var target : new ConnectorFixture.ProviderEntity[] {targetA, targetB})
                for (int slot = 0; slot < 2; slot++)
                  if (target.items.getStackInSlot(slot).is(Items.DIRT))
                    stored += target.items.getStackInSlot(slot).getCount();
              h.assertTrue(
                  stored + left.getCount() == 64 * 4 && left.getCount() > 0,
                  "What no output can take is returned, never lost: " + stored + "+" + left);
              var fluids =
                  level.getCapability(Capabilities.FluidHandler.BLOCK, inputAt, Direction.SOUTH);
              h.assertTrue(
                  fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE)
                          == 1000
                      && targetA.fluids.getFluidAmount() + targetB.fluids.getFluidAmount() == 1000,
                  "Fluid is forwarded");
              var energy =
                  level.getCapability(Capabilities.EnergyStorage.BLOCK, inputAt, Direction.SOUTH);
              h.assertTrue(
                  energy.receiveEnergy(500, false) == 500
                      && targetA.energy.getEnergyStored() + targetB.energy.getEnergyStored() == 500,
                  "FE is forwarded");
              h.setBlock(INPUT_FRONT, Blocks.REDSTONE_BLOCK);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    a.signal() == 15
                        && level.getSignal(h.absolutePos(OUT_A), Direction.WEST) == 15
                        && level.getSignal(h.absolutePos(OUT_B), Direction.WEST) == 15,
                    "Outputs repeat the input's redstone signal toward the block they face"))
        .thenExecute(() -> h.setBlock(INPUT_FRONT, Blocks.AIR))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    level.getSignal(h.absolutePos(OUT_A), Direction.WEST) == 0,
                    "and drop it when it ends"))
        .thenExecute(
            () -> {
              h.setBlock(INPUT_FRONT, Blocks.REDSTONE_BLOCK);
              // Cut the outputs off the network.
              h.setBlock(SPLIT, Blocks.AIR);
            })
        .thenWaitUntil(
            () -> {
              h.assertTrue(input.outputs().isEmpty(), "Outputs off the network are not linked");
              h.assertTrue(
                  a.signal() == 0 && b.signal() == 0,
                  "Unlinked outputs emit nothing: " + a.signal());
              var items =
                  level.getCapability(Capabilities.ItemHandler.BLOCK, inputAt, Direction.SOUTH);
              h.assertTrue(
                  items.insertItem(0, new ItemStack(Items.GOLD_INGOT, 5), false).getCount() == 5,
                  "An input with no linked output takes nothing");
            })
        .thenExecute(
            () -> {
              owner.connection.disconnect(Component.literal("done"));
              stranger.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
