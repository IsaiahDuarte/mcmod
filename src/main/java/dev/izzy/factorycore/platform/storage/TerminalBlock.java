package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.network.WorldNetworks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

public final class TerminalBlock extends Block implements EntityBlock {
  public TerminalBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.HORIZONTAL_FACING);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(
            BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new TerminalEntity(pos, state);
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(player instanceof ServerPlayer server)
        || !(level.getBlockEntity(pos) instanceof TerminalEntity terminal))
      return InteractionResult.FAIL;
    if (!terminal.canUse(server)) {
      server.displayClientMessage(
          Component.translatable("message.factorycore.terminal_unavailable"), true);
      return InteractionResult.FAIL;
    }
    server.openMenu(
        terminal,
        buffer -> {
          buffer.writeBlockPos(pos);
          buffer.writeUUID(terminal.sessionForOpen());
        });
    return InteractionResult.CONSUME;
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
    super.onPlace(state, level, pos, old, moved);
    WorldNetworks.changed(level, pos);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
    WorldNetworks.changed(level, pos);
    super.onRemove(state, level, pos, next, moved);
  }

  @Override
  protected void neighborChanged(
      BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos from, boolean moved) {
    super.neighborChanged(state, level, pos, neighbor, from, moved);
    WorldNetworks.changed(level, pos);
  }
}
