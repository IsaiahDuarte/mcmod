package dev.izzy.factorycore.platform.storage;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class CellUpgradeItem extends Item {
  private final int targetLevel;

  public CellUpgradeItem(int targetLevel) {
    super(new Properties());
    this.targetLevel = targetLevel;
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    ItemStack module = player.getItemInHand(hand);
    if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(module);
    if (level.isClientSide()) return InteractionResultHolder.success(module);
    try {
      var upgraded =
          CellOwnership.upgrade(
              player.getOffhandItem(),
              targetLevel,
              DeviceSavedData.get(level.getServer()).registry());
      player.setItemInHand(InteractionHand.OFF_HAND, upgraded);
      if (!player.getAbilities().instabuild) module.shrink(1);
      player.displayClientMessage(
          Component.literal("Cell upgraded; stored resources and reservations preserved"), true);
      return InteractionResultHolder.consume(module);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      player.displayClientMessage(Component.literal(rejected.getMessage()), true);
      return InteractionResultHolder.fail(module);
    }
  }
}
