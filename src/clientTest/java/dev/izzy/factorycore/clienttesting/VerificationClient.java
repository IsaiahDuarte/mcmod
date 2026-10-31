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
    // Verification clients play no music (it adds nothing to the checks).
    event.enqueueWork(
        () -> {
          var options = net.minecraft.client.Minecraft.getInstance().options;
          options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
          options.save();
        });
    if (Boolean.getBoolean("factorycore.presentation"))
      event.enqueueWork(PresentationClientVerification::new);
    else if (System.getProperty("factorycore.multiplayer.role") != null)
      event.enqueueWork(MultiplayerClientVerification::new);
    else event.enqueueWork(TerminalClientVerification::new);
  }
}
