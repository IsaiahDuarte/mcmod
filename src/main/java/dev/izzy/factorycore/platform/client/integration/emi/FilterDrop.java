package dev.izzy.factorycore.platform.client.integration.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.client.FilterDropTarget;
import net.minecraft.client.gui.screens.Screen;

/** Q16 (as B1 for JEI): dropping an EMI item or fluid on ghost filter slots adds it. */
final class FilterDrop<T extends Screen & FilterDropTarget> implements EmiDragDropHandler<T> {
  @Override
  public boolean dropStack(T screen, EmiIngredient ingredient, int x, int y) {
    var area = screen.filterArea();
    if (area == null || ingredient.getEmiStacks().isEmpty() || !area.contains(x, y)) return false;
    var stack = ingredient.getEmiStacks().getFirst();
    var item = EmiStacks.itemId(stack);
    var fluid = EmiStacks.fluidId(stack);
    if (item != null) screen.addFilterId(ResourceKind.ITEM, item);
    else if (fluid != null) screen.addFilterId(ResourceKind.FLUID, fluid);
    else return false;
    return true;
  }
}
