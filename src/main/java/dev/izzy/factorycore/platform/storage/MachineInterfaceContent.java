package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class MachineInterfaceContent {
  public static final DeferredBlock<ConnectorBlock> BLOCK =
      FactoryRegistries.BLOCKS.registerBlock(
          "machine_interface",
          properties -> new ConnectorBlock(properties, true),
          DeviceProperties.machine());
  public static final DeferredItem<LeasedBlockItem> ITEM =
      FactoryRegistries.ITEMS.register(
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
      ENTITY = FactoryRegistries.entity("machine_interface", MachineInterfaceEntity::new, BLOCK);

  public static final DeferredHolder<MenuType<?>, MenuType<MachineSettingsMenu>> MENU =
      FactoryRegistries.menu("machine_settings", MachineSettingsMenu::new);

  private MachineInterfaceContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(MachineSettingsPackets::register);
    bus.addListener(MachinePresetPackets::register);
  }
}
