package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
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
      BLOCKS.registerBlock(
          "storage_connector",
          ConnectorBlock::new,
          BlockBehaviour.Properties.of()
              .strength(3.5f)
              .requiresCorrectToolForDrops()
              .sound(SoundType.METAL));
  public static final DeferredItem<ConnectorItem> ITEM =
      ITEMS.register("storage_connector", () -> new ConnectorItem(BLOCK.get()));
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
