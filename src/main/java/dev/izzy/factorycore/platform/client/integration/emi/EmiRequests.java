package dev.izzy.factorycore.platform.client.integration.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Q16 (as A5 for JEI): opens an EMI recipe's first item output in the terminal's Jobs view as a
 * request (Ctrl on EMI's fill button). Nothing is moved or reserved until the player starts a job.
 */
public final class EmiRequests {
  private EmiRequests() {}

  /** Returns false when the recipe has no item output. */
  public static boolean request(TerminalMenu menu, EmiRecipe recipe) {
    String id = null;
    for (var output : recipe.getOutputs()) {
      id = EmiStacks.itemId(output);
      if (id != null) break;
    }
    if (id == null) return false;
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
    TerminalScreen.requestFromViewer(menu, id);
    return true;
  }
}
