package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.RelayBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.testing.Net;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q31 (QD7): root, cable, Relay, cable. The branch past the Relay is online only while the Relay
 * has a redstone signal, and the Relay applies at most one change per {@link RelayBlock#COOLDOWN}
 * ticks however fast the signal flickers.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class RelayGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      RELAY = new BlockPos(3, 1, 1),
      BRANCH = new BlockPos(4, 1, 1),
      POWER = new BlockPos(3, 2, 1);

  private RelayGameTests() {}

  private static void setup(GameTestHelper h) {
    Net.in(h, UUID.randomUUID()).controller(ROOT);
    var cable = NetworkContent.CABLE.get();
    h.setBlock(RELAY, NetworkContent.RELAY.get());
    for (var at : new BlockPos[] {new BlockPos(2, 1, 1), BRANCH})
      h.setBlock(at, cable.connected(h.getLevel(), h.absolutePos(at)));
  }

  private static NetworkTopology.Membership membership(GameTestHelper h, BlockPos at) {
    return WorldNetworks.get(h.getLevel().getServer())
        .membership(Positions.of(h.getLevel(), h.absolutePos(at)));
  }

  private static boolean online(GameTestHelper h) {
    var root = membership(h, new BlockPos(2, 1, 1));
    var branch = membership(h, BRANCH);
    return branch.status() == NetworkTopology.Status.VALID
        && root.status() == NetworkTopology.Status.VALID
        && branch.scope().network().equals(root.scope().network());
  }

  private static boolean powered(GameTestHelper h) {
    return h.getBlockState(RELAY).getValue(RelayBlock.POWERED);
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void theBranchIsOnlineOnlyWhileTheRelayIsPowered(GameTestHelper h) {
    setup(h);
    h.startSequence()
        .thenIdle(10)
        .thenExecute(
            () ->
                h.assertTrue(
                    !powered(h) && membership(h, BRANCH).status() != NetworkTopology.Status.VALID,
                    "Unpowered: the branch past the Relay is offline"))
        .thenExecute(() -> h.setBlock(POWER, Blocks.REDSTONE_BLOCK))
        .thenWaitUntil(() -> h.assertTrue(online(h), "Powered: the branch joins the network"))
        // Past the cooldown, so the next change applies at once.
        .thenIdle(RelayBlock.COOLDOWN + 1)
        .thenExecute(() -> h.setBlock(POWER, Blocks.AIR))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    !powered(h)
                        && membership(h, BRANCH).status() != NetworkTopology.Status.VALID
                        && membership(h, BRANCH).status() != NetworkTopology.Status.REBUILDING,
                    "Unpowered again: the branch goes offline"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aFlickeringSignalChangesTheRelayAtMostOncePerSecond(GameTestHelper h) {
    setup(h);
    long[] flipped = new long[1];
    h.startSequence()
        .thenIdle(5)
        .thenExecute(() -> h.setBlock(POWER, Blocks.REDSTONE_BLOCK))
        .thenWaitUntil(() -> h.assertTrue(powered(h), "Powered"))
        .thenExecute(
            () -> {
              flipped[0] = h.getTick();
              // Off, on and off again inside the cooldown.
              h.setBlock(POWER, Blocks.AIR);
            })
        .thenIdle(3)
        .thenExecute(() -> h.setBlock(POWER, Blocks.REDSTONE_BLOCK))
        .thenIdle(3)
        .thenExecute(() -> h.setBlock(POWER, Blocks.AIR))
        .thenExecute(
            () ->
                h.assertTrue(
                    powered(h) && h.getTick() - flipped[0] < RelayBlock.COOLDOWN,
                    "Within the cooldown the Relay keeps its last applied state"))
        .thenWaitUntil(() -> h.assertTrue(!powered(h), "Then it applies the final signal: off"))
        .thenExecute(
            () ->
                h.assertTrue(
                    h.getTick() - flipped[0] >= RelayBlock.COOLDOWN,
                    "Not before a full cooldown: " + (h.getTick() - flipped[0]) + " ticks"))
        .thenSucceed();
  }
}
