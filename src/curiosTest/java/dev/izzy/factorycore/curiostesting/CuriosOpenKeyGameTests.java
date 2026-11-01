package dev.izzy.factorycore.curiostesting;

import com.mojang.authlib.GameProfile;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Q110 (QD1, ADR 0069): with Curios installed, the open key finds a Wireless Terminal worn in a
 * charm slot, still refuses two, and closes the terminal when the worn one is taken off.
 */
@GameTestHolder("factorycore_curios_tests")
@PrefixGameTestTemplate(false)
public final class CuriosOpenKeyGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      POINT = new BlockPos(1, 1, 2);

  private CuriosOpenKeyGameTests() {}

  /**
   * As {@code GameTestHelper.makeMockServerPlayerInLevel}, but the mock connection accepts mod
   * payloads before the player joins: Curios syncs its slots on join.
   */
  @SuppressWarnings("removal")
  private static ServerPlayer player(GameTestHelper h) {
    var cookie =
        CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "curios-mock"), false);
    var server = h.getLevel().getServer();
    var player =
        new ServerPlayer(server, h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
    var connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(connection);
    NetworkRegistry.configureMockConnection(connection);
    server.getPlayerList().placeNewPlayer(connection, player, cookie);
    return player;
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void theOpenKeyFindsAHandheldWornInACharmSlot(GameTestHelper h) {
    var player = player(h);
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    h.setBlock(POINT, WirelessContent.ACCESS_POINT.get());
    AccessPointEntity point = h.getBlockEntity(POINT);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var handheld = new ItemStack(WirelessContent.HANDHELD.get());
    handheld.set(
        WirelessContent.BINDING.get(),
        new WirelessContent.Binding(
            h.getLevel().dimension().location().toString(), h.absolutePos(POINT).asLong()));
    handheld.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1_000, false);
    h.startSequence()
        .thenWaitUntil(
            () -> {
              point.energyStorage().receiveEnergy(1_000, false);
              var cell = dev.izzy.factorycore.platform.storage.CellHandleData.read(drive.cell(0));
              var cobble =
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                          h.getLevel().registryAccess())
                      .itemKey(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
              h.assertTrue(
                  point.current()
                      && dev.izzy.factorycore.platform.storage.DeviceSavedData.get(
                              h.getLevel().getServer())
                          .registry()
                          .stock(cell, cobble)
                          .loaded(),
                  "Network ready");
            })
        .thenExecute(
            () -> {
              var curios = CuriosApi.getCuriosInventory(player).orElseThrow();
              var charm =
                  curios
                      .getStacksHandler("charm")
                      .orElseThrow(() -> new IllegalStateException("Players get a charm slot"));
              h.assertTrue(
                  CuriosApi.isStackValid(
                      new top.theillusivec4.curios.api.SlotContext("charm", player, 0, false, true),
                      handheld),
                  "The Wireless Terminal fits a charm slot");
              charm.getStacks().setStackInSlot(0, handheld.copy());
              player.getInventory().setItem(5, handheld.copy());
              h.assertTrue(
                  WirelessTerminalItem.openFromInventory(player).equals("several_handhelds"),
                  "One worn and one carried: refused rather than guessed");
              player.getInventory().setItem(5, ItemStack.EMPTY);
              var reason = WirelessTerminalItem.openFromInventory(player);
              h.assertTrue(
                  reason.isEmpty() && player.containerMenu instanceof TerminalMenu,
                  "The worn handheld opens the terminal: " + reason);
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              player.containerMenu.broadcastChanges();
              h.assertTrue(
                  player.containerMenu instanceof TerminalMenu, "Open while it stays worn");
              CuriosApi.getCuriosInventory(player)
                  .orElseThrow()
                  .getStacksHandler("charm")
                  .orElseThrow()
                  .getStacks()
                  .setStackInSlot(0, ItemStack.EMPTY);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    !(player.containerMenu instanceof TerminalMenu),
                    "Taking the worn handheld off closes the terminal"))
        .thenExecute(() -> player.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }
}
