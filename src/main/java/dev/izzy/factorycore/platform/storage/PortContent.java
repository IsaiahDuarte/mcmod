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
