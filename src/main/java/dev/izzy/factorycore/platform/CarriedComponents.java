package dev.izzy.factorycore.platform;

import java.util.List;
import net.minecraft.core.component.DataComponentType;

/**
 * A block whose broken item keeps block-entity state as data components. The list is the single
 * definition of what survives breaking: the generated loot table copies exactly these from the
 * block entity, so it must name every component the entity's {@code collectImplicitComponents} can
 * set.
 */
public interface CarriedComponents {
  List<DataComponentType<?>> carriedComponents();
}
