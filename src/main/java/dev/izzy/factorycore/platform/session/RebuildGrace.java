package dev.izzy.factorycore.platform.session;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.ArrayDeque;
import net.minecraft.server.level.ServerPlayer;

/**
 * Q119 (after Q118's terminal): keeps a device screen open while a cable or device elsewhere
 * rebuilds its network. A menu that is no longer authorized, but whose player is still present and
 * whose network is REBUILDING, stays open for at most {@link #TICKS}. Commands from the viewer
 * meanwhile are kept (at most {@link #MAX_PENDING}) and replayed in order once the menu is
 * authorized again; the replay calls the handler afresh, so every check applies then. If the
 * rebuild does not end in time the menu closes and the kept commands are dropped. Server thread
 * only; one per menu.
 */
public final class RebuildGrace {
  /** How long a menu stays open while its network rebuilds (5 s). */
  public static final int TICKS = 100;

  public static final int MAX_PENDING = 8;

  private long since = -1;
  private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

  /** The menu is fully authorized: any grace ends. Returns true for use in {@code stillValid}. */
  public boolean valid() {
    since = -1;
    return true;
  }

  /** True while {@code origin}'s network is REBUILDING and this grace has not run out. */
  public boolean holds(ServerPlayer player, NodeRegistry.Position origin) {
    if (WorldNetworks.get(player.server).membership(origin).status()
        != NetworkTopology.Status.REBUILDING) return false;
    long now = player.serverLevel().getGameTime();
    if (since < 0) since = now;
    return now - since <= TICKS;
  }

  /**
   * True when {@code live} has the same network, segment and node as {@code captured}: a rebuild
   * that kept the session's authority. Region and generation change on every rebuild and are not
   * compared; grants are still checked live on every action.
   */
  public static boolean sameAuthority(NetworkTopology.Scope captured, NetworkTopology.Scope live) {
    return captured != null
        && live != null
        && captured.network().equals(live.network())
        && captured.segment().equals(live.segment())
        && captured.node().equals(live.node());
  }

  /** True while the menu is being held open by a rebuild. */
  public boolean active() {
    return since >= 0;
  }

  /** Keeps a command for replay; past {@link #MAX_PENDING} it is dropped. */
  public void keep(Runnable command) {
    if (pending.size() < MAX_PENDING) pending.add(command);
  }

  /** Replays kept commands in order; call once the menu is authorized again. */
  public void drain() {
    while (!pending.isEmpty()) pending.poll().run();
  }
}
