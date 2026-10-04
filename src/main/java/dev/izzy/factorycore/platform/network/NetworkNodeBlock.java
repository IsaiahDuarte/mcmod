package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

public final class NetworkNodeBlock extends Block implements EntityBlock {
  private final NodeRegistry.Kind kind;

  public NetworkNodeBlock(Properties properties, NodeRegistry.Kind kind) {
    super(properties);
    this.kind = kind;
    registerDefaultState(
        stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
  }

  public NodeRegistry.Kind kind() {
    return kind;
  }

  public boolean connects(BlockState state, Direction face) {
    return kind == NodeRegistry.Kind.CONTROLLER
        || face == state.getValue(BlockStateProperties.FACING)
        || face == state.getValue(BlockStateProperties.FACING).getOpposite();
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(BlockStateProperties.FACING, context.getNearestLookingDirection().getOpposite());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new NetworkNodeEntity(position, state);
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide() && level.getBlockEntity(position) instanceof NetworkNodeEntity node) {
      try {
        node.place(stack, placer instanceof Player ? placer.getUUID() : null);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (placer instanceof Player player)
          player.displayClientMessage(Component.literal(failure.getMessage()), true);
      }
    }
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof NetworkNodeEntity node))
      return InteractionResult.FAIL;
    try {
      if (node.handle() == null && node.diagnostic().isEmpty()) node.commission(player.getUUID());
      node.validatePlaced();
      String message =
          kind == NodeRegistry.Kind.GATEWAY
              ? "Downstream: "
                  + state.getValue(BlockStateProperties.FACING).getName()
                  + "; upstream: "
                  + state.getValue(BlockStateProperties.FACING).getOpposite().getName()
                  + "; routing not yet active"
              : "Network " + node.handle().id() + "; routing not yet active";
      player.displayClientMessage(Component.literal(message), true);
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(Component.literal(failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
    return List.of();
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState next, boolean moved) {
    if (!state.is(next.getBlock())
        && !level.isClientSide()
        && level.getBlockEntity(position) instanceof NetworkNodeEntity node) {
      Containers.dropItemStack(
          level,
          position.getX() + 0.5,
          position.getY() + 0.5,
          position.getZ() + 0.5,
          node.portableForBreak());
    }
    super.onRemove(state, level, position, next, moved);
  }
}
