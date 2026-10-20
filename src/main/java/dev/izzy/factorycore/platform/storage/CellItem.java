package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
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
        tier.infinite()
            ? Component.translatable("tooltip.factorycore.cell_capacity_infinite")
            : Component.translatable(
                tier.kind() == ResourceKind.ITEM
                    ? "tooltip.factorycore.cell_capacity_items"
                    : "tooltip.factorycore.cell_capacity_fluid",
                tier.capacity()));
    try {
      var handle = CellHandleData.read(stack);
      lines.add(
          handle == null
              ? Component.translatable("tooltip.factorycore.cell_unbound")
              : Component.translatable(
                  "tooltip.factorycore.cell_backing", handle.backing().toString()));
    } catch (IllegalArgumentException invalid) {
      lines.add(Component.translatable("tooltip.factorycore.cell_invalid", invalid.getMessage()));
    }
  }
}
