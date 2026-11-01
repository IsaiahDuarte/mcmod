package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Q32 (QD8): a handheld that holds one item cell and opens a storage screen on it with no network
 * ({@link PortableStorageMenu}). The first player to use it owns it; nobody else may open it. It
 * runs on FE like the Wireless Terminal (same battery and charging).
 */
public final class PortableStorageItem extends Item {
  public PortableStorageItem() {
    super(new Properties().stacksTo(1));
  }

  /** True when {@code player} owns {@code stack} or nobody does yet. */
  public static boolean mayUse(ItemStack stack, Player player) {
    var owner = stack.get(PortableContent.OWNER.get());
    return owner == null || owner.equals(player.getUUID());
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    if (level.isClientSide()) return InteractionResultHolder.success(stack);
    if (!mayUse(stack, player)) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.portable_storage.not_owner"), true);
      return InteractionResultHolder.fail(stack);
    }
    if (stack.get(PortableContent.OWNER.get()) == null)
      stack.set(PortableContent.OWNER.get(), player.getUUID());
    PortableStorageMenu.open((ServerPlayer) player, hand);
    return InteractionResultHolder.consume(stack);
  }

  @Override
  public boolean isBarVisible(ItemStack stack) {
    return true;
  }

  @Override
  public int getBarWidth(ItemStack stack) {
    return Math.round(
        13f * stack.getOrDefault(WirelessContent.ENERGY.get(), 0) / WirelessTerminalItem.CAPACITY);
  }

  @Override
  public int getBarColor(ItemStack stack) {
    return 0x36C5F0;
  }

  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(
        Component.translatable(
                "tooltip.factorycore.portable_storage_energy",
                stack.getOrDefault(WirelessContent.ENERGY.get(), 0),
                WirelessTerminalItem.CAPACITY,
                PortableStorageMenu.COST)
            .withStyle(ChatFormatting.GRAY));
    var contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
    var cell = contents == null ? ItemStack.EMPTY : contents.copyOne();
    lines.add(
        (cell.isEmpty()
                ? Component.translatable("tooltip.factorycore.portable_storage_empty")
                : Component.translatable(
                    "tooltip.factorycore.portable_storage_cell", cell.getHoverName()))
            .withStyle(ChatFormatting.DARK_GRAY));
  }
}
