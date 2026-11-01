package dev.izzy.factorycore.platform.client.integration.emi;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.izzy.factorycore.core.crafting.GridTransfer;
import dev.izzy.factorycore.core.crafting.ProcessingTransfer;
import dev.izzy.factorycore.platform.client.RecipeViewerHoldings;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Q16: EMI's fill button over the terminal sets ghost cells and moves nothing, as the JEI handlers
 * do: a crafting recipe fills the Table grid, a stonecutter or smithing recipe the matching
 * workstation (Q6), and any other recipe the processing grid (Q5). With Ctrl held the recipe's
 * output is opened in the Jobs view as a request instead (A5).
 */
final class TerminalRecipeHandler implements EmiRecipeHandler<TerminalMenu> {
  /** What EMI counts as available: the player's inventory and the terminal's stock (Q112). */
  @Override
  public EmiPlayerInventory getInventory(AbstractContainerScreen<TerminalMenu> screen) {
    var stacks = new ArrayList<EmiStack>();
    RecipeViewerHoldings.of(Minecraft.getInstance().player, screen.getMenu())
        .forEach(
            (id, count) -> {
              var key = net.minecraft.resources.ResourceLocation.tryParse(id);
              if (key == null) return;
              var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
              if (item != net.minecraft.world.item.Items.AIR) stacks.add(EmiStack.of(item, count));
            });
    return new EmiPlayerInventory(stacks);
  }

  @Override
  public boolean supportsRecipe(EmiRecipe recipe) {
    return !recipe.getInputs().isEmpty() && !recipe.getOutputs().isEmpty();
  }

  @Override
  public boolean alwaysDisplaySupport(EmiRecipe recipe) {
    return true;
  }

  @Override
  public boolean canCraft(EmiRecipe recipe, EmiCraftContext<TerminalMenu> context) {
    return true;
  }

  @Override
  public boolean craft(EmiRecipe recipe, EmiCraftContext<TerminalMenu> context) {
    var menu = context.getScreenHandler();
    var player = Minecraft.getInstance().player;
    if (Screen.hasControlDown()) return EmiRequests.request(menu, recipe);
    var available = RecipeViewerHoldings.of(player, menu);
    var category = recipe.getCategory();
    if (category == VanillaEmiRecipeCategories.CRAFTING) {
      var inputs = recipe.getInputs();
      if (inputs.size() > 9) {
        player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.jei.too_large"), true);
        return false;
      }
      if (menu.gridView().processing())
        player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.jei.switched_table"), true);
      // Shaped recipes come padded to the 3×3 grid; shapeless ones fill cells in order.
      var options = new ArrayList<List<String>>(Collections.nCopies(9, List.of()));
      for (int index = 0; index < inputs.size(); index++)
        options.set(index, EmiStacks.itemIds(inputs.get(index)));
      fill(menu, GridTransfer.choose(options, available), PatternContents.Table.CRAFTING, "");
      return true;
    }
    if (category == VanillaEmiRecipeCategories.STONECUTTING
        || category == VanillaEmiRecipeCategories.SMITHING) {
      var table =
          category == VanillaEmiRecipeCategories.STONECUTTING
              ? PatternContents.Table.STONECUTTING
              : PatternContents.Table.SMITHING;
      var used = table.cells();
      var inputs = recipe.getInputs();
      var options = new ArrayList<List<String>>(Collections.nCopies(9, List.of()));
      for (int index = 0; index < Math.min(used.length, inputs.size()); index++)
        options.set(used[index], EmiStacks.itemIds(inputs.get(index)));
      String result = "";
      if (table == PatternContents.Table.STONECUTTING) {
        var id = EmiStacks.itemId(recipe.getOutputs().getFirst());
        if (id != null) result = id;
      }
      fill(menu, GridTransfer.choose(options, available), table, result);
      return true;
    }
    return processing(menu, recipe, available);
  }

  private static void fill(
      TerminalMenu menu, List<String> cells, PatternContents.Table table, String result) {
    PacketDistributor.sendToServer(
        new TerminalCraftingPackets.Fill(
            menu.containerId, menu.session(), cells, table.ordinal(), result));
  }

  private static boolean processing(
      TerminalMenu menu, EmiRecipe recipe, Map<String, Long> available) {
    var player = Minecraft.getInstance().player;
    var inputs = cells(recipe.getInputs(), available);
    var outputs = cells(new ArrayList<EmiIngredient>(recipe.getOutputs()), available);
    if (inputs.size() > ProcessingTransfer.MAX_INPUTS) {
      player.displayClientMessage(
          Component.translatable("gui.factorycore.terminal.jei.too_many_inputs"), true);
      return false;
    }
    if (inputs.isEmpty() || outputs.isEmpty()) {
      player.displayClientMessage(
          Component.translatable("gui.factorycore.terminal.jei.nothing_to_transfer"), true);
      return false;
    }
    var kept = ProcessingTransfer.outputs(outputs);
    if (kept.truncated())
      player.displayClientMessage(
          Component.translatable(
              "gui.factorycore.terminal.jei.outputs_truncated",
              ProcessingTransfer.MAX_OUTPUTS,
              kept.total()),
          true);
    if (!menu.gridView().processing())
      player.displayClientMessage(
          Component.translatable("gui.factorycore.terminal.jei.switched_processing"), true);
    PacketDistributor.sendToServer(
        new TerminalCraftingPackets.ProcessingFill(
            menu.containerId, menu.session(), inputs, kept.kept()));
    return true;
  }

  /** Each ingredient as an item (the alternative held most) or a fluid with its amount. */
  private static List<TerminalCraftingPackets.ProcessingFill.Cell> cells(
      List<EmiIngredient> ingredients, Map<String, Long> available) {
    var cells = new ArrayList<TerminalCraftingPackets.ProcessingFill.Cell>();
    for (var ingredient : ingredients) {
      if (ingredient.isEmpty()) continue;
      var item = EmiStacks.best(ingredient, available);
      if (item != null) {
        cells.add(
            new TerminalCraftingPackets.ProcessingFill.Cell(
                false,
                EmiStacks.itemId(item),
                ProcessingTransfer.itemUnits(Math.max(1, ingredient.getAmount()))));
        continue;
      }
      var stack = ingredient.getEmiStacks().getFirst();
      var fluid = EmiStacks.fluidId(stack);
      if (fluid != null)
        cells.add(
            new TerminalCraftingPackets.ProcessingFill.Cell(
                true, fluid, ProcessingTransfer.fluidUnits(Math.max(1, ingredient.getAmount()))));
    }
    return cells;
  }
}
