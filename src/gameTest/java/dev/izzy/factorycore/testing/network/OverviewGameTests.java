package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkAccessMenu;
import dev.izzy.factorycore.platform.network.OverviewMenu;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * H10: using a Controller opens the network overview. It reports the routing status and how many
 * devices of each kind the network has, only to players with View, and its Access… button opens the
 * Network Access screen.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class OverviewGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      TERMINAL = new BlockPos(1, 1, 2),
      PORT = new BlockPos(1, 2, 1),
      CHEST = new BlockPos(1, 3, 1);

  private OverviewGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static OverviewMenu open(GameTestHelper h, ServerPlayer player) {
    var pos = h.absolutePos(ROOT);
    h.getLevel()
        .getBlockState(pos)
        .useWithoutItem(
            h.getLevel(),
            player,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    h.assertTrue(
        player.containerMenu instanceof OverviewMenu, "Using the controller opens the overview");
    var menu = (OverviewMenu) player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void overviewCountsTheNetworkForItsOwnerAndOpensAccess(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    h.setBlock(CHEST, Blocks.CHEST);
    net.machine(PORT, Direction.UP);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(DRIVE, TERMINAL, PORT))
        .thenExecute(
            () -> {
              var menu = open(h, owner);
              h.assertTrue(
                  menu.status() == NetworkTopology.Status.VALID, "Online: " + menu.status());
              h.assertTrue(
                  menu.count(NetworkTopology.Role.ROOT) == 1
                      && menu.count(NetworkTopology.Role.STORAGE) == 1
                      && menu.count(NetworkTopology.Role.TERMINAL) == 1
                      && menu.count(NetworkTopology.Role.INTERFACE) == 1
                      && menu.count(NetworkTopology.Role.CRAFTER) == 0,
                  "One drive, one terminal, one machine port, no Crafter");
              h.assertTrue(
                  menu.clickMenuButton(owner, OverviewMenu.ACCESS_BUTTON)
                      && owner.containerMenu instanceof NetworkAccessMenu,
                  "Access… opens the Network Access screen");
              owner.connection.disconnect(Component.literal("Overview fixture complete"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void aStrangerSeesTheStatusButNoCounts(GameTestHelper h) {
    var owner = player(h);
    var stranger = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(DRIVE))
        .thenExecute(
            () -> {
              stranger.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
              var menu = open(h, stranger);
              h.assertTrue(menu.status() == NetworkTopology.Status.VALID, "The status is public");
              h.assertTrue(
                  menu.count(NetworkTopology.Role.STORAGE) == -1,
                  "Counts need View access on the network");
              owner.connection.disconnect(Component.literal("Overview fixture complete"));
              stranger.connection.disconnect(Component.literal("Overview fixture complete"));
            })
        .thenSucceed();
  }
}
