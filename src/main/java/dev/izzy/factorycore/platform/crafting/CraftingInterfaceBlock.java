package dev.izzy.factorycore.platform.crafting;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Processing pattern holder; its FACING side is the leased machine port. */
public final class CraftingInterfaceBlock extends CraftingUnitBlock {
  public static final MapCodec<CraftingInterfaceBlock> CODEC =
      simpleCodec(CraftingInterfaceBlock::new);

  public CraftingInterfaceBlock(Properties properties) {
    super(properties);
    registerDefaultState(
        stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING);
  }

  /** Faces the clicked machine, so placing against a furnace binds that furnace. */
  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    return defaultBlockState()
        .setValue(BlockStateProperties.FACING, context.getClickedFace().getOpposite());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new CraftingInterfaceEntity(position, state);
  }
}
