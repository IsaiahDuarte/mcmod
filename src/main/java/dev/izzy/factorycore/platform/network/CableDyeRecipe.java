package dev.izzy.factorycore.platform.network;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Q29: up to eight cables of any colours and one dye make that many cables of the dye's colour; a
 * water bucket instead clears them (the bucket is returned empty). Like vanilla armour dyeing it is
 * a special recipe with no recipe-book entry; it makes no new cable.
 */
public final class CableDyeRecipe extends CustomRecipe {
  public static final int MAX_CABLES = 8;

  public CableDyeRecipe(CraftingBookCategory category) {
    super(category);
  }

  /** The new colour, or null when the grid is not this recipe. */
  private static CableColor color(CraftingInput input) {
    CableColor color = null;
    int cables = 0;
    for (var stack : input.items()) {
      if (stack.isEmpty()) continue;
      if (stack.is(NetworkContent.CABLE_ITEM.get())) cables++;
      else if (color != null) return null;
      else if (stack.getItem() instanceof DyeItem dye) color = CableColor.of(dye.getDyeColor());
      else if (stack.is(Items.WATER_BUCKET)) color = CableColor.NONE;
      else return null;
    }
    return cables == 0 || cables > MAX_CABLES ? null : color;
  }

  @Override
  public boolean matches(CraftingInput input, Level level) {
    return color(input) != null;
  }

  @Override
  public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
    var color = color(input);
    if (color == null) return ItemStack.EMPTY;
    int cables = 0;
    for (var stack : input.items()) if (stack.is(NetworkContent.CABLE_ITEM.get())) cables++;
    return NetworkCableBlock.item(color, cables);
  }

  @Override
  public boolean canCraftInDimensions(int width, int height) {
    return width * height >= 2;
  }

  @Override
  public RecipeSerializer<?> getSerializer() {
    return NetworkContent.CABLE_DYE.get();
  }
}
