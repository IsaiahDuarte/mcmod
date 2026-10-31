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
 */
public final class UpgradeSlots {
  /** Menu button for a click on the upgrade slot (clear of the 200 and 300 ranges). */
  public static final int BUTTON = 400;

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

  /**
   * Handles a click on the slot for {@code player}, whose menu {@code menu} shows {@code device}.
   * Returns whether the device changed.
   */
  public static boolean click(
      ServerPlayer player, AbstractContainerMenu menu, ThroughputUpgradeable device) {
    var carried = menu.getCarried();
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
