package dev.izzy.factorycore.platform.storage;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Creative placement must consume a physical reference just as survival does. */
public final class EnergyBankItem extends BlockItem {
  public EnergyBankItem(Block block) {
    super(block, new Properties().stacksTo(1));
  }

  @Override
  public void appendHoverText(
      net.minecraft.world.item.ItemStack stack,
      TooltipContext context,
      java.util.List<net.minecraft.network.chat.Component> tooltip,
      net.minecraft.world.item.TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    var tier = ((EnergyBankBlock) getBlock()).tier();
    tooltip.add(
        net.minecraft.network.chat.Component.translatable(
            "tooltip.factorycore.bank", tier.capacity(), tier.rate()));
    tooltip.add(
        net.minecraft.network.chat.Component.translatable("tooltip.factorycore.bank_upgrade"));
  }

  @Override
  public InteractionResult place(BlockPlaceContext context) {
    var result = super.place(context);
    if (result.consumesAction()
        && !context.getLevel().isClientSide()
        && context.getPlayer() != null
        && context.getPlayer().getAbilities().instabuild) context.getItemInHand().shrink(1);
    return result;
  }
}
