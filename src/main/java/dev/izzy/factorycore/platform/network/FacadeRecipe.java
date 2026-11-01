package dev.izzy.factorycore.platform.network;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Q30: one cable (any colour) and one allowed block ({@link FacadeItem#allowed}) make {@link
 * #COUNT} facades of that block. A special recipe: the block can be any of thousands.
 */
public final class FacadeRecipe extends CustomRecipe {
  public static final int COUNT = 4;

  public FacadeRecipe(CraftingBookCategory category) {
    super(category);
  }

  private static BlockItem block(CraftingInput input) {
    BlockItem block = null;
    int cables = 0;
    for (var stack : input.items()) {
      if (stack.isEmpty()) continue;
      if (stack.is(NetworkContent.CABLE_ITEM.get())) cables++;
      else if (block == null
          && stack.getItem() instanceof BlockItem item
          && FacadeItem.allowed(item.getBlock())) block = item;
      else return null;
    }
    return cables == 1 ? block : null;
  }

  @Override
  public boolean matches(CraftingInput input, Level level) {
    return block(input) != null;
  }

  @Override
  public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
    var block = block(input);
    return block == null ? ItemStack.EMPTY : FacadeItem.of(block.getBlock(), COUNT);
  }

  @Override
  public boolean canCraftInDimensions(int width, int height) {
    return width * height >= 2;
  }

  @Override
  public RecipeSerializer<?> getSerializer() {
    return NetworkContent.FACADE_RECIPE.get();
  }
}
