package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Explicit ownership metadata for providers whose backing cannot be inferred from vanilla
 * containers. The list must include every physical block sharing this kind's inventory, including
 * this block, in the same dimension. Every listed block must have a persistent block entity. It
 * must be immutable, complete, nonempty and at most 64 blocks. Aliases must declare the same list.
 * No contents are transferred or copied by this query.
 */
public interface ExternalBackingProvider {
  List<BlockPos> factoryCoreBackingPositions(ResourceKind kind);
}
