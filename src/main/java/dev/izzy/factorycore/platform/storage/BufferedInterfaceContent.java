package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Buffer-mode entity, screen and capability of the unified Interface (ADR 0059). */
public final class BufferedInterfaceContent {
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BufferedInterfaceEntity>>
      ENTITY =
          FactoryRegistries.entity(
              "buffered_interface", BufferedInterfaceEntity::new, InterfaceContent.BLOCK);

  public static final DeferredHolder<MenuType<?>, MenuType<BufferSettingsMenu>> MENU =
      FactoryRegistries.menu("buffer_settings", BufferSettingsMenu::new);

  private BufferedInterfaceContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(BufferSettingsPackets::register);
    bus.addListener(BufferedInterfaceContent::capabilities);
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, ENTITY.get(), (buffer, face) -> buffer.capability());
  }
}
