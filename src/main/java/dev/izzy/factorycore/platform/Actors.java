package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import net.minecraft.world.entity.player.Player;

/** The one place a player becomes a network actor, and the one operator-level policy. */
public final class Actors {
  /** Permission level that makes a player a network operator (vanilla's "cheats" level). */
  public static final int OPERATOR_LEVEL = 2;

  private Actors() {}

  public static NetworkPermissions.Actor of(Player player) {
    return new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(OPERATOR_LEVEL));
  }
}
