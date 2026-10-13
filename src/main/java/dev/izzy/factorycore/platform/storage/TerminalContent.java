package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.*;

public final class TerminalContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredBlock<TerminalBlock> BLOCK =
      BLOCKS.registerBlock(
          "storage_terminal",
          TerminalBlock::new,
          BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops());
  public static final DeferredItem<BlockItem> ITEM =
      ITEMS.register(
          "storage_terminal", () -> new TerminalItem(BLOCK.get(), new Item.Properties()));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TerminalEntity>> ENTITY =
      ENTITIES.register(
          "storage_terminal",
          () -> BlockEntityType.Builder.of(TerminalEntity::new, BLOCK.get()).build(null));
  public static final DeferredHolder<MenuType<?>, MenuType<TerminalMenu>> MENU =
      MENUS.register("storage_terminal", () -> IMenuTypeExtension.create(TerminalMenu::new));

  private TerminalContent() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    MENUS.register(bus);
    bus.addListener(TerminalPackets::register);
  }
}
