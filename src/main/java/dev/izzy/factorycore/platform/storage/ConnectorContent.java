package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class ConnectorContent {
  public static final DeferredBlock<ConnectorBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "storage_connector", ConnectorBlock::new, DeviceProperties.machine());
  public static final DeferredItem<LeasedBlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "storage_connector",
          () ->
              new LeasedBlockItem(
                  BLOCK.get(),
                  LeasedBlockItem.Tooltip.lines(
                          "tooltip.factorycore.connector",
                          "tooltip.factorycore.connector_controls",
                          "tooltip.factorycore.connector_access",
                          "tooltip.factorycore.connector_priority",
                          "tooltip.factorycore.connector_unique")
                      .then(
                          LeasedBlockItem.Tooltip.throughput(
                              ConnectorData.FIELD, tag -> ConnectorData.read(tag).tier()))));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConnectorEntity>> ENTITY =
      FactoryRegistries.entity("storage_connector", ConnectorEntity::new, BLOCK);

  private ConnectorContent() {}

  public static void register(IEventBus bus) {
    // Loading this class adds its entries to FactoryRegistries before registration.
  }
}
