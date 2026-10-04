package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.DiscoveryTask;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.network.WiredDiscovery;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldWireReader;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WireDiscoveryGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos GATE = new BlockPos(2, 1, 1);
  private static final BlockPos CHILD = new BlockPos(3, 1, 1);

  private WireDiscoveryGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper helper, BlockPos relative) {
    return new NodeRegistry.Position(
        helper.getLevel().dimension().location().toString(), helper.absolutePos(relative).asLong());
  }

  private static NetworkNodeEntity node(GameTestHelper helper, BlockPos relative) {
    return (NetworkNodeEntity) helper.getLevel().getBlockEntity(helper.absolutePos(relative));
  }

  @GameTest(template = "empty")
  public static void realWorldPortsPublishPrivateScopesThroughSharedScheduler(
      GameTestHelper helper) {
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    helper.setBlock(
        GATE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    helper.setBlock(CHILD, StorageContent.DRIVE.get());
    var owner = UUID.randomUUID();
    node(helper, ROOT).commission(owner);
    node(helper, GATE).commission(null);
    var reader = new WorldWireReader(helper.getLevel(), p -> null);
    long[] epoch = {1};
    var discovery =
        new WiredDiscovery(
            UUID.randomUUID(),
            8,
            List.of(position(helper, ROOT)).iterator(),
            reader,
            () -> epoch[0]);
    var scheduler =
        new FairScheduler(
            FairScheduler.Limits.defaults(),
            () -> 0,
            f -> {
              throw new IllegalStateException(f.diagnostic());
            });
    var task =
        new DiscoveryTask(
            scheduler, UUID.randomUUID(), UUID.randomUUID(), "world-discovery", discovery, 16);
    helper.assertTrue(
        task.submit() == FairScheduler.Admission.ACCEPTED,
        "Shared scheduling admitted real discovery");
    for (int i = 0; i < 200 && scheduler.backlog() > 0; i++) scheduler.tick();
    helper.assertTrue(scheduler.backlog() == 0, "Bounded real discovery completed");
    var topology = discovery.publishedTopology();
    var root = topology.membership(node(helper, ROOT).handle().id()).scope();
    var descriptor = discovery.captured(position(helper, CHILD));
    helper.assertTrue(descriptor != null, "Loaded child drive discovered");
    var child = topology.membership(descriptor.nodes().getFirst().id()).scope();
    helper.assertTrue(
        root != null && child != null && !root.segment().equals(child.segment()),
        "Physical gateway creates separate segment");
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    var authority = new NetworkAuthority(topology);
    authority.register(saved.network(root.network()));
    var actor = new NetworkPermissions.Actor(owner, false);
    helper.assertTrue(
        authority
            .storage(actor, root, ResourceKey.ENERGY, NetworkPermissions.Permission.VIEW)
            .segments()
            .equals(List.of(root.segment())),
        "Root view does not enumerate child-private storage");
    helper.assertTrue(
        authority
            .storage(actor, child, ResourceKey.ENERGY, NetworkPermissions.Permission.VIEW)
            .segments()
            .equals(List.of(child.segment(), root.segment())),
        "Child inherits permitted root view");
    discovery.cancel();
    epoch[0]++;
    helper.setBlock(GATE, Blocks.AIR);
    helper.assertTrue(
        topology.validate(child) == NetworkTopology.Status.STALE,
        "Immediate edit invalidates old published scopes");
    var detached =
        new WiredDiscovery(
            UUID.randomUUID(),
            8,
            List.of(position(helper, CHILD)).iterator(),
            reader,
            () -> epoch[0]);
    for (int i = 0; i < 200; i++) {
      var result = detached.advance(1);
      helper.assertTrue(result.work() <= 1, "One charged world visit per advance");
      if (result.state() == WiredDiscovery.State.PUBLISHED) break;
    }
    var isolated = detached.captured(position(helper, CHILD));
    helper.assertTrue(
        detached.publishedTopology().membership(isolated.nodes().getFirst().id()).status()
            == NetworkTopology.Status.NO_ROOT,
        "Detached drive does not invent a network");
    helper.succeed();
  }

  @GameTest(template = "empty")
  // Minecraft owns this borrowed chunk cache; a test must never close the world cache.
  @SuppressWarnings("PMD.CloseResource")
  public static void absentChunksNeverLoadAndLoadedAirDoesNotReuseCache(GameTestHelper helper) {
    var remote = new BlockPos(24000000, 64, 24000000);
    var position =
        new NodeRegistry.Position(
            helper.getLevel().dimension().location().toString(), remote.asLong());
    var chunks = helper.getLevel().getChunkSource();
    helper.assertTrue(
        chunks.getChunkNow(remote.getX() >> 4, remote.getZ() >> 4) == null,
        "Remote fixture chunk initially absent");
    var cache = new HashMap<NodeRegistry.Position, WiredDiscovery.Wire>();
    var previous =
        new WiredDiscovery.Wire(
            List.of(
                new NetworkTopology.Node(UUID.randomUUID(), NetworkTopology.Role.ROOT, "", true)),
            List.of(),
            null);
    cache.put(position, previous);
    var reader = new WorldWireReader(helper.getLevel(), cache::get);
    var unavailable = reader.apply(position);
    helper.assertTrue(
        unavailable != null
            && !unavailable.nodes().getFirst().loaded()
            && unavailable.nodes().getFirst().id().equals(previous.nodes().getFirst().id()),
        "Only known cached identity survives offline");
    helper.assertTrue(
        chunks.getChunkNow(remote.getX() >> 4, remote.getZ() >> 4) == null,
        "Probe never requests remote chunk loading");
    cache.clear();
    helper.assertTrue(
        reader.apply(position) == null, "Unknown unloaded position creates no descriptor");
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    node(helper, ROOT).commission(UUID.randomUUID());
    var local = position(helper, ROOT);
    cache.put(local, reader.apply(local));
    helper.setBlock(ROOT, Blocks.AIR);
    helper.assertTrue(reader.apply(local) == null, "Loaded air rejects retained geometry");
    helper.succeed();
  }
}
