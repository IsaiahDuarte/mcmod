package dev.izzy.factorycore.platform.storage;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public final class StorageDriveBlock extends Block implements EntityBlock {
  public StorageDriveBlock(Properties properties) {
    super(properties);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new StorageDriveEntity(position, state);
  }

  @Override
  protected ItemInteractionResult useItemOn(
      ItemStack stack,
      BlockState state,
      Level level,
      BlockPos position,
      Player player,
      InteractionHand hand,
      BlockHitResult hit) {
    if (!(stack.getItem() instanceof CellItem))
      return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
    try {
      var drive = (StorageDriveEntity) level.getBlockEntity(position);
      if (drive == null) return ItemInteractionResult.FAIL;
      int slot = drive.installCell(stack.copyWithCount(1));
      // Even creative installation consumes the physical lease item; its contents have exactly one
      // owner.
      stack.shrink(1);
      player.displayClientMessage(
          Component.literal(
              "Cell installed in slot " + (slot + 1) + "; routing awaits a valid network"),
          true);
      return ItemInteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(Component.literal(failure.getMessage()), true);
      return ItemInteractionResult.FAIL;
    }
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    var drive = (StorageDriveEntity) level.getBlockEntity(position);
    if (drive == null) return InteractionResult.FAIL;
    try {
      if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty()) {
        for (int slot = 0; slot < 4; slot++) {
          if (!drive.cell(slot).isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, drive.removeCell(slot));
            return InteractionResult.CONSUME;
          }
        }
      }
      player.displayClientMessage(
          Component.literal(
              drive.diagnostic().isEmpty()
                  ? "Insert a cell; sneak with an empty main hand to remove a cell"
                  : drive.diagnostic()),
          true);
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(Component.literal(failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide() && level.getBlockEntity(position) instanceof StorageDriveEntity drive)
      drive.restoreRecovery(stack, level.registryAccess());
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
    if (parameters.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
            instanceof StorageDriveEntity drive
        && drive.needsDataRecovery()) {
      // Recovery payload is emitted by onRemove, including replacements that do not invoke loot.
      return List.of();
    }
    return super.getDrops(state, parameters);
  }

  @Override
  protected void onRemove(
      BlockState state, Level level, BlockPos position, BlockState next, boolean moved) {
    if (!state.is(next.getBlock())
        && level.getBlockEntity(position) instanceof StorageDriveEntity drive
        && !level.isClientSide()) {
      for (var cell : drive.ejectForBreak())
        Containers.dropItemStack(
            level, position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5, cell);
    }
    super.onRemove(state, level, position, next, moved);
  }
}
