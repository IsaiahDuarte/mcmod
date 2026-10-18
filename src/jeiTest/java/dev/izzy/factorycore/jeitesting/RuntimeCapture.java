package dev.izzy.factorycore.jeitesting;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Captures JEI's live runtime so the fixture drives the same handlers JEI uses. */
@JeiPlugin
public final class RuntimeCapture implements IModPlugin {
  static volatile IJeiRuntime runtime;

  @Override
  public ResourceLocation getPluginUid() {
    return ResourceLocation.fromNamespaceAndPath("factorycore_jei_tests", "capture");
  }

  @Override
  public void onRuntimeAvailable(IJeiRuntime available) {
    runtime = available;
  }

  @Override
  public void onRuntimeUnavailable() {
    runtime = null;
  }
}
