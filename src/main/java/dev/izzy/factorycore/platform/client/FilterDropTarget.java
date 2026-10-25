package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import net.minecraft.client.renderer.Rect2i;

/**
 * B1: a screen whose ghost filter slots accept a recipe-viewer drop. The area is null while the
 * slots are hidden; a drop of the wrong kind (an item on a fluid filter) is ignored.
 */
public interface FilterDropTarget {
  Rect2i filterArea();

  void addFilterId(ResourceKind kind, String id);
}
