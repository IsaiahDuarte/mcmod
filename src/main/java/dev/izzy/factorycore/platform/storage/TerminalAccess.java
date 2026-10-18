package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * How a terminal session reaches the network: a placed Storage Terminal or a wireless handheld
 * through an access point. Every check runs on the server thread and is repeated per action.
 */
public interface TerminalAccess {
  /** True while {@code player} may keep using this session (holder, range, grants, power). */
  boolean canUse(ServerPlayer player);

  /** The network position that authorizes the session's operations. */
  NodeRegistry.Position origin();

  BlockPos getBlockPos();

  /** Pays the per-tick cost of an open session; false closes it. Wired access is free. */
  default boolean upkeep(ServerPlayer player) {
    return true;
  }

  /** Pays the cost of one mutating action; false refuses it with no effect. Wired is free. */
  default boolean spendAction(ServerPlayer player) {
    return true;
  }

  /** Squared player-to-origin reach for player-inventory endpoints (8 blocks when wired). */
  default double reachSquared() {
    return 64;
  }
}
