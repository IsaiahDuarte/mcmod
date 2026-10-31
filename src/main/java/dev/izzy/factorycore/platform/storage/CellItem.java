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

  /**
   * Q78: a creative player's copy of a cell becomes a fresh, empty, unbound cell instead of a
   * second reference to the same ledger (ADR 0005 counts that as a conflicting duplicate). Without
   * hooking the creative clone itself, a copy shows as a cell whose ledger is installed in a drive
   * bay, or as the later of two of the player's stacks with the same ledger. Survival players are
   * unaffected.
   */
  @Override
  public void inventoryTick(
      ItemStack stack,
      net.minecraft.world.level.Level level,
      net.minecraft.world.entity.Entity entity,
      int slot,
      boolean selected) {
    if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)
        || !player.hasInfiniteMaterials()) return;
    var handle = handle(stack);
    if (handle == null) return;
    var saved = DeviceSavedData.get(player.server);
    if (!saved.diagnostic().isEmpty()) return;
    var owner = saved.registry().ownership(handle.backing());
    boolean installed = owner != null && owner.definition().location() != null;
    if (installed || earlierCopy(player, stack, handle)) {
      CellHandleData.clear(stack);
      stack.remove(StorageContent.SUMMARY.get());
    }
  }

  private static dev.izzy.factorycore.core.storage.DeviceRegistry.Handle handle(ItemStack stack) {
    try {
      return CellHandleData.read(stack);
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  /** Another of the player's stacks before {@code stack} holds the same ledger. */
  private static boolean earlierCopy(
      net.minecraft.server.level.ServerPlayer player,
      ItemStack stack,
      dev.izzy.factorycore.core.storage.DeviceRegistry.Handle handle) {
    var inventory = player.getInventory();
    for (int index = 0; index < inventory.getContainerSize(); index++) {
      var other = inventory.getItem(index);
      if (other == stack) return false;
      var held = other.getItem() instanceof CellItem ? handle(other) : null;
      if (held != null && held.backing().equals(handle.backing())) return true;
    }
    return false;
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
