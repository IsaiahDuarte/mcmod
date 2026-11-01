package dev.izzy.factorycore.platform.client.integration.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.izzy.factorycore.core.program.ResourceExpressions;
import dev.izzy.factorycore.platform.client.ProgramScreen;

/** Q16: dropping an EMI item or fluid on the program editor types it as programs name it. */
final class ProgramDrop implements EmiDragDropHandler<ProgramScreen> {
  @Override
  public boolean dropStack(ProgramScreen screen, EmiIngredient ingredient, int x, int y) {
    var area = screen.editorArea();
    if (ingredient.getEmiStacks().isEmpty() || !area.contains(x, y)) return false;
    var stack = ingredient.getEmiStacks().getFirst();
    var item = EmiStacks.itemId(stack);
    var fluid = EmiStacks.fluidId(stack);
    if (item != null) screen.insert(ResourceExpressions.item(item));
    else if (fluid != null) screen.insert(ResourceExpressions.fluid(fluid));
    else return false;
    return true;
  }
}
