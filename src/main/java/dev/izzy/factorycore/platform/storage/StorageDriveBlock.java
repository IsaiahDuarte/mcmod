package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public final class StorageDriveBlock extends NetworkedBlock implements EntityBlock {
  /**
   * Presentation-only bay lamps (Q72: empty, online, near full, full or offline), mirrored from the
   * entity's four lease slots and their ledgers. Gameplay always reads the entity; these properties
   * never decide installation or routing.
   */
  public static final List<EnumProperty<DriveBay>> BAYS =
      List.of(
          EnumProperty.create("bay_1", DriveBay.class),
          EnumProperty.create("bay_2", DriveBay.class),
          EnumProperty.create("bay_3", DriveBay.class),
          EnumProperty.create("bay_4", DriveBay.class));

  public StorageDriveBlock(Properties properties) {
    super(properties);
    var state = stateDefinition.any();
    for (var bay : BAYS) state = state.setValue(bay, DriveBay.EMPTY);
    registerDefaultState(state);
  }

  /** Refreshes the bay lamps once per {@link DeviceLamp#PERIOD}, as fill and network change. */
  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
      Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide()
        ? null
        : ticker(
            type,
            StorageContent.DRIVE_ENTITY.get(),
            (tickLevel, position, tickState, drive) -> {
              if (DeviceLamp.due(tickLevel, position)) drive.syncBays();
            });
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    BAYS.forEach(builder::add);
  }

  /** True when {@code next} only changes bay lamps of the same drive: no topology work or drops. */
  static boolean onlyBaysChanged(BlockState previous, BlockState next) {
    if (previous.getBlock() != next.getBlock()) return false;
    var normalized = previous;
    for (var bay : BAYS) normalized = normalized.setValue(bay, next.getValue(bay));
    return normalized == next;
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
          Component.translatable("message.factorycore.drive_installed", slot + 1), true);
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
      if (player instanceof ServerPlayer server) DriveMenu.open(server, drive);
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
  protected List<ItemStack> dropsOnBreak(BlockEntity blockEntity) {
    return blockEntity instanceof StorageDriveEntity drive ? drive.ejectForBreak() : List.of();
  }

  @Override
  protected boolean cosmeticOnly(BlockState before, BlockState after) {
    return onlyBaysChanged(before, after);
  }
}
