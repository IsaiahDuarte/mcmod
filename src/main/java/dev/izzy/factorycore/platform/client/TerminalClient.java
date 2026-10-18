package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class TerminalClient {
  private TerminalClient() {}

  @SubscribeEvent
  public static void screens(RegisterMenuScreensEvent event) {
    event.register(TerminalContent.MENU.get(), TerminalScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.MachineInterfaceContent.MENU.get(),
        MachineSettingsScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.BufferedInterfaceContent.MENU.get(),
        BufferSettingsScreen::new);
    event.register(
        dev.izzy.factorycore.platform.program.ProgramContent.MENU.get(), ProgramScreen::new);
    event.register(
        dev.izzy.factorycore.platform.network.NetworkContent.POLICY_MENU.get(),
        GatewayPolicyScreen::new);
  }
}
