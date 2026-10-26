package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Machine-mode entity and screen types of the unified Interface (ADR 0059). */
public final class MachineInterfaceContent {
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineInterfaceEntity>>
      ENTITY =
          FactoryRegistries.entity(
              "machine_interface",
              MachineInterfaceEntity::new,
              InterfaceContent.BLOCK,
              PortContent.IMPORTER,
              PortContent.EXPORTER,
              PortContent.CABLE_PORT);

  public static final DeferredHolder<MenuType<?>, MenuType<MachineSettingsMenu>> MENU =
      FactoryRegistries.menu("machine_settings", MachineSettingsMenu::new);

  private MachineInterfaceContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(MachineSettingsPackets::register);
    bus.addListener(MachinePresetPackets::register);
  }
}
