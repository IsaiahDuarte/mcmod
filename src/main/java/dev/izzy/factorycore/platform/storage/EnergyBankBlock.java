package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class EnergyBankBlock extends NetworkedBlock implements EntityBlock {
  private final EnergyBankTier tier;

  public EnergyBankBlock(Properties properties, EnergyBankTier tier) {
    super(properties);
    this.tier = tier;
  }

  public EnergyBankTier tier() {
    return tier;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new EnergyBankEntity(position, state);
  }

  @Override
  public void setPlacedBy(
      Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, position, state, placer, stack);
    if (!level.isClientSide() && level.getBlockEntity(position) instanceof EnergyBankEntity node) {
      try {
        node.place(stack);
        if (placer instanceof Player player && !node.diagnostic().isEmpty())
          player.displayClientMessage(
              Component.translatable("message.factorycore.bank_fault", node.diagnostic()), true);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        if (placer instanceof Player player)
          player.displayClientMessage(
              Component.translatable("message.factorycore.bank_fault", failure.getMessage()), true);
      }
    }
  }

  @Override
  protected InteractionResult useWithoutItem(
      BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(level.getBlockEntity(position) instanceof EnergyBankEntity bank))
      return InteractionResult.FAIL;
    try {
      if (bank.handle() == null && bank.diagnostic().isEmpty())
        bank.place(new ItemStack(EnergyBankContent.item(tier)));
      bank.validatePlaced();
      // Q20: the screen shows the charge, flow and network state, and takes upgrade modules.
      if (player instanceof ServerPlayer viewer) EnergyBankMenu.open(viewer, position);
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.bank_fault", failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  public boolean dropsLeasedItem() {
    return true;
  }

  @Override
  protected List<ItemStack> dropsOnBreak(BlockEntity blockEntity) {
    return blockEntity instanceof EnergyBankEntity bank
        ? List.of(bank.portableForBreak())
        : List.of();
  }
}
