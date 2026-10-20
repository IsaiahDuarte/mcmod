package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

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

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void handheldUsesAccessPointRangePowerAndActionCost(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(player.connection.getConnection());
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
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
                  menu(player).view().status().equals("ready")
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
                  menu(player).view().status().equals("no_power")
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
}
