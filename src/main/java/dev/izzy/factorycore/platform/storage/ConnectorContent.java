package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Storage-mode entity and screen types of the unified Interface (ADR 0059). */
public final class ConnectorContent {
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConnectorEntity>> ENTITY =
      FactoryRegistries.entity("storage_connector", ConnectorEntity::new, InterfaceContent.BLOCK);

  public static final DeferredHolder<MenuType<?>, MenuType<ConnectorSettingsMenu>> MENU =
      FactoryRegistries.menu("connector_settings", ConnectorSettingsMenu::new);

  private ConnectorContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(ConnectorSettingsPackets::register);
  }
}
