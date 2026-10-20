package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.wireless.LinkEndpointBlock;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WirelessLinkGameTests {
  // Parent: controller → gateway G1 → endpoint A ~~ endpoint B → gateway G2 → drive (child).
  private static final BlockPos ROOT = new BlockPos(0, 1, 1),
      PARENT_GATE = new BlockPos(1, 1, 1),
      A = new BlockPos(2, 1, 1),
      B = new BlockPos(4, 1, 1),
      CHILD_GATE = new BlockPos(5, 1, 1),
      DRIVE = new BlockPos(6, 1, 1);

  private WirelessLinkGameTests() {}

  private static NetworkTopology.Membership membership(GameTestHelper h, BlockPos relative) {
    return WorldNetworks.get(h.getLevel().getServer())
        .membership(
            new NodeRegistry.Position(
                h.getLevel().dimension().location().toString(), h.absolutePos(relative).asLong()));
  }

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void pairedLinkJoinsChildBranchOnlyWhilePoweredAndPaired(GameTestHelper h) {
    var owner = h.makeMockServerPlayerInLevel();
    var stranger = h.makeMockServerPlayerInLevel();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    for (var gate : new BlockPos[] {PARENT_GATE, CHILD_GATE}) {
      h.setBlock(
          gate,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(BlockStateProperties.FACING, Direction.EAST));
      NetworkNodeEntity entity = h.getBlockEntity(gate);
      entity.commission(null);
    }
    // A's back faces west (G1 downstream); B's back faces east (G2 upstream).
    h.setBlock(
        A,
        WirelessContent.LINK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    h.setBlock(
        B,
        WirelessContent.LINK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.WEST));
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    for (var end : new BlockPos[] {A, B})
      WirelessContent.LINK
          .get()
          .setPlacedBy(
              h.getLevel(),
              h.absolutePos(end),
              h.getBlockState(end),
              owner,
              new ItemStack(WirelessContent.LINK_ITEM.get()));
    LinkEndpointEntity a = h.getBlockEntity(A);
    LinkEndpointEntity b = h.getBlockEntity(B);
    var far = h.absolutePos(A).offset(300, 0, 0);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    membership(h, ROOT).status() == NetworkTopology.Status.VALID
                        && membership(h, DRIVE).status() == NetworkTopology.Status.NO_ROOT,
                    "Unlinked child branch has no root: " + membership(h, DRIVE)))
        .thenExecute(
            () -> {
              h.assertTrue(
                  LinkEndpointBlock.pair(owner, h.absolutePos(A), far).equals("out_of_range"),
                  "Pairing beyond 256 blocks is refused");
              h.assertTrue(
                  LinkEndpointBlock.pair(stranger, h.absolutePos(A), h.absolutePos(B))
                      .equals("denied"),
                  "Pairing needs Manage at the parent endpoint");
              h.assertTrue(
                  LinkEndpointBlock.pair(owner, h.absolutePos(A), h.absolutePos(B)).equals("paired")
                      && a.diagnostic().equals("no_power"),
                  "Owner pairs; endpoints without FE stay inactive: " + a.diagnostic());
            })
        .thenIdle(10)
        .thenExecute(
            () -> {
              h.assertTrue(
                  membership(h, DRIVE).status() == NetworkTopology.Status.NO_ROOT,
                  "An unpowered pair does not connect");
              h.assertTrue(
                  a.energyStorage().receiveEnergy(5_000, false) == 1_000
                      && b.energyStorage().receiveEnergy(1_000, false) == 1_000,
                  "Endpoint input is rate-limited to 1000 FE per tick");
            })
        .thenWaitUntil(
            () -> {
              var child = membership(h, DRIVE);
              h.assertTrue(
                  child.status() == NetworkTopology.Status.VALID
                      && child.scope().network().equals(membership(h, ROOT).scope().network())
                      && !child.scope().segment().equals(membership(h, ROOT).scope().segment()),
                  "Powered pair joins the child below its gateway: " + child);
            })
        .thenExecute(
            () -> {
              h.assertTrue(a.energy() < 1_000, "Endpoints pay operating power per tick");
              b.spend(b.energy());
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    b.diagnostic().equals("no_power")
                        && membership(h, DRIVE).status() == NetworkTopology.Status.NO_ROOT,
                    "Power loss at either end disconnects the child: " + membership(h, DRIVE)))
        .thenExecute(
            () -> {
              owner.connection.disconnect(Component.literal("done"));
              stranger.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
