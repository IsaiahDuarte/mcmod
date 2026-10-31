package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredItem;

/** Q22: the Wrench item and its drop collection hook. */
public final class WrenchContent {
  public static final DeferredItem<WrenchItem> WRENCH =
      FactoryRegistries.ITEMS.register("wrench", WrenchItem::new);

  private WrenchContent() {}

  public static void register(IEventBus bus) {
    NeoForge.EVENT_BUS.addListener(WrenchItem::drops);
  }
}
