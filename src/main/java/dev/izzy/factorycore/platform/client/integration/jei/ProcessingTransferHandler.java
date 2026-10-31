package dev.izzy.factorycore.platform.client.integration.jei;

import dev.izzy.factorycore.core.crafting.ProcessingTransfer;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ergonomics Q5: JEI's transfer button on any recipe other than a crafting-table one fills the
 * terminal's processing grid with the recipe's shown inputs and outputs, items and fluids, with
 * their amounts. Up to nine inputs; a recipe with more than three outputs keeps the first three and
 * says so. With Ctrl held, the output is requested in the Jobs view instead, as for crafting.
 * Crafting-table recipes keep their own handler, which JEI prefers.
 */
final class ProcessingTransferHandler implements IUniversalRecipeTransferHandler<TerminalMenu> {
  private final IRecipeTransferHandlerHelper helper;

  ProcessingTransferHandler(IRecipeTransferHandlerHelper helper) {
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
  public IRecipeTransferError transferRecipe(
      TerminalMenu menu,
      Object recipe,
      IRecipeSlotsView slots,
      Player player,
      boolean maxTransfer,
      boolean doTransfer) {
    var available = Holdings.of(player, menu);
    var inputs = cells(slots.getSlotViews(RecipeIngredientRole.INPUT), available);
    var outputs = cells(slots.getSlotViews(RecipeIngredientRole.OUTPUT), available);
    if (inputs.size() > ProcessingTransfer.MAX_INPUTS)
      return helper.createUserErrorWithTooltip(
          Component.translatable("gui.factorycore.terminal.jei.too_many_inputs"));
    if (inputs.isEmpty() || outputs.isEmpty())
      return helper.createUserErrorWithTooltip(
          Component.translatable("gui.factorycore.terminal.jei.nothing_to_transfer"));
    if (!doTransfer) return null;
    if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
      JeiRequests.request(menu, slots);
      return null;
    }
    var kept = ProcessingTransfer.outputs(outputs);
    if (kept.truncated())
      player.displayClientMessage(
          Component.translatable(
              "gui.factorycore.terminal.jei.outputs_truncated",
              ProcessingTransfer.MAX_OUTPUTS,
              kept.total()),
          true);
    // Q112: a processing recipe switches a Table grid to Inputs/Outputs; say so.
    if (!menu.gridView().processing())
      player.displayClientMessage(
          Component.translatable("gui.factorycore.terminal.jei.switched_processing"), true);
    PacketDistributor.sendToServer(
        new TerminalCraftingPackets.ProcessingFill(
            menu.containerId, menu.session(), inputs, kept.kept()));
    return null;
  }

  /**
   * The item or fluid of each nonempty slot; slots of other ingredient types are skipped. An item
   * slot with alternatives takes the one the player has most of (Q112; inventory and terminal
   * rows), else its first listed one, never JEI's cycling display, so the same click always fills
   * the same grid.
   */
  private static List<TerminalCraftingPackets.ProcessingFill.Cell> cells(
      List<IRecipeSlotView> views, java.util.Map<String, Long> available) {
    var cells = new ArrayList<TerminalCraftingPackets.ProcessingFill.Cell>();
    for (var view : views) {
      var options = view.getItemStacks().filter(stack -> !stack.isEmpty()).limit(64).toList();
      java.util.Optional<net.minecraft.world.item.ItemStack> item = options.stream().findFirst();
      long most = 0;
      for (var stack : options) {
        long count =
            available.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 0L);
        if (count > most) {
          most = count;
          item = java.util.Optional.of(stack);
        }
      }
      if (item.isPresent()) {
        cells.add(
            new TerminalCraftingPackets.ProcessingFill.Cell(
                false,
                BuiltInRegistries.ITEM.getKey(item.get().getItem()).toString(),
                ProcessingTransfer.itemUnits(item.get().getCount())));
        continue;
      }
      var fluid =
          view.getDisplayedIngredient(NeoForgeTypes.FLUID_STACK).filter(stack -> !stack.isEmpty());
      if (fluid.isPresent())
        cells.add(
            new TerminalCraftingPackets.ProcessingFill.Cell(
                true,
                BuiltInRegistries.FLUID.getKey(fluid.get().getFluid()).toString(),
                ProcessingTransfer.fluidUnits(fluid.get().getAmount())));
    }
    return cells;
  }
}
