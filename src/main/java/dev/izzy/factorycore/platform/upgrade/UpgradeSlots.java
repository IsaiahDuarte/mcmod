package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.Actors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Q19: the upgrade slot every upgradeable device's screen shows. It is not a real container slot;
 * the installed tier is synced in one data slot and a click is menu button {@link #BUTTON}. With an
 * upgrade on the cursor the click installs it (the item is used up only when the device accepts it,
 * as for using the item on the block); with an empty cursor it takes the top upgrade out onto the
 * cursor, Overdrive before Throughput. Both go through the device's {@link ThroughputUpgradeable}
 * contract, so authority, the step order and ADR 0054's pause and rate rules are the same as for
 * the item.
 *
 * <p>Q113: with an Upgrade Toolbelt in the inventory, an empty-cursor click installs the device's
 * next upgrade from the toolbelt when it holds one (the slot shows it as an offer). A right-click
 * ({@link #REMOVE}) always takes the top upgrade out, and a removed upgrade goes into the toolbelt
 * when it has room.
 */
public final class UpgradeSlots {
  /** Menu button for a click on the upgrade slot (clear of the 200 and 300 ranges). */
  public static final int BUTTON = 400;

  /** Q113: menu button for a right-click, which only removes. */
  public static final int REMOVE = 401;

  /** Data slot value when the device has no upgrade slot (a gateway without a link). */
  public static final int NONE = -1;

  private static final int LINK = 8;

  private UpgradeSlots() {}

  /**
   * The synced slot state: the installed tier's ordinal, plus {@code 8} for a link, or {@link
   * #NONE}. {@code device} is null on the client, which then only receives values.
   */
  public static DataSlot state(java.util.function.Supplier<ThroughputUpgradeable> device) {
    return new DataSlot() {
      private int value = NONE;

      @Override
      public int get() {
        var target = device.get();
        if (target == null) return value;
        try {
          return target.tier().ordinal() + (target.linkUpgrade() ? LINK : 0);
        } catch (IllegalStateException absent) {
          return NONE;
        }
      }

      @Override
      public void set(int next) {
        value = next;
      }
    };
  }

  /** Whether the synced state shows a slot at all. */
  public static boolean shown(int state) {
    return state >= 0;
  }

  public static ThroughputTier tier(int state) {
    return ThroughputTier.values()[Math.max(0, state) % LINK];
  }

  public static boolean link(int state) {
    return state >= LINK;
  }

  /** Whether a menu button is one of the upgrade slot's. */
  public static boolean requested(int button) {
    return button == BUTTON || button == REMOVE;
  }

  /** The upgrade a device with slot state {@code state} takes next, or null when it is full. */
  public static ThroughputTier next(int state) {
    if (!shown(state)) return null;
    var tier = tier(state);
    if (tier == ThroughputTier.ENDGAME) return null;
    return link(state) ? ThroughputTier.ENDGAME : ThroughputTier.values()[tier.ordinal() + 1];
  }

  /** The player's first Upgrade Toolbelt (main inventory, then off hand), or empty. */
  public static ItemStack belt(net.minecraft.world.entity.player.Inventory inventory) {
    for (var stack : inventory.items) if (stack.getItem() instanceof UpgradeBeltItem) return stack;
    for (var stack : inventory.offhand)
      if (stack.getItem() instanceof UpgradeBeltItem) return stack;
    return ItemStack.EMPTY;
  }

  /** Q113: the upgrade the slot offers from the player's toolbelt, or null. Client and server. */
  public static ThroughputTier offer(
      int state, net.minecraft.world.entity.player.Inventory inventory) {
    var next = next(state);
    var belt = belt(inventory);
    return next != null && !belt.isEmpty() && UpgradeBeltItem.contents(belt).count(next) > 0
        ? next
        : null;
  }

  public static boolean click(
      ServerPlayer player, AbstractContainerMenu menu, ThroughputUpgradeable device) {
    return click(player, menu, device, BUTTON);
  }

  /**
   * Handles a click ({@link #BUTTON}) or right-click ({@link #REMOVE}) on the slot for {@code
   * player}, whose menu {@code menu} shows {@code device}. Returns whether the device changed.
   */
  public static boolean click(
      ServerPlayer player, AbstractContainerMenu menu, ThroughputUpgradeable device, int button) {
    var carried = menu.getCarried();
    if (carried.isEmpty() && button == BUTTON) {
      var offered = offer(state(() -> device).get(), player.getInventory());
      if (offered != null) return fromBelt(player, menu, device, offered);
    }
    // A change can close the screen (a port's network re-validates), which returns the cursor to
    // the inventory. So the upgrade leaves the cursor before the change and goes back only if the
    // device refuses it, and a removed upgrade goes to the inventory when the screen has closed.
    ItemStack taken = ItemStack.EMPTY;
    try {
      if (carried.getItem() instanceof ThroughputUpgradeItem upgrade) {
        if (!player.getAbilities().instabuild) {
          taken = carried.split(1);
          menu.setCarried(carried);
        }
        if (!device.upgrade(Actors.of(player), upgrade.tier())) {
          giveBack(player, menu, taken);
          return denied(player);
        }
        taken = ItemStack.EMPTY;
        player.displayClientMessage(
            Component.translatable(
                "message.factorycore.throughput_installed", device.throughputStatus(player.server)),
            true);
      } else if (carried.isEmpty()) {
        var removed = device.removeUpgrade(Actors.of(player));
        if (removed == null) return denied(player);
        if (!intoBelt(player, removed))
          giveBack(player, menu, new ItemStack(ThroughputContent.item(removed)));
        player.displayClientMessage(
            Component.translatable(
                "message.factorycore.throughput_removed",
                ThroughputContent.name(removed),
                device.throughputStatus(player.server)),
            true);
      } else {
        player.displayClientMessage(
            Component.translatable("message.factorycore.throughput_slot_accepts"), true);
        return false;
      }
      menu.broadcastChanges();
      return true;
    } catch (IllegalStateException | IllegalArgumentException | ArithmeticException rejected) {
      giveBack(player, menu, taken);
      player.displayClientMessage(refusal(device, rejected), true);
      return false;
    }
  }

  /**
   * Q113: installs {@code tier} from the toolbelt; the toolbelt pays only if the device accepts.
   */
  private static boolean fromBelt(
      ServerPlayer player,
      AbstractContainerMenu menu,
      ThroughputUpgradeable device,
      ThroughputTier tier) {
    try {
      if (!device.upgrade(Actors.of(player), tier)) return denied(player);
    } catch (IllegalStateException | IllegalArgumentException | ArithmeticException rejected) {
      player.displayClientMessage(refusal(device, rejected), true);
      return false;
    }
    var belt = belt(player.getInventory());
    if (!player.getAbilities().instabuild && !belt.isEmpty())
      belt.set(ThroughputContent.BELT.get(), UpgradeBeltItem.contents(belt).add(tier, -1));
    player.displayClientMessage(
        Component.translatable(
            "message.factorycore.throughput_installed", device.throughputStatus(player.server)),
        true);
    menu.broadcastChanges();
    return true;
  }

  /** Q113: puts a removed upgrade into the player's toolbelt if it has room. */
  private static boolean intoBelt(ServerPlayer player, ThroughputTier tier) {
    var belt = belt(player.getInventory());
    if (belt.isEmpty()) return false;
    var contents = UpgradeBeltItem.contents(belt);
    if (contents.count(tier) >= UpgradeBelt.MAX) return false;
    belt.set(ThroughputContent.BELT.get(), contents.add(tier, 1));
    return true;
  }

  /** Puts {@code stack} on the cursor while {@code menu} is still open, else in the inventory. */
  private static void giveBack(ServerPlayer player, AbstractContainerMenu menu, ItemStack stack) {
    if (stack.isEmpty()) return;
    if (player.containerMenu == menu && menu.getCarried().isEmpty()) menu.setCarried(stack);
    else if (player.containerMenu == menu
        && ItemStack.isSameItemSameComponents(menu.getCarried(), stack))
      menu.getCarried().grow(stack.getCount());
    else player.getInventory().placeItemBackInInventory(stack);
  }

  /** The player-facing reason an install or removal was refused. */
  static Component refusal(ThroughputUpgradeable device, RuntimeException rejected) {
    String reason = rejected.getMessage() == null ? "" : rejected.getMessage();
    if (!reason.startsWith("upgrade_"))
      return Component.translatable("message.factorycore.throughput_fault", reason);
    ThroughputTier tier;
    try {
      tier = device.tier();
    } catch (IllegalStateException absent) {
      tier = ThroughputTier.BASE;
    }
    return Component.translatable(
        "message.factorycore.throughput_" + reason.substring("upgrade_".length()),
        ThroughputContent.name(tier));
  }

  private static boolean denied(ServerPlayer player) {
    player.displayClientMessage(
        Component.translatable("message.factorycore.throughput_denied"), true);
    return false;
  }
}
