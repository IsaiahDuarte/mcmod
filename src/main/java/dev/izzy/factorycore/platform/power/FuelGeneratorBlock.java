package dev.izzy.factorycore.platform.power;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.core.storage.FuelGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Standalone vanilla-fuel FE source. It is not a network node and needs no controller. */
public final class FuelGeneratorBlock extends BaseEntityBlock {
  public static final MapCodec<FuelGeneratorBlock> CODEC = simpleCodec(FuelGeneratorBlock::new);
  public static final BooleanProperty LIT = BlockStateProperties.LIT;

  public FuelGeneratorBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition
            .any()
            .setValue(HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH)
            .setValue(LIT, false));
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(HorizontalDirectionalBlock.FACING, LIT);
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(
            HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new FuelGeneratorEntity(position, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : createTickerHelper(
            type,
            FuelGeneratorContent.ENTITY.get(),
            (l, p, s, generator) -> generator.serverTick());
  }

  /** Using fuel inserts as much of the held stack as the fuel slot accepts. */
  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack held,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (!(level.getBlockEntity(position) instanceof FuelGeneratorEntity generator)
        || !generator.slots().isItemValid(FuelGeneratorEntity.FUEL, held))
      return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
    if (!generator.diagnostic().isEmpty()) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.generator_unsupported"), true);
      return ItemInteractionResult.FAIL;
    }
    var rest = generator.items(net.minecraft.core.Direction.UP).insertItem(0, held.copy(), false);
    int inserted = held.getCount() - rest.getCount();
    if (inserted == 0) return ItemInteractionResult.FAIL;
    if (!player.getAbilities().instabuild) held.shrink(inserted);
    return ItemInteractionResult.CONSUME;
  }

  /** Empty hand reports status; sneaking with an empty hand takes remainders, then fuel. */
  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof FuelGeneratorEntity generator))
      return InteractionResult.FAIL;
    if (!generator.diagnostic().isEmpty()) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.generator_unsupported"), true);
      return InteractionResult.FAIL;
    }
    if (player.isShiftKeyDown()) {
      for (int slot : new int[] {FuelGeneratorEntity.REMAINDER, FuelGeneratorEntity.FUEL}) {
        var stack = generator.slots().getStackInSlot(slot);
        if (stack.isEmpty()) continue;
        var taken = stack.copy();
        player.getInventory().add(taken);
        generator.slots().setStackInSlot(slot, taken);
        return InteractionResult.CONSUME;
      }
      return InteractionResult.PASS;
    }
    var core = generator.generator();
    var reason = generator.blockedReason();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.generator_status",
            core.energy(),
            FuelGenerator.CAPACITY,
            core.burnRemaining(),
            FuelGenerator.MAX_OUTPUT,
            Component.translatable(
                "status.factorycore.generator."
                    + (reason.isEmpty()
                        ? core.status().name().toLowerCase(java.util.Locale.ROOT)
                        : reason))),
        true);
    return InteractionResult.CONSUME;
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState next, boolean moved) {
    if (!state.is(next.getBlock())
        && level.getBlockEntity(position) instanceof FuelGeneratorEntity generator
        && generator.diagnostic().isEmpty()) {
      for (int slot = 0; slot < generator.slots().getSlots(); slot++)
        Containers.dropItemStack(
            level,
            position.getX(),
            position.getY(),
            position.getZ(),
            generator.slots().getStackInSlot(slot).copy());
    }
    super.onRemove(state, level, position, next, moved);
  }
}
