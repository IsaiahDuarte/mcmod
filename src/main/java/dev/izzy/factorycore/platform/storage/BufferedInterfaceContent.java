package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class BufferedInterfaceContent {
  public static final DeferredBlock<BufferedInterfaceBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "buffered_interface", BufferedInterfaceBlock::new, DeviceProperties.machine());
  public static final DeferredItem<LeasedBlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "buffered_interface",
          () ->
              new LeasedBlockItem(
                  BLOCK.get(),
                  LeasedBlockItem.Tooltip.lines(
                          "tooltip.factorycore.buffer_slots",
                          "tooltip.factorycore.buffer_move",
                          "tooltip.factorycore.buffer_import",
                          "tooltip.factorycore.buffer_cleanup",
                          "tooltip.factorycore.buffer_filter",
                          "tooltip.factorycore.buffer_rate",
                          "tooltip.factorycore.buffer_export_stock")
                      .then(
                          LeasedBlockItem.Tooltip.throughput(
                              BufferData.FIELD, tag -> BufferData.read(tag).tier()))));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BufferedInterfaceEntity>>
      ENTITY = FactoryRegistries.entity("buffered_interface", BufferedInterfaceEntity::new, BLOCK);

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
