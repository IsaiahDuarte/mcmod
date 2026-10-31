package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Q113: the Upgrade Toolbelt. Click it with upgrades on the cursor to put them in; right-click it
 * with an empty cursor to take one out (Overdrive first). While it is in the inventory, an upgrade
 * slot offers what it holds ({@link UpgradeSlots}).
 */
public final class UpgradeBeltItem extends Item {
  public UpgradeBeltItem() {
    super(new Properties().stacksTo(1));
  }

  public static UpgradeBelt contents(ItemStack belt) {
    return belt.getOrDefault(ThroughputContent.BELT.get(), UpgradeBelt.EMPTY);
  }

  @Override
  public boolean overrideOtherStackedOnMe(
      ItemStack belt,
      ItemStack other,
      Slot slot,
      ClickAction action,
      Player player,
      SlotAccess cursor) {
    if (!slot.allowModification(player)) return false;
    var contents = contents(belt);
    if (other.getItem() instanceof ThroughputUpgradeItem upgrade) {
      int room = UpgradeBelt.MAX - contents.count(upgrade.tier());
      int moved = Math.min(room, other.getCount());
      if (moved > 0) {
        belt.set(ThroughputContent.BELT.get(), contents.add(upgrade.tier(), moved));
        other.shrink(moved);
      }
      return true;
    }
    if (other.isEmpty() && action == ClickAction.SECONDARY && !contents.isEmpty()) {
      var tier =
          contents.count(ThroughputTier.ENDGAME) > 0
              ? ThroughputTier.ENDGAME
              : ThroughputTier.ADVANCED;
      belt.set(ThroughputContent.BELT.get(), contents.add(tier, -1));
      cursor.set(new ItemStack(ThroughputContent.item(tier)));
      return true;
    }
    return false;
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    var contents = contents(stack);
    lines.add(
        Component.translatable(
            "tooltip.factorycore.upgrade_toolbelt", contents.throughput(), contents.overdrive()));
    lines.add(Component.translatable("tooltip.factorycore.upgrade_toolbelt.help"));
  }
}
