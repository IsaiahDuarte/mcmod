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

  /** Q72: the stored contents a cell had when it left a drive, drawn as icons. */
  @Override
  public java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> getTooltipImage(
      ItemStack stack) {
    var summary = stack.get(StorageContent.SUMMARY.get());
    return summary == null || summary.entries().isEmpty()
        ? java.util.Optional.empty()
        : java.util.Optional.of(summary);
  }

  @Override
  public void appendHoverText(
      ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
    var summary = stack.get(StorageContent.SUMMARY.get());
    if (summary != null)
      lines.add(
          summary.infinite()
              ? Component.translatable(
                  "tooltip.factorycore.cell_stored_infinite", summary.stored(), summary.types())
              : Component.translatable(
                  "tooltip.factorycore.cell_stored",
                  summary.stored(),
                  summary.capacity(),
                  summary.percent(),
                  summary.types()));
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
