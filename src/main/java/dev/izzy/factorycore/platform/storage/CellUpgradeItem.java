package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.EnergyBankOwnership;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class CellUpgradeItem extends Item {
  private final int targetLevel;

  public CellUpgradeItem(int targetLevel) {
    super(new Properties());
    this.targetLevel = targetLevel;
  }

  /** The tier level this module upgrades to: 1 Expanded, 2 Advanced, 3 Infinite. */
  public int targetLevel() {
    return targetLevel;
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    ItemStack module = player.getItemInHand(hand);
    if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(module);
    if (level.isClientSide()) return InteractionResultHolder.success(module);
    try {
      var offhand = player.getOffhandItem();
      ItemStack upgraded;
      boolean bank =
          offhand.getItem() instanceof BlockItem blockItem
              && blockItem.getBlock() instanceof EnergyBankBlock;
      if (bank) {
        if (offhand.getCount() != 1)
          throw new IllegalArgumentException("Hold one bank in the offhand");
        var block = (EnergyBankBlock) ((BlockItem) offhand.getItem()).getBlock();
        var data = offhand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var handle = EnergyBankHandleData.read(data.get(EnergyBankHandleData.FIELD));
        var tier =
            new EnergyBankOwnership(DeviceSavedData.get(level.getServer()).registry())
                .upgrade(handle, block.tier(), targetLevel);
        upgraded = new ItemStack(EnergyBankContent.item(tier));
        upgraded.applyComponents(offhand.getComponentsPatch());
      } else {
        upgraded =
            CellOwnership.upgrade(
                player.getOffhandItem(),
                targetLevel,
                DeviceSavedData.get(level.getServer()).registry());
      }
      player.setItemInHand(InteractionHand.OFF_HAND, upgraded);
      if (!player.getAbilities().instabuild) module.shrink(1);
      player.displayClientMessage(
          Component.translatable("message.factorycore.storage_upgraded"), true);
      return InteractionResultHolder.consume(module);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      player.displayClientMessage(Component.literal(rejected.getMessage()), true);
      return InteractionResultHolder.fail(module);
    }
  }
}
