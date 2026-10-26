package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * The Machine-port entity type, behind Importers, Exporters (ADR 0066) and cable ports (ADR 0065).
 * The registry id keeps its original name; the Interface's Machine mode was removed in H7.
 */
public final class MachineInterfaceContent {
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineInterfaceEntity>>
      ENTITY =
          FactoryRegistries.entity(
              "machine_interface",
              MachineInterfaceEntity::new,
              PortContent.IMPORTER,
              PortContent.EXPORTER,
              PortContent.CABLE_PORT);

  private MachineInterfaceContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(MachineSettingsPackets::register);
    bus.addListener(MachinePresetPackets::register);
  }
}
