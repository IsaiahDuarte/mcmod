package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * A placed device that accepts Throughput/Overdrive upgrades (ADR 0054). The upgrade item drives
 * every device through this one contract, so denial, inapplicable steps and the installed status
 * read the same for all of them.
 */
public interface ThroughputUpgradeable {
  ThroughputTier tier();

  /**
   * Installs the step {@code item} gives. Returns false, changing nothing, when {@code actor} may
   * not configure this device. Throws {@link IllegalStateException} with an {@code upgrade_*}
   * reason when the step does not apply, or another message for a device fault.
   */
  boolean upgrade(NetworkPermissions.Actor actor, ThroughputTier item);

  /** The player-facing summary shown after a successful install. */
  Component throughputStatus(MinecraftServer server);
}
