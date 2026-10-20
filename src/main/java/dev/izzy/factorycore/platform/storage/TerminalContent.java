package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class TerminalContent {
  public static final DeferredBlock<TerminalBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "storage_terminal", TerminalBlock::new, DeviceProperties.machine());
  public static final DeferredItem<BlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
          "storage_terminal", () -> new TerminalItem(BLOCK.get(), new Item.Properties()));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TerminalEntity>> ENTITY =
      FactoryRegistries.entity("storage_terminal", TerminalEntity::new, BLOCK);
  public static final DeferredHolder<MenuType<?>, MenuType<TerminalMenu>> MENU =
      FactoryRegistries.menu("storage_terminal", TerminalMenu::new);

  private TerminalContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(TerminalPackets::register);
  }
}
