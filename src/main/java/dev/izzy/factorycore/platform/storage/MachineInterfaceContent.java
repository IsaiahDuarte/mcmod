package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MachineInterfaceContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  public static final DeferredBlock<ConnectorBlock> BLOCK =
      BLOCKS.registerBlock(
          "machine_interface",
          properties -> new ConnectorBlock(properties, true),
          BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops());
  public static final DeferredItem<MachineInterfaceItem> ITEM =
      ITEMS.register("machine_interface", () -> new MachineInterfaceItem(BLOCK.get()));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineInterfaceEntity>>
      ENTITY =
          ENTITIES.register(
              "machine_interface",
              () ->
                  BlockEntityType.Builder.of(MachineInterfaceEntity::new, BLOCK.get()).build(null));

  private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredHolder<
          net.minecraft.world.inventory.MenuType<?>,
          net.minecraft.world.inventory.MenuType<MachineSettingsMenu>>
      MENU =
          MENUS.register(
              "machine_settings",
              () ->
                  net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                      MachineSettingsMenu::new));

  private MachineInterfaceContent() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    MENUS.register(bus);
    bus.addListener(MachineSettingsPackets::register);
    bus.addListener(MachinePresetPackets::register);
  }
}
