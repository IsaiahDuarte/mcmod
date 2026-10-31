package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.core.crafting.GridTransfer;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fills the terminal's 3-by-3 ghost grid from a JEI crafting recipe. Nothing is moved or consumed;
 * the option the player has most of (undamaged inventory stacks and terminal rows) is preferred.
 * With Ctrl held (A5) the recipe's output is opened in the Jobs view as a request instead, if the
 * network can craft it.
 */
final class TerminalTransferHandler
    implements IRecipeTransferHandler<TerminalMenu, RecipeHolder<CraftingRecipe>> {
  private final IRecipeTransferHandlerHelper helper;

  TerminalTransferHandler(IRecipeTransferHandlerHelper helper) {
    this.helper = helper;
  }

  @Override
  public Class<? extends TerminalMenu> getContainerClass() {
    return TerminalMenu.class;
  }

  @Override
  public Optional<MenuType<TerminalMenu>> getMenuType() {
    return Optional.of(TerminalContent.MENU.get());
  }

  @Override
  public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
    return RecipeTypes.CRAFTING;
  }

  // Still abstract in JEI 19.57; its default context overload delegates here.
  @SuppressWarnings("removal")
  @Override
  public IRecipeTransferError transferRecipe(
      TerminalMenu menu,
      RecipeHolder<CraftingRecipe> recipe,
      IRecipeSlotsView slots,
      Player player,
      boolean maxTransfer,
      boolean doTransfer) {
    var inputs = slots.getSlotViews(RecipeIngredientRole.INPUT);
    if (inputs.size() > 9)
      return helper.createUserErrorWithTooltip(
          Component.translatable("gui.factorycore.terminal.jei.too_large"));
    if (!doTransfer) return null;
    if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
      JeiRequests.request(menu, slots);
      return null;
    }
    // Q112: a crafting recipe switches a grid in processing mode back to Table; say so.
    if (menu.gridView().processing())
      player.displayClientMessage(
          Component.translatable("gui.factorycore.terminal.jei.switched_table"), true);
    var options = new ArrayList<List<String>>(9);
    for (var slot : inputs)
      options.add(
          slot.getItemStacks()
              .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
              .distinct()
              .limit(64)
              .toList());
    PacketDistributor.sendToServer(
        new TerminalCraftingPackets.Fill(
            menu.containerId,
            menu.session(),
            GridTransfer.choose(options, Holdings.of(player, menu))));
    return null;
  }
}
