package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.platform.network.NetworkAccessMenu;
import dev.izzy.factorycore.platform.storage.ConnectorContent;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsMenu;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** U7: empty-hand use opens device screens, which carry the shared status strip. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class DeviceStatusGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), CONNECTOR = new BlockPos(2, 1, 1);

  private DeviceStatusGameTests() {}

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void plainUseOpensControllerAndConnectorScreensWithStatus(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT).above()));
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    h.setBlock(
        CONNECTOR,
        ConnectorContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ConnectorEntity connector = h.getBlockEntity(CONNECTOR);
    connector.place(new ItemStack(ConnectorContent.ITEM.get()));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(connector.canUse(player), "Wired owner ready"))
        .thenExecute(
            () -> {
              h.useBlock(ROOT, player);
              h.assertTrue(
                  player.containerMenu instanceof NetworkAccessMenu,
                  "Plain use on a controller opens Network Access instead of printing status");
              var access = (NetworkAccessMenu) player.containerMenu;
              access.broadcastChanges();
              h.assertTrue(
                  access.deviceStatus().network().equals("valid"),
                  "The controller screen's status strip reports the network online");
              player.closeContainer();
              h.useBlock(CONNECTOR, player);
              var settings = (ConnectorSettingsMenu) player.containerMenu;
              settings.broadcastChanges();
              h.assertTrue(
                  settings.deviceStatus().network().equals("valid")
                      && settings.deviceStatus().reason().startsWith("reason.factorycore.")
                      && !settings.deviceStatus().reason().equals("reason.factorycore.ok"),
                  "With no container behind it the connector strip explains why it is not ready");
              player.closeContainer();
              player.connection.disconnect(Component.literal("Device status fixture complete"));
            })
        .thenSucceed();
  }
}
