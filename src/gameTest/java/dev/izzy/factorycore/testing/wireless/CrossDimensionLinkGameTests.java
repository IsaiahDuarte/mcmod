package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import dev.izzy.factorycore.platform.wireless.LinkPairing;
import dev.izzy.factorycore.testing.Links;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q36 (QD10, ADR 0040 amendment): a wireless link pair spanning the Overworld and the Nether joins
 * the Nether branch to the Overworld network at the higher cross-dimension cost, with no range
 * limit; while the Nether end's chunk is unloaded its branch waits and the Overworld side stays
 * online, nothing loads it, and reloading it restores the branch.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CrossDimensionLinkGameTests {
  private static final BlockPos ROOT = new BlockPos(0, 1, 1), PARENT_GATE = new BlockPos(1, 1, 1);

  private CrossDimensionLinkGameTests() {}

  private static NetworkTopology.Membership membership(Level level, BlockPos absolute) {
    return WorldNetworks.get(level.getServer()).membership(Positions.of(level, absolute));
  }

  private static LinkModule link(ServerLevel level, BlockPos gate) {
    return LinkModule.loaded(level, gate);
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void anOverworldNetherPairJoinsTheNetherBranchAndWaitsWhileItIsUnloaded(
      GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var overworld = h.getLevel();
    var nether = overworld.getServer().getLevel(Level.NETHER);
    Net.in(h, owner.getUUID()).controller(ROOT);
    h.setBlock(
        PARENT_GATE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity parent = h.getBlockEntity(PARENT_GATE);
    parent.commission(null);
    LinkModule a = Links.install(h, PARENT_GATE, Direction.EAST, owner.getUUID());
    // Far beyond the 256-block same-dimension range once scaled: the Nether end sits at the same
    // block coordinates, which no range rule would accept as close.
    var root = h.absolutePos(ROOT);
    var gate = new BlockPos(root.getX() + 4_000, 64, root.getZ());
    var drive = gate.east();
    var chunk = new net.minecraft.world.level.ChunkPos(gate);
    nether.setChunkForced(chunk.x, chunk.z, true);
    nether.setBlockAndUpdate(
        gate,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ((NetworkNodeEntity) nether.getBlockEntity(gate)).commission(null);
    ((NetworkNodeEntity) nether.getBlockEntity(gate))
        .installLink(Direction.WEST, owner.getUUID(), null);
    nether.setBlockAndUpdate(drive, StorageContent.DRIVE.get().defaultBlockState());
    // Keep both ends charged; a cross-dimension pair spends 128 FE per tick.
    h.onEachTick(
        () -> {
          a.energyStorage().receiveEnergy((int) LinkModule.INPUT_RATE, false);
          var b = link(nether, gate);
          if (b != null) b.energyStorage().receiveEnergy((int) LinkModule.INPUT_RATE, false);
        });
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    membership(nether, drive).status() == NetworkTopology.Status.NO_ROOT,
                    "Unlinked, the Nether branch has no root: " + membership(nether, drive)))
        .thenExecute(
            () -> {
              h.assertTrue(
                  LinkPairing.pair(owner, overworld, a.position(), nether, gate).equals("paired"),
                  "Pairing across dimensions is accepted at any distance");
              h.assertTrue(
                  a.crossDimension()
                      && a.tickCost() == LinkModule.CROSS_DIMENSION_COST
                      && link(nether, gate).partnerDimension().equals("minecraft:overworld"),
                  "Each end names the other's dimension and pays the cross-dimension cost");
            })
        .thenWaitUntil(
            () -> {
              var child = membership(nether, drive);
              h.assertTrue(
                  child.status() == NetworkTopology.Status.VALID
                      && child
                          .scope()
                          .network()
                          .equals(membership(overworld, root).scope().network()),
                  "The Nether drive joins the Overworld network: " + child);
              h.assertTrue(a.pair() != null, "The pair is metered as a wireless hop");
            })
        .thenExecute(
            () -> {
              nether.setChunkForced(chunk.x, chunk.z, false);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    Loaded.chunk(nether, gate) == null,
                    "The Nether end's chunk unloads once nothing holds it"))
        .thenWaitUntil(
            () -> {
              // As for a same-dimension pair (ADR 0040), the unloaded far end reads as an
              // unloaded cable: its branch waits while the Overworld side stays online.
              h.assertTrue(
                  membership(overworld, root).status() == NetworkTopology.Status.VALID,
                  "The Overworld side stays online: " + membership(overworld, root));
              h.assertTrue(
                  membership(nether, drive).status() != NetworkTopology.Status.VALID
                      && a.pair() == null,
                  "With the far end unloaded its branch waits: " + membership(nether, drive));
            })
        .thenIdle(20)
        .thenExecute(
            () ->
                h.assertTrue(
                    Loaded.chunk(nether, gate) == null, "Waiting never loads the far chunk"))
        .thenExecute(() -> nether.setChunkForced(chunk.x, chunk.z, true))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    membership(nether, drive).status() == NetworkTopology.Status.VALID,
                    "Reloaded, the Nether branch rejoins: " + membership(nether, drive)))
        .thenExecute(
            () -> {
              nether.setBlockAndUpdate(drive, Blocks.AIR.defaultBlockState());
              nether.setBlockAndUpdate(gate, Blocks.AIR.defaultBlockState());
              nether.setChunkForced(chunk.x, chunk.z, false);
              owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
