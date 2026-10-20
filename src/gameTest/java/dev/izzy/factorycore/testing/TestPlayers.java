package dev.izzy.factorycore.testing;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * The one place GameTests create mock players. NeoForge marks these GameTest hooks for removal;
 * keeping them here confines the suppression and gives a single seam if they change.
 */
public final class TestPlayers {
  private TestPlayers() {}

  /** A mock server player standing in the test's level. */
  @SuppressWarnings("removal")
  public static ServerPlayer mock(GameTestHelper helper) {
    return helper.makeMockServerPlayerInLevel();
  }

  /** Lets server code send this mock player custom payloads (menus, views). */
  @SuppressWarnings("removal")
  public static void connect(ServerPlayer player) {
    NetworkRegistry.configureMockConnection(player.connection.getConnection());
  }
}
