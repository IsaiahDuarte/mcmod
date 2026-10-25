package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.client.FilterDropTarget;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;

/** B1: dragging a JEI item or fluid onto ghost filter slots adds it as an Item term. */
final class FilterGhostHandler<T extends Screen & FilterDropTarget>
    implements IGhostIngredientHandler<T> {
  @Override
  public <I> List<Target<I>> getTargetsTyped(
      T screen, ITypedIngredient<I> ingredient, boolean doStart) {
    var area = screen.filterArea();
    if (area == null) return List.of();
    var item = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
    var fluid = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK);
    ResourceKind kind;
    String id;
    if (item.isPresent()) {
      kind = ResourceKind.ITEM;
      id = BuiltInRegistries.ITEM.getKey(item.get().getItem()).toString();
    } else if (fluid.isPresent()) {
      kind = ResourceKind.FLUID;
      id = BuiltInRegistries.FLUID.getKey(fluid.get().getFluid()).toString();
    } else return List.of();
    return List.of(
        new Target<>() {
          @Override
          public Rect2i getArea() {
            return area;
          }

          @Override
          public void accept(I ignored) {
            screen.addFilterId(kind, id);
          }
        });
  }

  @Override
  public void onComplete() {}
}
