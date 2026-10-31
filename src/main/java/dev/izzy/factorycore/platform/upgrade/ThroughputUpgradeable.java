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

  /**
   * Q19: takes the top installed upgrade out (Overdrive before Throughput) under the same authority
   * as installing, lowering any rate above the new ceiling to fit. Returns the removed item's tier,
   * or null, changing nothing, when {@code actor} may not configure this device. Throws {@link
   * IllegalStateException} {@code upgrade_none} when nothing is installed.
   */
  ThroughputTier removeUpgrade(NetworkPermissions.Actor actor);

  /** Q19: true for a gateway link, which takes one Overdrive and no Throughput Upgrade. */
  default boolean linkUpgrade() {
    return false;
  }

  /** The player-facing summary shown after a successful install. */
  Component throughputStatus(MinecraftServer server);
}
