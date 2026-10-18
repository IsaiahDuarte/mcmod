package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.core.program.ResourceExpressions;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;

/** Dropping a JEI item or fluid on the program editor types an escaped resource expression. */
final class ProgramGhostHandler implements IGhostIngredientHandler<ProgramScreen> {
  @Override
  public <I> List<Target<I>> getTargetsTyped(
      ProgramScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
    String expression;
    var item = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
    var fluid = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK);
    if (item.isPresent())
      expression =
          ResourceExpressions.item(BuiltInRegistries.ITEM.getKey(item.get().getItem()).toString());
    else if (fluid.isPresent())
      expression =
          ResourceExpressions.fluid(
              BuiltInRegistries.FLUID.getKey(fluid.get().getFluid()).toString());
    else return List.of();
    String text = expression;
    return List.of(
        new Target<>() {
          @Override
          public Rect2i getArea() {
            return screen.editorArea();
          }

          @Override
          public void accept(I ignored) {
            screen.insert(text);
          }
        });
  }

  @Override
  public void onComplete() {}
}
