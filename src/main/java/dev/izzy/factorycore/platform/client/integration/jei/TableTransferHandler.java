package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.core.crafting.GridTransfer;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Q6: fills the terminal grid from a JEI stonecutter or smithing recipe, switching its Table page
 * to that workstation: the stonecutter input in the first cell (and the shown result chosen), or
 * the smithing template, base and addition in the top row. With Ctrl held the recipe's output is
 * requested instead, as for crafting recipes (A5).
 */
final class TableTransferHandler<R extends Recipe<?>>
    implements IRecipeTransferHandler<TerminalMenu, RecipeHolder<R>> {
  private final RecipeType<RecipeHolder<R>> type;
  private final PatternContents.Table table;

  TableTransferHandler(RecipeType<RecipeHolder<R>> type, PatternContents.Table table) {
    this.type = type;
    this.table = table;
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
  public RecipeType<RecipeHolder<R>> getRecipeType() {
    return type;
  }

  // Still abstract in JEI 19.57; its default context overload delegates here.
  @SuppressWarnings("removal")
  @Override
  public IRecipeTransferError transferRecipe(
      TerminalMenu menu,
      RecipeHolder<R> recipe,
      IRecipeSlotsView slots,
      Player player,
      boolean maxTransfer,
      boolean doTransfer) {
    if (!doTransfer) return null;
    if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
      JeiRequests.request(menu, slots);
      return null;
    }
    var used = table.cells();
    var inputs = slots.getSlotViews(RecipeIngredientRole.INPUT);
    var options = new ArrayList<List<String>>(Collections.nCopies(9, List.of()));
    for (int index = 0; index < Math.min(used.length, inputs.size()); index++)
      options.set(
          used[index],
          inputs
              .get(index)
              .getItemStacks()
              .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
              .distinct()
              .limit(64)
              .toList());
    String result = "";
    if (table == PatternContents.Table.STONECUTTING)
      result =
          slots.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
              .flatMap(view -> view.getDisplayedItemStack().stream())
              .findFirst()
              .filter(stack -> !stack.isEmpty())
              .map(ItemStack::getItem)
              .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
              .orElse("");
    PacketDistributor.sendToServer(
        new TerminalCraftingPackets.Fill(
            menu.containerId,
            menu.session(),
            GridTransfer.choose(options, Holdings.of(player, menu)),
            table.ordinal(),
            result));
    return null;
  }
}
