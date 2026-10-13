package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.StorageContent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Loader entry point. Gameplay modules are added only after their decision gates. */
@Mod(FactoryCoreMod.MOD_ID)
public final class FactoryCoreMod {
  public static final String MOD_ID = "factorycore";

  public FactoryCoreMod(IEventBus bus) {
    StorageContent.register(bus);
    dev.izzy.factorycore.platform.storage.TerminalContent.register(bus);
    dev.izzy.factorycore.platform.storage.BufferedInterfaceContent.register(bus);
    dev.izzy.factorycore.platform.storage.ConnectorContent.register(bus);
    EnergyBankContent.register(bus);
    NetworkContent.register(bus);
    NeoForge.EVENT_BUS.addListener(DeviceSavedData::serverStarted);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::postTick);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::stopped);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::playerLeft);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::loaded);
    NeoForge.EVENT_BUS.addListener(WorldNetworks::unloaded);
  }
}
