package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceContent;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** U6(a): a player-placed buffer starts in Import only under its placer's own current grants. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferDefaultImportGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      CABLE = new BlockPos(2, 1, 1),
      BUFFER = new BlockPos(3, 1, 1);

  private BufferDefaultImportGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static NetworkNodeEntity network(GameTestHelper h, ServerPlayer owner) {
    var root = Net.in(h, owner.getUUID()).controller(ROOT);
    h.setBlock(CABLE, NetworkContent.CABLE.get());
    return root;
  }

  /** Places the buffer the way a player does, through the block's placement hook. */
  private static BufferedInterfaceEntity place(GameTestHelper h, ServerPlayer placer) {
    h.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    var position = h.absolutePos(BUFFER);
    var state = h.getLevel().getBlockState(position);
    state
        .getBlock()
        .setPlacedBy(
            h.getLevel(),
            position,
            state,
            placer,
            new ItemStack(BufferedInterfaceContent.ITEM.get()));
    return h.getBlockEntity(BUFFER);
  }

  private static BufferRegistry.Deployment deployment(
      ServerPlayer player, BufferedInterfaceEntity buffer) {
    return DeviceSavedData.get(player.server).buffers().deployment(buffer.handle().id());
  }

  private static void end(ServerPlayer... players) {
    for (var player : players)
      player.connection.disconnect(Component.literal("Buffer default fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void ownerPlacedBufferStartsInImportUnderTheOwner(GameTestHelper h) {
    var owner = player(h);
    network(h, owner);
    var buffer = place(h, owner);
    h.succeedWhen(
        () -> {
          var deployment = deployment(owner, buffer);
          h.assertTrue(
              deployment != null
                  && deployment.mode() == BufferRegistry.Mode.IMPORT
                  && deployment.principal().equals(owner.getUUID()),
              "A placer with every grant gets an Import deployment in their own name");
          end(owner);
        });
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void placerWithoutDeployGrantLeavesBufferPaused(GameTestHelper h) {
    var owner = player(h);
    var guest = player(h);
    var root = network(h, owner);
    DeviceSavedData.get(owner.server)
        .network(root.handle().id())
        .setGrants(
            new NetworkPermissions.Actor(owner.getUUID(), false),
            guest.getUUID(),
            Set.of(
                NetworkPermissions.Permission.VIEW,
                NetworkPermissions.Permission.EDIT,
                NetworkPermissions.Permission.WITHDRAW,
                NetworkPermissions.Permission.DEPOSIT));
    var buffer = place(h, guest);
    h.startSequence()
        .thenIdle(
            (BufferedInterfaceEntity.DEFAULT_IMPORT_ATTEMPTS + 2)
                * BufferedInterfaceEntity.DEFAULT_IMPORT_TICKS)
        .thenExecute(
            () -> {
              var deployment = deployment(guest, buffer);
              h.assertTrue(
                  deployment == null || deployment.mode() == BufferRegistry.Mode.PAUSED,
                  "Without DEPLOY the placement default is denied and the buffer stays Paused");
              end(owner, guest);
            })
        .thenSucceed();
  }
}
