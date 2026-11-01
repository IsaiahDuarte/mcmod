package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Rechargeable handheld terminal: bind it to an access point, then use it within range. Q26 (QD13):
 * the creative one (creative tab only, no recipe) needs no charge and reaches its access point from
 * anywhere in the same dimension.
 */
public final class WirelessTerminalItem extends Item {
  public static final int CAPACITY = 20_000;
  public static final int CHARGE_RATE = 1_000;

  private final boolean creative;

  public WirelessTerminalItem() {
    this(false);
  }

  public WirelessTerminalItem(boolean creative) {
    super(
        creative
            ? new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC)
            : new Item.Properties().stacksTo(1));
    this.creative = creative;
  }

  /** Either Wireless Terminal. */
  public static boolean handheld(ItemStack stack) {
    return stack.getItem() instanceof WirelessTerminalItem;
  }

  /** The creative Wireless Terminal: no charge, no range limit in its dimension. */
  public static boolean creative(ItemStack stack) {
    return stack.getItem() instanceof WirelessTerminalItem item && item.creative;
  }

  /** Using the handheld on an access point binds it there. */
  @Override
  public InteractionResult useOn(UseOnContext context) {
    var level = context.getLevel();
    if (!(level.getBlockEntity(context.getClickedPos()) instanceof AccessPointEntity))
      return InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    context
        .getItemInHand()
        .set(
            WirelessContent.BINDING.get(),
            new WirelessContent.Binding(
                level.dimension().location().toString(), context.getClickedPos().asLong()));
    if (context.getPlayer() != null)
      context
          .getPlayer()
          .displayClientMessage(Component.translatable("message.factorycore.wireless_bound"), true);
    return InteractionResult.CONSUME;
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    if (level.isClientSide()) return InteractionResultHolder.success(stack);
    if (!(player instanceof ServerPlayer server)) return InteractionResultHolder.fail(stack);
    var reason = open(server, hand);
    if (!reason.isEmpty()) {
      player.displayClientMessage(
          Component.translatable("message.factorycore.wireless_" + reason), true);
      return InteractionResultHolder.fail(stack);
    }
    return InteractionResultHolder.consume(stack);
  }

  /** Opens the bound access point's terminal; returns an empty string or a refusal reason. */
  public static String open(ServerPlayer player, InteractionHand hand) {
    return open(player, held -> held.getItemInHand(hand));
  }

  /**
   * Q14: opens the one charged, bound handheld in the main inventory or offhand (Q110: or an
   * accessory slot an optional integration provides, see {@link HandheldSlots}); refuses with
   * "no_handheld" when there is none and "several_handhelds" when there are more, rather than
   * guessing. The session then needs the handheld to stay in that slot.
   */
  public static String openFromInventory(ServerPlayer player) {
    var inventory = player.getInventory();
    int found = -1;
    for (int slot = 0; slot <= Inventory.SLOT_OFFHAND; slot++) {
      if (slot >= inventory.items.size() && slot < Inventory.SLOT_OFFHAND) continue;
      if (!ready(inventory.getItem(slot))) continue;
      if (found >= 0) return "several_handhelds";
      found = slot;
    }
    var worn = HandheldSlots.find(player, WirelessTerminalItem::ready);
    if (worn.size() + (found >= 0 ? 1 : 0) > 1) return "several_handhelds";
    if (worn.size() == 1) return open(player, worn.getFirst());
    if (found < 0) return "no_handheld";
    int slot = found;
    return open(player, held -> held.getInventory().getItem(slot));
  }

  /** A bound handheld with charge for at least one action (Q26: or a bound creative one). */
  private static boolean ready(ItemStack stack) {
    if (!handheld(stack) || stack.get(WirelessContent.BINDING.get()) == null) return false;
    if (creative(stack)) return true;
    var energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
    return energy != null && energy.getEnergyStored() >= WirelessAccess.ACTION_COST;
  }

  /**
   * Q99 (M127): whether the player carries a charged, bound handheld in the main inventory or
   * offhand, so a finished job is shown as a toast rather than a chat line.
   */
  public static boolean carried(ServerPlayer player) {
    var inventory = player.getInventory();
    for (var stack : inventory.items) if (ready(stack)) return true;
    return ready(inventory.offhand.getFirst());
  }

  private static String open(
      ServerPlayer player, java.util.function.Function<ServerPlayer, ItemStack> handheld) {
    var binding = handheld.apply(player).get(WirelessContent.BINDING.get());
    if (binding == null) return "unbound";
    if (!player.level().dimension().location().toString().equals(binding.dimension()))
      return "out_of_range";
    var pos = BlockPos.of(binding.position());
    var level = player.serverLevel();
    if (Loaded.chunk(level, pos) == null
        || !(level.getBlockEntity(pos) instanceof AccessPointEntity point)) return "missing";
    if (point.energy() < point.tickCost()) return "no_power";
    var access = new WirelessAccess(point, handheld, creative(handheld.apply(player)));
    if (!access.canUse(player)) return "out_of_range";
    return TerminalMenu.open(
            player, access, Component.translatable("item.factorycore.wireless_terminal"))
        ? ""
        : "out_of_range";
  }

  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
    if (creative)
      lines.add(
          Component.translatable("tooltip.factorycore.handheld_creative")
              .withStyle(ChatFormatting.LIGHT_PURPLE));
    else
      lines.add(
          Component.translatable(
                  "tooltip.factorycore.handheld_energy",
                  stack.getOrDefault(WirelessContent.ENERGY.get(), 0),
                  CAPACITY)
              .withStyle(ChatFormatting.GRAY));
    lines.add(
        Component.translatable(
                stack.get(WirelessContent.BINDING.get()) == null
                    ? "tooltip.factorycore.handheld_unbound"
                    : "tooltip.factorycore.handheld_bound")
            .withStyle(ChatFormatting.DARK_GRAY));
  }
}
