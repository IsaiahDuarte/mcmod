package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Importer and Exporter blocks, items and screens (playtest H6, ADR 0066). Their block entity is
 * the Machine-port type in {@link MachineInterfaceContent}; each role has its own menu type so the
 * client opens the matching screen.
 */
public final class PortContent {
  public static final DeferredBlock<PortBlock> IMPORTER =
      FactoryRegistries.BLOCKS.registerBlock(
          "importer",
          properties -> new PortBlock(PortBlock.Role.IMPORTER, properties),
          DeviceProperties.machine());
  public static final DeferredBlock<PortBlock> EXPORTER =
      FactoryRegistries.BLOCKS.registerBlock(
          "exporter",
          properties -> new PortBlock(PortBlock.Role.EXPORTER, properties),
          DeviceProperties.machine());

  /** H5 (ADR 0065): made by the Label Tool from a cable; its item only carries a busy lease. */
  public static final DeferredBlock<CablePortBlock> CABLE_PORT =
      FactoryRegistries.BLOCKS.registerBlock(
          "cable_port", CablePortBlock::new, DeviceProperties.machine());

  public static final DeferredItem<LeasedBlockItem> CABLE_PORT_ITEM =
      FactoryRegistries.ITEMS.register(
          "cable_port",
          () ->
              new LeasedBlockItem(
                  CABLE_PORT.get(),
                  LeasedBlockItem.Tooltip.lines(
                      "tooltip.factorycore.cable_port", "tooltip.factorycore.cable_port_item")));

  public static final DeferredItem<LeasedBlockItem> IMPORTER_ITEM = item("importer", IMPORTER);
  public static final DeferredItem<LeasedBlockItem> EXPORTER_ITEM = item("exporter", EXPORTER);

  public static final DeferredHolder<MenuType<?>, MenuType<MachineSettingsMenu>> IMPORTER_MENU =
      FactoryRegistries.menu(
          "importer_settings",
          (id, inventory, buffer) ->
              MachineSettingsMenu.client(menu(PortBlock.Role.IMPORTER), id, inventory, buffer));
  public static final DeferredHolder<MenuType<?>, MenuType<MachineSettingsMenu>> EXPORTER_MENU =
      FactoryRegistries.menu(
          "exporter_settings",
          (id, inventory, buffer) ->
              MachineSettingsMenu.client(menu(PortBlock.Role.EXPORTER), id, inventory, buffer));

  public static final DeferredHolder<MenuType<?>, MenuType<MachineSettingsMenu>> CABLE_PORT_MENU =
      FactoryRegistries.menu(
          "cable_port_settings",
          (id, inventory, buffer) ->
              MachineSettingsMenu.client(PortContent.CABLE_PORT_MENU.get(), id, inventory, buffer));

  private PortContent() {}

  private static DeferredItem<LeasedBlockItem> item(String name, DeferredBlock<PortBlock> block) {
    return FactoryRegistries.ITEMS.register(
        name,
        () ->
            new LeasedBlockItem(
                block.get(),
                LeasedBlockItem.Tooltip.lines(
                        "tooltip.factorycore." + name, "tooltip.factorycore.port_use")
                    .then(
                        LeasedBlockItem.Tooltip.throughput(
                            ConnectorData.FIELD, tag -> ConnectorData.read(tag).tier()))));
  }

  /** The settings menu type for {@code role}. */
  public static MenuType<MachineSettingsMenu> menu(PortBlock.Role role) {
    return role == PortBlock.Role.IMPORTER ? IMPORTER_MENU.get() : EXPORTER_MENU.get();
  }

  public static void register(IEventBus bus) {
    // Loading this class adds its entries to FactoryRegistries before registration.
  }
}
