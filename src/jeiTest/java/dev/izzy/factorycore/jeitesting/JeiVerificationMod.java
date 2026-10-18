package dev.izzy.factorycore.jeitesting;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** Test-only entry for the JEI graphical run; never packaged or loaded in other runs. */
@Mod("factorycore_jei_tests")
public final class JeiVerificationMod {
  public JeiVerificationMod() {
    if (FMLEnvironment.dist.isClient()) new JeiClientVerification().register();
  }
}
