package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BufferedInterfaceContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  public static final DeferredBlock<BufferedInterfaceBlock> BLOCK =
      BLOCKS.registerBlock(
          "buffered_interface",
          BufferedInterfaceBlock::new,
          BlockBehaviour.Properties.of()
              .strength(3.5f)
              .requiresCorrectToolForDrops()
              .sound(net.minecraft.world.level.block.SoundType.METAL));
  public static final DeferredItem<BufferedInterfaceItem> ITEM =
      ITEMS.register("buffered_interface", () -> new BufferedInterfaceItem(BLOCK.get()));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BufferedInterfaceEntity>>
      ENTITY =
          ENTITIES.register(
              "buffered_interface",
              () ->
                  BlockEntityType.Builder.of(BufferedInterfaceEntity::new, BLOCK.get())
                      .build(null));

  private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredHolder<
          net.minecraft.world.inventory.MenuType<?>,
          net.minecraft.world.inventory.MenuType<BufferSettingsMenu>>
      MENU =
          MENUS.register(
              "buffer_settings",
              () ->
                  net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                      BufferSettingsMenu::new));

  private BufferedInterfaceContent() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    MENUS.register(bus);
    bus.addListener(BufferSettingsPackets::register);
    bus.addListener(BufferedInterfaceContent::capabilities);
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK, ENTITY.get(), (buffer, face) -> buffer.capability());
  }
}
