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
    if (Boolean.getBoolean("factorycore.saveorder.server")) new SaveOrderVerification().register();
  }
}
