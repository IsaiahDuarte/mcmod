package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceContent;
import dev.izzy.factorycore.platform.storage.ConnectorContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.RecoveryContent;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class TerminalClient {
  private TerminalClient() {}

  /** A5: job notices become toasts over open screens (see {@link JobToast}). */
  @SubscribeEvent
  public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
    dev.izzy.factorycore.platform.crafting.JobNotice.sink(JobToast::receive);
  }

  @SubscribeEvent
  public static void screens(RegisterMenuScreensEvent event) {
    event.register(TerminalContent.MENU.get(), TerminalScreen::new);
    event.register(StorageContent.DRIVE_MENU.get(), DriveScreen::new);
    event.register(MachineInterfaceContent.MENU.get(), MachineSettingsScreen::new);
    event.register(ConnectorContent.MENU.get(), ConnectorSettingsScreen::new);
    event.register(BufferedInterfaceContent.MENU.get(), BufferSettingsScreen::new);
    event.register(ProgramContent.MENU.get(), ProgramScreen::new);
    event.register(NetworkContent.POLICY_MENU.get(), GatewayPolicyScreen::new);
    event.register(NetworkContent.ACCESS_MENU.get(), NetworkAccessScreen::new);
    event.register(RecoveryContent.MENU.get(), RecoveryScreen::new);
    event.register(
        dev.izzy.factorycore.platform.signal.SignalContent.MENU.get(), SignalScreen::new);
  }
}
