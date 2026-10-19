package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.StorageContent;
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
    StorageContent.register(bus);
    dev.izzy.factorycore.platform.storage.TerminalContent.register(bus);
    dev.izzy.factorycore.platform.storage.BufferedInterfaceContent.register(bus);
    dev.izzy.factorycore.platform.storage.ConnectorContent.register(bus);
    dev.izzy.factorycore.platform.storage.MachineInterfaceContent.register(bus);
    EnergyBankContent.register(bus);
    dev.izzy.factorycore.platform.power.FuelGeneratorContent.register(bus);
    dev.izzy.factorycore.platform.crafting.CraftingContent.register(bus);
    dev.izzy.factorycore.platform.program.ProgramContent.register(bus);
    dev.izzy.factorycore.platform.wireless.WirelessContent.register(bus);
    dev.izzy.factorycore.platform.guide.GuideContent.register(bus);
    dev.izzy.factorycore.platform.upgrade.ThroughputContent.register(bus);
    NetworkContent.register(bus);
    dev.izzy.factorycore.platform.storage.RecoveryContent.register(bus);
    NeoForge.EVENT_BUS.addListener(DeviceSavedData::serverStarted);
    NeoForge.EVENT_BUS.addListener(
        dev.izzy.factorycore.platform.storage.RecoveryCommands::register);
    NeoForge.EVENT_BUS.addListener(dev.izzy.factorycore.platform.storage.RecoveryCommands::stopped);
    NeoForge.EVENT_BUS.addListener(dev.izzy.factorycore.platform.network.AccessCommands::register);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::postTick);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::stopped);
    NeoForge.EVENT_BUS.addListener(dev.izzy.factorycore.platform.crafting.WorldCrafting::stopped);
    NeoForge.EVENT_BUS.addListener(
        dev.izzy.factorycore.platform.crafting.WorldCraftingJobs::postTick);
    NeoForge.EVENT_BUS.addListener(
        dev.izzy.factorycore.platform.crafting.WorldCraftingJobs::stopped);
    NeoForge.EVENT_BUS.addListener(dev.izzy.factorycore.platform.storage.WorldPrograms::postTick);
    NeoForge.EVENT_BUS.addListener(dev.izzy.factorycore.platform.storage.WorldPrograms::stopped);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::playerLeft);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::tagsReloaded);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::loaded);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::unloaded);
  }
}
