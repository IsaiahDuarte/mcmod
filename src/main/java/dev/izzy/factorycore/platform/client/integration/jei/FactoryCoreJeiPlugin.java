package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;

/**
 * Optional JEI plugin, loaded only by JEI's annotation scan. Nothing else in the mod references
 * this package, so the mod runs unchanged without JEI (A14).
 */
@JeiPlugin
public final class FactoryCoreJeiPlugin implements IModPlugin {
  @Override
  public ResourceLocation getPluginUid() {
    return ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "jei");
  }

  @Override
  public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
    registration.addRecipeTransferHandler(
        new TerminalTransferHandler(registration.getTransferHelper()), RecipeTypes.CRAFTING);
    // Q5: every other recipe JEI shows fills the processing grid.
    registration.addUniversalRecipeTransferHandler(
        new ProcessingTransferHandler(registration.getTransferHelper()));
  }

  /** Q17: the terminal's search sync reads and writes JEI's ingredient filter. */
  @Override
  public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime runtime) {
    var filter = runtime.getIngredientFilter();
    dev.izzy.factorycore.platform.client.RecipeViewerSearch.install(
        new dev.izzy.factorycore.platform.client.RecipeViewerSearch.Bridge() {
          @Override
          public String text() {
            return filter.getFilterText();
          }

          @Override
          public void text(String value) {
            filter.setFilterText(value);
          }
        });
  }

  @Override
  public void onRuntimeUnavailable() {
    dev.izzy.factorycore.platform.client.RecipeViewerSearch.clear();
  }

  @Override
  public void registerGuiHandlers(IGuiHandlerRegistration registration) {
    // Q19: keep JEI's item list off the upgrade slot panel beside device screens.
    registration.addGenericGuiContainerHandler(
        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class,
        new mezz.jei.api.gui.handlers.IGuiContainerHandler<
            net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>>() {
          @Override
          public java.util.List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(
              net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
            var areas = new java.util.ArrayList<net.minecraft.client.renderer.Rect2i>();
            for (var child : screen.children())
              if (child instanceof dev.izzy.factorycore.platform.client.UpgradeSlotWidget slot
                  && slot.visible) {
                var area = slot.area();
                areas.add(
                    new net.minecraft.client.renderer.Rect2i(
                        area.x(), area.y(), area.width(), area.height()));
              }
            return areas;
          }
        });
    registration.addGhostIngredientHandler(ProgramScreen.class, new ProgramGhostHandler());
    registration.addGhostIngredientHandler(
        dev.izzy.factorycore.platform.client.BufferSettingsScreen.class,
        new FilterGhostHandler<>());
    registration.addGhostIngredientHandler(
        dev.izzy.factorycore.platform.client.PortScreen.class, new FilterGhostHandler<>());
    registration.addGhostIngredientHandler(
        dev.izzy.factorycore.platform.client.ConnectorSettingsScreen.class,
        new FilterGhostHandler<>());
    registration.addGhostIngredientHandler(
        dev.izzy.factorycore.platform.client.DriveScreen.class, new FilterGhostHandler<>());
    registration.addGhostIngredientHandler(
        dev.izzy.factorycore.platform.client.SignalScreen.class, new FilterGhostHandler<>());
  }
}
