package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.Actors;
import java.util.List;
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

  /**
   * Q15: installs before the block's own use, so devices that open a screen or report status when
   * used (Access Point, Crafter) take the upgrade without sneaking.
   */
  @Override
  public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
    return context.getLevel().getBlockEntity(context.getClickedPos())
            instanceof ThroughputUpgradeable
        ? useOn(context)
        : InteractionResult.PASS;
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var level = context.getLevel();
    if (!(level.getBlockEntity(context.getClickedPos()) instanceof ThroughputUpgradeable device))
      return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
    try {
      if (!device.upgrade(Actors.of(player), tier)) return denied(player);
      if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.throughput_installed", device.throughputStatus(player.server)),
          false);
      return InteractionResult.CONSUME;
    } catch (IllegalStateException | IllegalArgumentException | ArithmeticException rejected) {
      player.displayClientMessage(UpgradeSlots.refusal(device, rejected), true);
      return InteractionResult.FAIL;
    }
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
    tooltip.add(ThroughputContent.describe(tier));
    // Q19 (M88): which devices take this upgrade.
    tooltip.add(
        Component.translatable(
            "tooltip.factorycore.throughput.fits."
                + tier.name().toLowerCase(java.util.Locale.ROOT)));
    tooltip.add(Component.translatable("tooltip.factorycore.throughput.install"));
    tooltip.add(Component.translatable("tooltip.factorycore.throughput.fair"));
  }
}
