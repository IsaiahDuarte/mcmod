package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.core.network.NetworkTopology;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world server settings. Topology settings apply when the server opens the world. */
public final class FactoryCoreConfig {
  public static final ModConfigSpec SERVER;
  static final ModConfigSpec.IntValue GATEWAY_DEPTH;

  static {
    var builder = new ModConfigSpec.Builder();
    GATEWAY_DEPTH =
        builder
            .comment("Maximum nested gateways; reopen the world or restart the server to apply.")
            .worldRestart()
            .defineInRange("network.gatewayDepth", 8, 1, NetworkTopology.MAX_DEPTH);
    SERVER = builder.build();
  }

  private FactoryCoreConfig() {}

  /** Configured gateway count from root to deepest branch, inclusive; 1..32, default 8. */
  public static int gatewayDepth() {
    return GATEWAY_DEPTH.get();
  }
}
