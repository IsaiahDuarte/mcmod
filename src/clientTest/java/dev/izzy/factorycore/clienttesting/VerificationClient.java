package dev.izzy.factorycore.clienttesting;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = "factorycore_client_tests", value = Dist.CLIENT)
public final class VerificationClient {
  private VerificationClient() {}

  @SubscribeEvent
  public static void setup(FMLClientSetupEvent event) {
    event.enqueueWork(TerminalClientVerification::new);
  }
}
