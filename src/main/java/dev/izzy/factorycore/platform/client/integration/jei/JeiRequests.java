package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A5: opens a JEI recipe's first output in the terminal's Jobs view as a request (Ctrl-click on the
 * recipe's transfer button). The server honours it only if a visible pattern makes the item;
 * nothing is moved or reserved until the player previews and starts the job.
 */
public final class JeiRequests {
  private JeiRequests() {}

  /** Returns false when the recipe has no item output. */
  public static boolean request(TerminalMenu menu, IRecipeSlotsView slots) {
    var output =
        slots.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
            .flatMap(view -> view.getItemStacks())
            .findFirst();
    if (output.isEmpty()) return false;
    String id = BuiltInRegistries.ITEM.getKey(output.get().getItem()).toString();
    PacketDistributor.sendToServer(
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            TerminalPackets.REQUEST,
            0,
            1,
            id,
            new byte[32]));
    TerminalScreen.requestFromViewer(menu.containerId, id);
    return true;
  }
}
