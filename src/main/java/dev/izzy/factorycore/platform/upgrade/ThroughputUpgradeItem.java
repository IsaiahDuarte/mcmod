package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.wireless.LinkEndpointBlock;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Use on a placed device to install it. The device validates authority and the tier step before
 * changing anything; the item is consumed only after the device accepted the upgrade, so a denied
 * or inapplicable use costs nothing (ADR 0054).
 */
public final class ThroughputUpgradeItem extends Item {
  private final ThroughputTier tier;

  public ThroughputUpgradeItem(ThroughputTier tier) {
    super(new Properties());
    if (tier == ThroughputTier.BASE) throw new IllegalArgumentException("Base is not an upgrade");
    this.tier = tier;
  }

  public ThroughputTier tier() {
    return tier;
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var level = context.getLevel();
    var entity = level.getBlockEntity(context.getClickedPos());
    if (!(entity instanceof ConnectorEntity
        || entity instanceof BufferedInterfaceEntity
        || entity instanceof LinkEndpointEntity)) return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
    var actor = Actors.of(player);
    try {
      Component status;
      switch (entity) {
        case ConnectorEntity port -> {
          if (port.installThroughput(actor, tier) != NetworkAuthority.Decision.ALLOWED)
            return denied(player);
          status = ThroughputStatus.port(player.server, port);
        }
        case BufferedInterfaceEntity buffer -> {
          buffer.installThroughput(actor, tier);
          status = ThroughputStatus.buffer(player.server, buffer);
        }
        case LinkEndpointEntity link -> {
          if (!LinkEndpointBlock.mayConfigure(player, link)) return denied(player);
          link.install(tier);
          status = ThroughputStatus.link(player.server, link);
        }
        default -> {
          return InteractionResult.PASS;
        }
      }
      if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
      player.displayClientMessage(
          Component.translatable("message.factorycore.throughput_installed", status), false);
      return InteractionResult.CONSUME;
    } catch (IllegalStateException | IllegalArgumentException | ArithmeticException rejected) {
      String reason = rejected.getMessage() == null ? "" : rejected.getMessage();
      player.displayClientMessage(
          reason.startsWith("upgrade_")
              ? Component.translatable(
                  "message.factorycore.throughput_" + reason.substring("upgrade_".length()),
                  ThroughputContent.name(currentTier(entity)))
              : Component.translatable("message.factorycore.throughput_fault", reason),
          true);
      return InteractionResult.FAIL;
    }
  }

  private static ThroughputTier currentTier(Object entity) {
    return switch (entity) {
      case ConnectorEntity port -> port.tier();
      case BufferedInterfaceEntity buffer -> buffer.tier();
      case LinkEndpointEntity link -> link.tier();
      default -> ThroughputTier.BASE;
    };
  }

  private static InteractionResult denied(ServerPlayer player) {
    player.displayClientMessage(
        Component.translatable("message.factorycore.throughput_denied"), true);
    return InteractionResult.FAIL;
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, context, tooltip, flag);
    String key = tier.name().toLowerCase(Locale.ROOT);
    tooltip.add(Component.translatable("tooltip.factorycore.throughput." + key));
    tooltip.add(Component.translatable("tooltip.factorycore.throughput.install"));
    tooltip.add(Component.translatable("tooltip.factorycore.throughput.fair"));
  }
}
