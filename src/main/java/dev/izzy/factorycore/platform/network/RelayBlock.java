package dev.izzy.factorycore.platform.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Q31 (QD7): a Relay connects like an uncoloured cable on every face while it has a redstone
 * signal, and on none without one, so the network beyond it goes offline. Each change rebuilds the
 * network, so a change is applied at most once per {@link #COOLDOWN} ticks: after one, a scheduled
 * tick re-reads the signal only when the cooldown ends. It has no entity and saves nothing but its
 * state.
 */
public final class RelayBlock extends NetworkedBlock {
  public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

  /** Ticks between applied changes: one network rebuild per second at most. */
  public static final int COOLDOWN = 20;

  public RelayBlock(Properties properties) {
    super(properties);
    registerDefaultState(stateDefinition.any().setValue(POWERED, false));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(POWERED);
  }

  @Override
  public boolean wiredFace(BlockState state, Direction face) {
    return state.getValue(POWERED);
  }

  /** Placed already reading its signal, like a lamp; later changes go through the cooldown. */
  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
  }

  @Override
  protected void neighborChanged(
      BlockState state,
      Level level,
      BlockPos position,
      Block neighbor,
      BlockPos from,
      boolean moved) {
    super.neighborChanged(state, level, position, neighbor, from, moved);
    // During a cooldown the pending tick re-reads the signal when it ends.
    if (!level.isClientSide() && !level.getBlockTicks().hasScheduledTick(position, this))
      level.scheduleTick(position, this, 1);
  }

  @Override
  protected void tick(BlockState state, ServerLevel level, BlockPos position, RandomSource random) {
    boolean powered = level.hasNeighborSignal(position);
    if (powered == state.getValue(POWERED)) return;
    level.setBlock(position, state.setValue(POWERED, powered), Block.UPDATE_ALL);
    level.scheduleTick(position, this, COOLDOWN);
  }
}
