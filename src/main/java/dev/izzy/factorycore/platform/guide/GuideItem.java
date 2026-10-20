package dev.izzy.factorycore.platform.guide;

import dev.izzy.factorycore.platform.client.GuideScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Opens the read-only guide on the client; the server does nothing. */
public final class GuideItem extends Item {
  public GuideItem() {
    super(new Item.Properties().stacksTo(1));
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    // The client class is only resolved on the client branch.
    if (level.isClientSide()) GuideScreen.open();
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
  }
}
