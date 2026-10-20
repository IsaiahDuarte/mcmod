package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

public final class EnergyBankBlock extends Block implements EntityBlock {
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
      var status =
          WorldNetworks.get(((ServerLevel) level).getServer())
              .membership(Positions.of(level, position))
              .status();
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.bank_status",
              bank.getEnergyStored(),
              bank.getMaxEnergyStored(),
              bank.tier().rate(),
              Component.translatable(
                  "status.factorycore." + status.name().toLowerCase(Locale.ROOT))),
          true);
      return InteractionResult.CONSUME;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.bank_fault", failure.getMessage()), true);
      return InteractionResult.FAIL;
    }
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
    return List.of();
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
        && !level.isClientSide()
        && level.getBlockEntity(position) instanceof EnergyBankEntity node) {
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
