package dev.izzy.factorycore.platform.crafting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Crafting-table pattern holder; jobs craft these patterns here. */
public final class CraftingUnitEntity extends PatternHolderEntity {
  public CraftingUnitEntity(BlockPos position, BlockState state) {
    super(CraftingContent.UNIT_ENTITY.get(), position, state);
  }

  @Override
  protected boolean accepts(ItemStack stack) {
    return stack.is(CraftingContent.CRAFTING_PATTERN.get())
        && stack.get(CraftingContent.PATTERN.get()) != null;
  }
}
