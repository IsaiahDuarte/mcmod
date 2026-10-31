package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkAccessMenu;
import dev.izzy.factorycore.platform.network.OverviewMenu;
import dev.izzy.factorycore.platform.power.EnergyUsers;
import dev.izzy.factorycore.platform.power.RateLimitedEnergyInput;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
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
import net.neoforged.neoforge.capabilities.Capabilities;
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

  /**
   * Q25: the overview reports the segment's energy use: a Program Controller running a program and
   * an Access Point serving an open wireless terminal each appear with their count and use, in the
   * total and among the largest users.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void overviewReportsEnergyUseOfAProgramControllerAndAnAccessPoint(
      GameTestHelper h) {
    var owner = player(h);
    owner.getInventory().clearContent();
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var point = TERMINAL;
    var controllerAt = new BlockPos(0, 1, 1);
    h.setBlock(point, WirelessContent.ACCESS_POINT.get());
    h.setBlock(controllerAt, ProgramContent.BLOCK.get());
    AccessPointEntity access = h.getBlockEntity(point);
    ProgramControllerEntity controller = h.getBlockEntity(controllerAt);
    owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(WirelessContent.HANDHELD.get()));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(DRIVE, point, controllerAt))
        .thenExecute(
            () -> {
              controller.energyStorage().receiveEnergy(1_000, false);
              controller.draft(
                  "every 10 ticks as \"beat\" { emit redstone 0 on machines(\"none\") }");
              var diagnostics =
                  WorldPrograms.get(h.getLevel().getServer())
                      .deploy(new NetworkPermissions.Actor(owner.getUUID(), false), controller);
              h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
              access.energyStorage().receiveEnergy(1_000, false);
              owner
                  .getMainHandItem()
                  .set(
                      WirelessContent.BINDING.get(),
                      new WirelessContent.Binding(
                          h.getLevel().dimension().location().toString(),
                          h.absolutePos(point).asLong()));
              owner
                  .getMainHandItem()
                  .getCapability(Capabilities.EnergyStorage.ITEM)
                  .receiveEnergy(100, false);
              h.assertTrue(
                  WirelessTerminalItem.open(owner, InteractionHand.MAIN_HAND).isEmpty(),
                  "The wireless terminal opens");
            })
        // An open wireless session costs the Access Point FE each tick it is kept open.
        .thenExecuteFor(
            2 * RateLimitedEnergyInput.WINDOW + 5, () -> owner.containerMenu.broadcastChanges())
        .thenExecute(
            () -> {
              var menu = open(h, owner);
              double point_ = menu.energyUse(EnergyUsers.Kind.ACCESS_POINT),
                  program = menu.energyUse(EnergyUsers.Kind.PROGRAM_CONTROLLER);
              var top = menu.topUsers();
              h.assertTrue(
                  menu.energyCount(EnergyUsers.Kind.ACCESS_POINT) == 1
                      && menu.energyCount(EnergyUsers.Kind.PROGRAM_CONTROLLER) == 1
                      && menu.energyCount(EnergyUsers.Kind.WIRELESS_LINK) == 0,
                  "One Access Point and one Program Controller use energy");
              h.assertTrue(
                  // A window can include ticks before the session opened, so at most its cost.
                  point_ > access.tickCost() / 2.0
                      && point_ <= access.tickCost() + 0.05
                      && program > 0,
                  "Each reports its use: point " + point_ + ", program " + program);
              h.assertTrue(
                  Math.abs(menu.energyUse() - point_ - program) < 0.15,
                  "The total adds them: " + menu.energyUse());
              h.assertTrue(
                  top.size() == 2
                      && top.get(0).kind() == EnergyUsers.Kind.ACCESS_POINT
                      && top.get(1).kind() == EnergyUsers.Kind.PROGRAM_CONTROLLER,
                  "The largest users come first: " + top);
            })
        .thenSucceed();
  }
}
