package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.ComponentEnergyStorage;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** Q32: the Portable Storage's registrations. */
public final class PortableContent {
  public static final DeferredItem<PortableStorageItem> ITEM =
      FactoryRegistries.ITEMS.register("portable_storage", PortableStorageItem::new);

  /** The player who owns a Portable Storage (set on first use). */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> OWNER =
      FactoryRegistries.COMPONENTS.registerComponentType(
          "portable_owner",
          builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

  public static final DeferredHolder<MenuType<?>, MenuType<PortableStorageMenu>> MENU =
      FactoryRegistries.menu("portable_storage", PortableStorageMenu::new);

  private PortableContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(PortableContent::capabilities);
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerItem(
        Capabilities.EnergyStorage.ITEM,
        (stack, context) ->
            new ComponentEnergyStorage(
                stack,
                WirelessContent.ENERGY.get(),
                WirelessTerminalItem.CAPACITY,
                WirelessTerminalItem.CHARGE_RATE,
                WirelessTerminalItem.CHARGE_RATE),
        ITEM.get());
  }
}
