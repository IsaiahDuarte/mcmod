package dev.izzy.factorycore.platform.monitor;

import dev.izzy.factorycore.platform.network.NetworkedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/** Q28: the Storage Monitor block; its face shows the count (see {@link MonitorEntity}). */
public final class MonitorBlock extends NetworkedBlock implements EntityBlock {
  public MonitorBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(HorizontalDirectionalBlock.FACING);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(
            HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide()
        && placer instanceof Player player
        && level.getBlockEntity(position) instanceof MonitorEntity monitor)
      monitor.place(player.getUUID());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new MonitorEntity(position, state);
  }

  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack held,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (hand != InteractionHand.MAIN_HAND)
      return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
    if (player instanceof ServerPlayer server
        && level.getBlockEntity(position) instanceof MonitorEntity monitor
        && monitor.use(server, held)) return ItemInteractionResult.CONSUME;
    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (player instanceof ServerPlayer server
        && level.getBlockEntity(position) instanceof MonitorEntity monitor)
      monitor.use(server, ItemStack.EMPTY);
    return InteractionResult.CONSUME;
  }
}
