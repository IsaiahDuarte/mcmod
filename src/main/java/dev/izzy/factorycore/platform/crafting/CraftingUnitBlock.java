package dev.izzy.factorycore.platform.crafting;

import com.mojang.serialization.MapCodec;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Wired holder of up to nine patterns. Pattern items are ordinary, physically owned. */
public class CraftingUnitBlock extends BaseEntityBlock {
  public static final MapCodec<CraftingUnitBlock> CODEC = simpleCodec(CraftingUnitBlock::new);

  public CraftingUnitBlock(Properties properties) {
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
    return new CraftingUnitEntity(position, state);
  }

  /** Using a pattern inserts it into the first free slot. */
  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack held,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (!(held.is(CraftingContent.CRAFTING_PATTERN.get())
            || held.is(CraftingContent.PROCESSING_PATTERN.get()))
        || !(level.getBlockEntity(position) instanceof PatternHolderEntity unit))
      return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
    for (int slot = 0; slot < PatternHolderEntity.SLOTS; slot++) {
      var rest = unit.patterns().insertItem(slot, held.copyWithCount(1), false);
      if (rest.isEmpty()) {
        held.shrink(1);
        return ItemInteractionResult.CONSUME;
      }
    }
    player.displayClientMessage(
        Component.translatable(
            unit.patterns().isItemValid(0, held)
                ? "message.factorycore.crafting_unit_full"
                : "message.factorycore.crafting_unit_invalid"),
        true);
    return ItemInteractionResult.FAIL;
  }

  /** Empty hand reports patterns and network state; sneaking takes the last pattern back. */
  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof PatternHolderEntity unit))
      return InteractionResult.FAIL;
    if (player.isShiftKeyDown()) {
      for (int slot = PatternHolderEntity.SLOTS - 1; slot >= 0; slot--) {
        var taken = unit.patterns().extractItem(slot, 1, false);
        if (!taken.isEmpty()) {
          if (!player.getInventory().add(taken))
            Containers.dropItemStack(
                level, position.getX() + 0.5, position.getY() + 1, position.getZ() + 0.5, taken);
          return InteractionResult.CONSUME;
        }
      }
      return InteractionResult.PASS;
    }
    var status =
        WorldNetworks.get(((ServerLevel) level).getServer()).membership(unit.origin()).status();
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.crafting_unit_status",
            unit.patternCount(),
            PatternHolderEntity.SLOTS,
            Component.translatable("status.factorycore." + status.name().toLowerCase(Locale.ROOT))),
        true);
    return InteractionResult.CONSUME;
  }

  @Override
  protected void onPlace(
      BlockState state, Level level, BlockPos position, BlockState previous, boolean moved) {
    super.onPlace(state, level, position, previous, moved);
    WorldNetworks.changed(level, position);
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
    WorldNetworks.changed(level, position);
    if (!state.is(next.getBlock())
        && level.getBlockEntity(position) instanceof PatternHolderEntity unit
        && unit.diagnostic().isEmpty())
      for (int slot = 0; slot < PatternHolderEntity.SLOTS; slot++)
        Containers.dropItemStack(
            level,
            position.getX(),
            position.getY(),
            position.getZ(),
            unit.patterns().getStackInSlot(slot).copy());
    super.onRemove(state, level, position, next, moved);
  }
}
