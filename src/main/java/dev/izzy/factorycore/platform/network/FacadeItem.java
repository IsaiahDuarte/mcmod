package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;

/**
 * Q30: a facade of one block. Use it on a cable to cover it ({@link NetworkCableBlock}); made from
 * a cable and the block ({@link FacadeRecipe}). The block travels as the {@code facade_block}
 * component.
 */
public final class FacadeItem extends Item {
  public FacadeItem(Properties properties) {
    super(properties);
  }

  /**
   * Blocks a facade may show: a plain, full, opaque cube with no entity, not a Factory Core block.
   * Read from the default state, so it is the same on client and server.
   */
  public static boolean allowed(Block block) {
    if (block == null) return false;
    var state = block.defaultBlockState();
    var id = BuiltInRegistries.BLOCK.getKey(block);
    return !id.getNamespace().equals(FactoryCoreMod.MOD_ID)
        && !state.hasBlockEntity()
        && state.getRenderShape() == RenderShape.MODEL
        && state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        && block.asItem() instanceof BlockItem;
  }

  public static ItemStack of(Block block, int count) {
    var stack = new ItemStack(NetworkContent.FACADE_ITEM.get(), count);
    stack.set(NetworkContent.FACADE_BLOCK.get(), BuiltInRegistries.BLOCK.wrapAsHolder(block));
    return stack;
  }

  /** The block shown, or null when unset or no longer allowed. */
  public static Block shown(ItemStack stack) {
    var holder = stack.get(NetworkContent.FACADE_BLOCK.get());
    return holder != null && allowed(holder.value()) ? holder.value() : null;
  }

  @Override
  public Component getName(ItemStack stack) {
    var block = shown(stack);
    return block == null
        ? super.getName(stack)
        : Component.translatable("item.factorycore.facade.of", block.getName());
  }
}
