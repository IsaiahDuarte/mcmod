package dev.izzy.factorycore.platform.client.integration.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.ConnectorSettingsScreen;
import dev.izzy.factorycore.platform.client.DriveScreen;
import dev.izzy.factorycore.platform.client.PortScreen;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.RecipeViewerSearch;
import dev.izzy.factorycore.platform.client.SignalScreen;
import dev.izzy.factorycore.platform.client.UpgradeSlotWidget;
import dev.izzy.factorycore.platform.storage.TerminalContent;

/**
 * Q16 (QD2, ADR 0069): the optional EMI plugin, found only by EMI's entrypoint scan, with the same
 * features as the JEI one: recipe fills into the terminal grid (crafting, stonecutter, smithing and
 * processing), Ctrl to request the output instead, drops into ghost filters and the program editor,
 * the upgrade panel kept clear, and terminal search sync.
 */
@EmiEntrypoint
public final class FactoryCoreEmiPlugin implements EmiPlugin {
  @Override
  public void register(EmiRegistry registry) {
    registry.addRecipeHandler(TerminalContent.MENU.get(), new TerminalRecipeHandler());
    registry.addDragDropHandler(ProgramScreen.class, new ProgramDrop());
    registry.addDragDropHandler(BufferSettingsScreen.class, new FilterDrop<>());
    registry.addDragDropHandler(PortScreen.class, new FilterDrop<>());
    registry.addDragDropHandler(ConnectorSettingsScreen.class, new FilterDrop<>());
    registry.addDragDropHandler(DriveScreen.class, new FilterDrop<>());
    registry.addDragDropHandler(SignalScreen.class, new FilterDrop<>());
    registry.addGenericExclusionArea(
        (screen, areas) -> {
          for (var child : screen.children())
            if (child instanceof UpgradeSlotWidget slot && slot.visible) {
              var area = slot.area();
              areas.accept(new Bounds(area.x(), area.y(), area.width(), area.height()));
            }
        });
    RecipeViewerSearch.install(
        new RecipeViewerSearch.Bridge() {
          @Override
          public String text() {
            return EmiApi.getSearchText();
          }

          @Override
          public void text(String value) {
            EmiApi.setSearchText(value);
          }
        });
  }
}
