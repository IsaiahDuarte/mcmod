package dev.izzy.factorycore.platform.crafting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

/**
 * Q6 (QD3): stonecutter and smithing recipes, looked up the way the vanilla workstations do, for
 * the terminal grid (preview and encode) and the Crafter (revalidated before every craft).
 */
public final class TableRecipes {
  private TableRecipes() {}

  /** What one craft makes: the output and any items left behind. */
  public record Made(ItemStack output, List<ItemStack> remainders) {}

  /** Every stonecutter result for one unit of {@code input}, in the stonecutter's order. */
  public static List<ItemStack> cuts(Level level, ItemStack input) {
    if (input.isEmpty()) return List.of();
    var in = new SingleRecipeInput(input.copyWithCount(1));
    var results = new ArrayList<ItemStack>();
    for (var holder : level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, in, level))
      results.add(holder.value().assemble(in, level.registryAccess()));
    return results;
  }

  /**
   * One craft of {@code table} from the nine {@code cells}, or null when they do not fit the table
   * or no recipe matches. A stonecutter input has several results: {@code wanted} picks one (it
   * must match exactly); for smithing a non-null {@code wanted} must equal the result.
   */
  public static Made make(
      Level level, PatternContents.Table table, List<ItemStack> cells, ItemStack wanted) {
    var used = table.cells();
    if (used == null || cells.size() != PatternContents.CELLS) return null;
    var expected = new boolean[PatternContents.CELLS];
    for (int cell : used) expected[cell] = true;
    for (int cell = 0; cell < PatternContents.CELLS; cell++)
      if (cells.get(cell).isEmpty() == expected[cell]) return null;
    var registries = level.registryAccess();
    var recipes = level.getRecipeManager();
    if (table == PatternContents.Table.STONECUTTING) {
      var in = new SingleRecipeInput(cells.getFirst().copyWithCount(1));
      for (var holder : recipes.getRecipesFor(RecipeType.STONECUTTING, in, level)) {
        var output = holder.value().assemble(in, registries);
        if (wanted == null || ItemStack.matches(output, wanted))
          return new Made(output, remainders(holder.value().getRemainingItems(in)));
      }
      return null;
    }
    var in =
        new SmithingRecipeInput(
            cells.get(0).copyWithCount(1),
            cells.get(1).copyWithCount(1),
            cells.get(2).copyWithCount(1));
    var holder = recipes.getRecipeFor(RecipeType.SMITHING, in, level).orElse(null);
    if (holder == null) return null;
    var output = holder.value().assemble(in, registries);
    if (output.isEmpty() || wanted != null && !ItemStack.matches(output, wanted)) return null;
    return new Made(output, remainders(holder.value().getRemainingItems(in)));
  }

  private static List<ItemStack> remainders(List<ItemStack> left) {
    var kept = new ArrayList<ItemStack>();
    for (var stack : left) if (!stack.isEmpty()) kept.add(stack);
    return kept;
  }
}
