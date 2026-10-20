package dev.izzy.factorycore.platform.network;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block that takes part in a network: placing it, changing a neighbor or removing it asks the
 * network to re-validate, unless only a cosmetic property changed. Breaking it drops whatever the
 * device hands back (its lease item, cells, patterns or upgrades). Subclasses only say what is
 * cosmetic and what to drop.
 */
public abstract class NetworkedBlock extends Block {
  protected NetworkedBlock(Properties properties) {
    super(properties);
  }

  /**
   * The single definition of this block's wired ports: true when {@code face} carries a network
   * connection. Cable arms, discovery triggers and the wire reader's ports all derive from it.
   * Default: every face.
   */
  public boolean wiredFace(BlockState state, Direction face) {
    return true;
  }

  /** True when {@code state} is a networked block whose {@code face} is a wired port. */
  public static boolean wired(BlockState state, Direction face) {
    return state.getBlock() instanceof NetworkedBlock block && block.wiredFace(state, face);
  }

  /** True when only cosmetic state changed, so the network need not rebuild (default: lamp). */
  protected boolean cosmeticOnly(BlockState before, BlockState after) {
    return DeviceLamp.onlyLampChanged(before, after);
  }

  /** Items handed back when the block is broken (server only; not on a mere state change). */
  protected List<ItemStack> dropsOnBreak(BlockEntity entity) {
    return List.of();
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos position, BlockState previous, boolean moved) {
    super.onPlace(state, level, position, previous, moved);
    if (!cosmeticOnly(previous, state)) WorldNetworks.changed(level, position);
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
    WorldNetworks.changed(level, position);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState next, boolean moved) {
    if (!cosmeticOnly(state, next)) WorldNetworks.changed(level, position);
    if (!state.is(next.getBlock()) && !level.isClientSide()) {
      var entity = level.getBlockEntity(position);
      if (entity != null)
        for (var drop : dropsOnBreak(entity))
          Containers.dropItemStack(
              level, position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5, drop);
    }
    super.onRemove(state, level, position, next, moved);
  }

  /** Server-side ticker for exactly {@code expected}; null for any other block entity type. */
  @SuppressWarnings("unchecked")
  protected static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> ticker(
      BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
    return expected == actual ? (BlockEntityTicker<A>) ticker : null;
  }
}
