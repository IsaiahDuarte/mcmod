package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
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
          DeviceProperties.machine());
  public static final DeferredItem<LeasedBlockItem> ITEM =
      ITEMS.register(
          "machine_interface",
          () ->
              new LeasedBlockItem(
                  BLOCK.get(),
                  LeasedBlockItem.Tooltip.lines(
                          "tooltip.factorycore.machine",
                          "tooltip.factorycore.machine_controls",
                          "tooltip.factorycore.machine_access",
                          "tooltip.factorycore.machine_priority",
                          "tooltip.factorycore.machine_unique")
                      .then(
                          LeasedBlockItem.Tooltip.throughput(
                              ConnectorData.FIELD, tag -> ConnectorData.read(tag).tier()))));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineInterfaceEntity>>
      ENTITY =
          ENTITIES.register(
              "machine_interface",
              () ->
                  BlockEntityType.Builder.of(MachineInterfaceEntity::new, BLOCK.get()).build(null));

  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredHolder<MenuType<?>, MenuType<MachineSettingsMenu>> MENU =
      MENUS.register("machine_settings", () -> IMenuTypeExtension.create(MachineSettingsMenu::new));

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
