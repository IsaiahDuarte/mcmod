package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessPick;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WirelessGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      POINT = new BlockPos(1, 1, 2);

  private WirelessGameTests() {}

  private static TerminalMenu menu(ServerPlayer player) {
    return (TerminalMenu) player.containerMenu;
  }

  private static void command(ServerPlayer player, int action, int selection, int amount) {
    var m = menu(player);
    m.command(
        player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            action,
            selection,
            amount,
            "",
            new byte[32]));
    m.broadcastChanges();
  }

  private static int handheldEnergy(ServerPlayer player) {
    return player
        .getMainHandItem()
        .getCapability(Capabilities.EnergyStorage.ITEM)
        .getEnergyStored();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void handheldUsesAccessPointRangePowerAndActionCost(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = CellHandleData.read(drive.cell(0));
    h.setBlock(POINT, WirelessContent.ACCESS_POINT.get());
    AccessPointEntity point = h.getBlockEntity(POINT);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var key =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    var far = Vec3.atCenterOf(h.absolutePos(POINT)).add(20, 0, 0);
    player.moveTo(far);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(WirelessContent.HANDHELD.get()));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(saved.registry().stock(cell, key).loaded(), "Ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, key, 10);
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND).equals("unbound"),
                  "Unbound handhelds do not open");
              player
                  .getMainHandItem()
                  .set(
                      WirelessContent.BINDING.get(),
                      new WirelessContent.Binding(
                          h.getLevel().dimension().location().toString(),
                          h.absolutePos(POINT).asLong()));
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND).equals("no_power"),
                  "An unpowered access point refuses");
              h.assertTrue(
                  point.energyStorage().receiveEnergy(5_000, false) == 1_000,
                  "Point input is rate-limited");
              h.assertTrue(
                  player
                          .getMainHandItem()
                          .getCapability(Capabilities.EnergyStorage.ITEM)
                          .receiveEnergy(100, false)
                      == 100,
                  "Handheld accepts charge");
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND).isEmpty()
                      && player.containerMenu instanceof TerminalMenu,
                  "Bound handheld opens 20 blocks away, beyond wired reach");
            })
        .thenWaitUntil(
            () -> {
              menu(player).broadcastChanges();
              h.assertTrue(
                  menu(player).view().status() == TerminalPackets.Status.READY
                      && menu(player).view().rows().size() == 1,
                  "Wireless view lists stock: " + menu(player).view());
            })
        .thenExecute(
            () -> {
              h.assertTrue(point.energy() < 1_000, "Open session drains access point FE");
              command(player, TerminalPackets.WITHDRAW, 0, 4);
              h.assertTrue(
                  handheldEnergy(player) == 36, "One action costs 64 FE from the handheld");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    player.getInventory().countItem(Items.COBBLESTONE) == 4
                        && saved.registry().stock(cell, key).total() == 6,
                    "Withdraw reaches the player's inventory over wireless range"))
        .thenIdle(3)
        .thenExecute(
            () -> {
              command(player, TerminalPackets.WITHDRAW, 0, 4);
              h.assertTrue(
                  menu(player).view().status() == TerminalPackets.Status.NO_POWER
                      && handheldEnergy(player) == 36
                      && saved.registry().stock(cell, key).total() == 6,
                  "Without 64 FE the action is refused with no effect");
              player.moveTo(Vec3.atCenterOf(h.absolutePos(POINT)).add(70, 0, 0));
              menu(player).broadcastChanges();
              h.assertFalse(
                  player.containerMenu instanceof TerminalMenu, "Leaving range closes the session");
              player.moveTo(far);
              point.spend(point.energy());
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND).equals("no_power"),
                  "A drained access point refuses again");
              player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  private static void click(GameTestHelper h, ServerPlayer player, ItemStack held) {
    player.setItemInHand(InteractionHand.MAIN_HAND, held);
    var at = h.absolutePos(POINT);
    player.gameMode.useItemOn(
        player,
        h.getLevel(),
        held,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
  }

  /**
   * Q15: Throughput and Overdrive, used on the Access Point like any device, take its range from 64
   * to 128 to 256 blocks and its cost per open tick from 16 to 32 to 64 FE. The tier survives a
   * reload, and breaking the point returns both upgrades.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void rangeUpgradesWidenReachAndRaiseTheCost(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    // Survival, so an installed upgrade is used up.
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(POINT, WirelessContent.ACCESS_POINT.get());
    AccessPointEntity point = h.getBlockEntity(POINT);
    var handheld = new ItemStack(WirelessContent.HANDHELD.get());
    handheld.set(
        WirelessContent.BINDING.get(),
        new WirelessContent.Binding(
            h.getLevel().dimension().location().toString(), h.absolutePos(POINT).asLong()));
    handheld.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1_000, false);
    var centre = Vec3.atCenterOf(h.absolutePos(POINT));
    long[] before = {0};
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    dev.izzy.factorycore.platform.network.WorldNetworks.get(player.server)
                            .membership(point.origin())
                            .status()
                        == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID,
                    "Network ready"))
        .thenExecute(
            () -> {
              point.energyStorage().receiveEnergy(1_000, false);
              player.moveTo(centre.add(100, 0, 0));
              player.setItemInHand(InteractionHand.MAIN_HAND, handheld);
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND)
                      .equals("out_of_range"),
                  "A base point does not reach 100 blocks");
              player.moveTo(centre.add(2, 0, 0));
              click(h, player, new ItemStack(ThroughputContent.THROUGHPUT.get()));
              h.assertTrue(
                  point.tier() == ThroughputTier.ADVANCED
                      && player.getMainHandItem().isEmpty()
                      && player.containerMenu == player.inventoryMenu,
                  "Using Throughput installs it (no status message instead): " + point.tier());
              player.setItemInHand(InteractionHand.MAIN_HAND, handheld);
              player.moveTo(centre.add(100, 0, 0));
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND).isEmpty(),
                  "Throughput reaches 100 blocks");
              before[0] = point.energy();
            })
        .thenIdle(1)
        .thenExecute(
            () -> {
              menu(player).broadcastChanges();
              long drained = before[0] - point.energy();
              h.assertTrue(
                  point.tickCost() == 32 && drained >= 32 && drained % 32 == 0,
                  "An open session costs 32 FE per tick: " + drained);
              player.closeContainer();
              player.moveTo(centre.add(2, 0, 0));
              click(h, player, new ItemStack(ThroughputContent.OVERDRIVE.get()));
              h.assertTrue(point.tier() == ThroughputTier.ENDGAME, "Overdrive installed");
              player.setItemInHand(InteractionHand.MAIN_HAND, handheld);
              point.energyStorage().receiveEnergy(1_000, false);
              player.moveTo(centre.add(300, 0, 0));
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND)
                      .equals("out_of_range"),
                  "Overdrive stops at 256 blocks");
              player.moveTo(centre.add(200, 0, 0));
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND).isEmpty()
                      && point.tickCost() == 64
                      && point.range() == 256,
                  "Overdrive reaches 200 blocks at 64 FE per tick");
              player.closeContainer();
              var access = h.getLevel().registryAccess();
              var reloaded =
                  (AccessPointEntity)
                      BlockEntity.loadStatic(
                          h.absolutePos(POINT),
                          point.getBlockState(),
                          point.saveWithFullMetadata(access),
                          access);
              h.assertTrue(
                  reloaded != null && reloaded.tier() == ThroughputTier.ENDGAME,
                  "A reload keeps the tier");
              h.getLevel().destroyBlock(h.absolutePos(POINT), true);
            })
        .thenExecute(
            () -> {
              h.assertItemEntityPresent(ThroughputContent.THROUGHPUT.get(), POINT, 2);
              h.assertItemEntityPresent(ThroughputContent.OVERDRIVE.get(), POINT, 2);
              player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  /**
   * Q18(d): Pick Block on stone the player has none of pulls a stack from the network into the
   * selected empty hotbar slot through a bound Wireless Terminal kept in the inventory, for 64 FE
   * from the handheld; with stone already held, or without Withdraw, nothing moves.
   */
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void pickBlockPullsFromTheNetwork(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = CellHandleData.read(drive.cell(0));
    h.setBlock(POINT, WirelessContent.ACCESS_POINT.get());
    AccessPointEntity point = h.getBlockEntity(POINT);
    var stoneAt = new BlockPos(3, 1, 3);
    h.setBlock(stoneAt, net.minecraft.world.level.block.Blocks.STONE);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var stone =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.STONE));
    var handheld = new ItemStack(WirelessContent.HANDHELD.get());
    handheld.set(
        WirelessContent.BINDING.get(),
        new WirelessContent.Binding(
            h.getLevel().dimension().location().toString(), h.absolutePos(POINT).asLong()));
    handheld.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1_000, false);
    player.getInventory().setItem(20, handheld);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(stoneAt)).add(0, 0, 2));
    var stranger = TestPlayers.mock(h);
    TestPlayers.connect(stranger);
    stranger.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    stranger.getInventory().clearContent();
    stranger.getInventory().setItem(20, handheld.copy());
    stranger.moveTo(Vec3.atCenterOf(h.absolutePos(stoneAt)).add(0, 0, 2));
    var picked = h.absolutePos(stoneAt);
    int[] before = {0};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(saved.registry().stock(cell, stone).loaded(), "Ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, stone, 10);
              point.energyStorage().receiveEnergy(1_000, false);
              var refused = WirelessPick.pick(stranger, picked);
              h.assertTrue(
                  refused.equals("denied"), "Without Withdraw the pick is refused: " + refused);
              player.getInventory().selected = 3;
              before[0] =
                  player
                      .getInventory()
                      .getItem(20)
                      .getCapability(Capabilities.EnergyStorage.ITEM)
                      .getEnergyStored();
              h.assertTrue(WirelessPick.pick(player, picked).isEmpty(), "The pick starts");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    player.getInventory().getItem(3).is(Items.STONE)
                        && player.getInventory().getItem(3).getCount() == 10
                        && player.getInventory().selected == 3,
                    "Ten stone land in the selected empty slot: "
                        + player.getInventory().getItem(3)))
        .thenExecute(
            () -> {
              int after =
                  player
                      .getInventory()
                      .getItem(20)
                      .getCapability(Capabilities.EnergyStorage.ITEM)
                      .getEnergyStored();
              h.assertTrue(
                  before[0] - after == 64 && saved.registry().stock(cell, stone).total() == 0,
                  "One pick costs 64 FE from the handheld: " + (before[0] - after));
              saved.registry().insert(cell, stone, 5);
              h.assertTrue(WirelessPick.pick(player, picked).isEmpty(), "Holding stone: no-op");
            })
        .thenIdle(5)
        .thenExecute(
            () -> {
              h.assertTrue(
                  saved.registry().stock(cell, stone).total() == 5
                      && player.getInventory().countItem(Items.STONE) == 10,
                  "With stone already held, nothing is taken");
              player.connection.disconnect(Component.literal("done"));
              stranger.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
