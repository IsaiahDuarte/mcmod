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

  /** One immutable canonical storage partition, using zero-based global slot/tank indexes. */
  record PortWindow(int first, int count) {
    public PortWindow {
      if (first < 0 || count < 1 || count > 64)
        throw new IllegalArgumentException("Invalid canonical port window");
    }
  }

  /**
   * Optional fixed, nonoverlapping physical partitions (at most 64). Every alias/face must return
   * the same immutable catalog and global indexes for the lifetime of this backing identity. A
   * nonempty catalog permits only exact declared windows; it must never change or coexist with a
   * whole-inventory alias. These invariants preserve saved holds across reconfiguration. Empty
   * retains exclusive whole-backing accounting and arbitrary bounded window selection. Fluid
   * handlers additionally implement IndexedFluidHandler for selected mutation. Energy has one
   * unpartitioned port and must return an empty list.
   */
  default List<PortWindow> factoryCorePorts(ResourceKind kind) {
    return List.of();
  }
}
