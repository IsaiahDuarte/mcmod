package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The Wireless Link upgrade (ADR 0061): used on a gateway's upstream or downstream face, it
 * installs a link on that side. Consumed only when the install succeeds, and never in creative.
 */
public final class LinkItem extends Item {
  public LinkItem() {
    super(new Item.Properties());
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var level = context.getLevel();
    if (!(level.getBlockEntity(context.getClickedPos()) instanceof NetworkNodeEntity gateway)
        || gateway.kind() != NodeRegistry.Kind.GATEWAY) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
    if (!LinkModule.mayConfigure(Actors.of(player), gateway, null)) {
      player.displayClientMessage(Component.translatable("message.factorycore.link_denied"), true);
      return InteractionResult.FAIL;
    }
    try {
      gateway.installLink(
          context.getClickedFace(), player.getUUID(), LinkModule.recovery(context.getItemInHand()));
    } catch (IllegalStateException refused) {
      player.displayClientMessage(
          Component.translatable("message.factorycore." + refused.getMessage()), true);
      return InteractionResult.FAIL;
    }
    if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
    player.displayClientMessage(Component.translatable("message.factorycore.link_installed"), true);
    return InteractionResult.CONSUME;
  }
}
