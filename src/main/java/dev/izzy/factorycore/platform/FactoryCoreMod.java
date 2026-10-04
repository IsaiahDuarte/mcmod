package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
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
    NetworkContent.register(bus);
    NeoForge.EVENT_BUS.addListener(DeviceSavedData::serverStarted);
  }
}
