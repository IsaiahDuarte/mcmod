package dev.izzy.factorycore.clienttesting;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Test-only common entry: dedicated checks never load the graphical driver's classes. */
@Mod("factorycore_client_tests")
public final class VerificationMod {
  public VerificationMod(IEventBus bus) {
    RecoveryProvider.register(bus);
    if (Boolean.getBoolean("factorycore.staging.server"))
      new StagingServerVerification().register();
    if (Boolean.getBoolean("factorycore.bench.server")) new ServerBenchmark().register();
    if (Boolean.getBoolean("factorycore.saveorder.server")) new SaveOrderVerification().register();
    if (Boolean.getBoolean("factorycore.interruption.server"))
      new InterruptionVerification().register();
    if (Boolean.getBoolean("factorycore.multiplayer.server"))
      new MultiplayerServerVerification().register();
    if (Boolean.getBoolean("factorycore.wireless.server"))
      new WirelessLifecycleVerification().register();
  }
}
