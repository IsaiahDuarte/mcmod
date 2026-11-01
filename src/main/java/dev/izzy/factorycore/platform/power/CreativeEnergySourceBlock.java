package dev.izzy.factorycore.platform.power;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Q26 (QD13): a creative-tab-only FE source with no recipe. Like the Fuel Generator it is not a
 * network node; it offers unlimited FE to every face and pushes up to {@link
 * CreativeEnergySourceEntity#PUSH} FE per tick into each neighbour that accepts energy.
 */
public final class CreativeEnergySourceBlock extends BaseEntityBlock {
  public static final MapCodec<CreativeEnergySourceBlock> CODEC =
      simpleCodec(CreativeEnergySourceBlock::new);

  public CreativeEnergySourceBlock(Properties properties) {
    super(properties);
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new CreativeEnergySourceEntity(position, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : createTickerHelper(
            type,
            FuelGeneratorContent.CREATIVE_ENTITY.get(),
            (l, p, s, source) -> source.serverTick());
  }
}
