package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ConnectorContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  public static final DeferredBlock<ConnectorBlock> BLOCK =
      BLOCKS.registerBlock("storage_connector", ConnectorBlock::new, DeviceProperties.machine());
  public static final DeferredItem<LeasedBlockItem> ITEM =
      ITEMS.register(
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
      ENTITIES.register(
          "storage_connector",
          () -> BlockEntityType.Builder.of(ConnectorEntity::new, BLOCK.get()).build(null));

  private ConnectorContent() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
  }
}
