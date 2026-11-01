package dev.izzy.factorycore.emitesting;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** Test-only entry for the EMI graphical run; never packaged or loaded in other runs. */
@Mod("factorycore_emi_tests")
public final class EmiVerificationMod {
  public EmiVerificationMod() {
    if (FMLEnvironment.dist.isClient()) new EmiClientVerification().register();
  }
}
