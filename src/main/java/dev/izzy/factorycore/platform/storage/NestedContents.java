package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;

/**
 * Q101 (M120): items a cell never stores, because their contents would be a second, hidden store: a
 * bound cell (a ledger inside a ledger, see ADR 0005) and a container that holds items (a filled
 * shulker box or bundle). Empty ones are ordinary items. Checked where items enter a cell; an item
 * without components is never refused, so the common path decodes nothing.
 */
final class NestedContents {
  private NestedContents() {}

  static boolean refused(ResourceKey key, HolderLookup.Provider registries) {
    if (key.kind() != ResourceKind.ITEM || key.componentByteCount() == 0) return false;
    net.minecraft.world.item.ItemStack stack;
    try {
      stack = new PlatformResourceCodec(registries).itemStack(key, 1);
    } catch (IllegalArgumentException undecodable) {
      return false;
    }
    if (stack.getItem() instanceof CellItem) {
      try {
        if (CellHandleData.read(stack) != null) return true;
      } catch (IllegalArgumentException malformed) {
        return true;
      }
    }
    var container = stack.get(DataComponents.CONTAINER);
    if (container != null && container.nonEmptyStream().findAny().isPresent()) return true;
    var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
    return bundle != null && !bundle.isEmpty();
  }
}
