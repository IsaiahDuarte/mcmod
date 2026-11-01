package dev.izzy.factorycore.platform.monitor;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** Q28: the Storage Monitor's registrations. */
public final class MonitorContent {
  public static final DeferredBlock<MonitorBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "storage_monitor", MonitorBlock::new, DeviceProperties.machine());

  public static final DeferredItem<BlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "storage_monitor", () -> new BlockItem(BLOCK.get(), new Item.Properties()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonitorEntity>> ENTITY =
      FactoryRegistries.entity("storage_monitor", MonitorEntity::new, BLOCK);

  private MonitorContent() {}

  public static void register(IEventBus bus) {
    NeoForge.EVENT_BUS.addListener(WorldMonitors::postTick);
  }
}
