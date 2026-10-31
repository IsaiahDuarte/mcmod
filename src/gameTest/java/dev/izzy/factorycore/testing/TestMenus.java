package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;

/**
 * Q114: menu helpers that keep a network rebuild from crashing the test server. Blocks set in one
 * tick start their own network discoveries; when the first publishes VALID, a later one can still
 * merge into it and publish a new scope, which closes menus opened in between. How soon that
 * happens depends on server load.
 */
public final class TestMenus {
  /** Ticks a network's scope must stay the same before a test opens a menu on it. */
  public static final int SETTLE = 10;

  private record Watch(GameTestHelper helper, BlockPos position) {}

  private static final Map<Watch, Object[]> WATCHES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private TestMenus() {}

  /** The player's open Terminal; a closed one fails the test instead of crashing the server. */
  public static TerminalMenu terminal(Player player) {
    if (player.containerMenu instanceof TerminalMenu menu) return menu;
    throw new GameTestAssertException(
        "Terminal closed: " + player.containerMenu.getClass().getSimpleName());
  }

  /**
   * For {@code thenWaitUntil}: {@code ready} holds and the network at absolute {@code position} has
   * stayed VALID with the same scope for {@link #SETTLE} checks (one per tick).
   */
  public static void ready(
      GameTestHelper helper, boolean ready, BlockPos position, String message) {
    helper.assertTrue(ready, message);
    var member =
        WorldNetworks.get(helper.getLevel().getServer())
            .membership(Positions.of(helper.getLevel(), position));
    var state = WATCHES.computeIfAbsent(new Watch(helper, position), key -> new Object[] {null, 0});
    if (member.status() == NetworkTopology.Status.VALID && Objects.equals(member.scope(), state[0]))
      state[1] = (int) state[1] + 1;
    else {
      state[0] = member.status() == NetworkTopology.Status.VALID ? member.scope() : null;
      state[1] = 0;
    }
    helper.assertTrue((int) state[1] >= SETTLE, message + " (network settling)");
  }
}
