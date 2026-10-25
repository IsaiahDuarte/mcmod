package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.guide.GuideContent;
import dev.izzy.factorycore.platform.network.AccessCommands;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.power.FuelGeneratorContent;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceContent;
import dev.izzy.factorycore.platform.storage.ConnectorContent;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.RecoveryCommands;
import dev.izzy.factorycore.platform.storage.RecoveryContent;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/** Loader entry point. Gameplay modules are added only after their decision gates. */
@Mod(FactoryCoreMod.MOD_ID)
public final class FactoryCoreMod {
  public static final String MOD_ID = "factorycore";

  public FactoryCoreMod(IEventBus bus, ModContainer container) {
    container.registerConfig(ModConfig.Type.SERVER, FactoryCoreConfig.SERVER);
    FactoryRegistries.register(bus);
    StorageContent.register(bus);
    TerminalContent.register(bus);
    BufferedInterfaceContent.register(bus);
    InterfaceContent.register(bus);
    ConnectorContent.register(bus);
    MachineInterfaceContent.register(bus);
    EnergyBankContent.register(bus);
    FuelGeneratorContent.register(bus);
    CraftingContent.register(bus);
    ProgramContent.register(bus);
    WirelessContent.register(bus);
    GuideContent.register(bus);
    ThroughputContent.register(bus);
    NetworkContent.register(bus);
    RecoveryContent.register(bus);
    dev.izzy.factorycore.platform.signal.SignalContent.register(bus);
    dev.izzy.factorycore.platform.storage.LabelToolContent.register(bus);
    bus.addListener(dev.izzy.factorycore.platform.session.DeviceStatus::register);
    NeoForge.EVENT_BUS.addListener(DeviceSavedData::serverStarted);
    NeoForge.EVENT_BUS.addListener(RecoveryCommands::register);
    NeoForge.EVENT_BUS.addListener(RecoveryCommands::stopped);
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.server.ServerStoppedEvent event) ->
            ServerScoped.stopped(event));
    NeoForge.EVENT_BUS.addListener(AccessCommands::register);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::postTick);
    NeoForge.EVENT_BUS.addListener(WorldCraftingJobs::postTick);
    NeoForge.EVENT_BUS.addListener(WorldPrograms::postTick);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::playerLeft);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::tagsReloaded);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::loaded);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::unloaded);
  }
}
