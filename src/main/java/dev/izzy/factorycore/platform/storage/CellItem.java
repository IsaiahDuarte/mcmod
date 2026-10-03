package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class CellItem extends Item {
  private final CellTier tier;

  public CellItem(CellTier tier) {
    super(new Properties().stacksTo(1));
    this.tier = tier;
  }

  public CellTier tier() {
    return tier;
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
    lines.add(
        Component.literal(
            "Capacity: "
                + (tier.infinite() ? "Infinite (technical limits apply)" : tier.capacity())
                + (tier.kind() == dev.izzy.factorycore.core.resource.ResourceKind.ITEM
                    ? " items"
                    : " mB")));
    try {
      var handle = CellHandleData.read(stack);
      lines.add(
          Component.literal(
              handle == null ? "Unbound empty cell" : "Backing: " + handle.backing()));
    } catch (IllegalArgumentException invalid) {
      lines.add(Component.literal("Invalid cell data: " + invalid.getMessage()));
    }
  }
}
