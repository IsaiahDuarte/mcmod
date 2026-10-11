package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WorldNetworksGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(3, 1, 1);
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private WorldNetworksGameTests() {}

  private static WorldNetworks runtime(GameTestHelper helper) {
    return WorldNetworks.get(helper.getLevel().getServer());
  }

  private static NodeRegistry.Position position(GameTestHelper helper, BlockPos relative) {
    return new NodeRegistry.Position(
        helper.getLevel().dimension().location().toString(), helper.absolutePos(relative).asLong());
  }

  private static NetworkNodeEntity root(GameTestHelper helper, BlockPos relative, UUID owner) {
    helper.setBlock(relative, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity node = helper.getBlockEntity(relative);
    node.commission(owner);
    return node;
  }

  private static StorageDriveEntity drive(GameTestHelper helper, BlockPos relative) {
    helper.setBlock(relative, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(relative);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    return drive;
  }

  private static void status(
      GameTestHelper helper, BlockPos relative, NetworkTopology.Status expected) {
    var actual = runtime(helper).membership(position(helper, relative)).status();
    helper.assertTrue(
        actual == expected,
        "Expected " + expected + "; got " + actual + ": " + runtime(helper).diagnostic());
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void serverTicksActivateDisconnectAndReconnectExactClaims(GameTestHelper helper) {
    root(helper, ROOT, UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    var drive = drive(helper, DRIVE);
    var handle = CellHandleData.read(drive.cell(0));
    var registry = DeviceSavedData.get(helper.getLevel().getServer()).registry();
    var job = UUID.randomUUID();
    helper.assertTrue(
        !registry.stock(handle, IRON).loaded(),
        "Placement starts offline before post-tick rebuilding");
    helper
        .startSequence()
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  registry.insert(handle, IRON, 100).amount() == 100,
                  "Validated network automatically activates cell");
              registry.reserve(handle, job, IRON, 60);
              helper.setBlock(CABLE, Blocks.AIR);
              helper.assertTrue(
                  !registry.stock(handle, IRON).loaded()
                      && registry.stock(handle, IRON).available() == 0,
                  "Cable removal denies access immediately");
              helper.assertTrue(
                  registry.extract(handle, IRON, 100, job).reason()
                      == OperationResult.Reason.OFFLINE,
                  "Reserved extraction cannot use stale routing");
            })
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.NO_ROOT))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  registry.stock(handle, IRON).total() == 100
                      && registry.stock(handle, IRON).reserved() == 60,
                  "Detached fragments preserve exact stock and claims");
              // Repeated notifications before a tick must coalesce without replacement-chain
              // failure.
              for (int i = 0; i < 100; i++)
                WorldNetworks.changed(helper.getLevel(), helper.absolutePos(DRIVE));
              helper.setBlock(CABLE, NetworkContent.CABLE.get());
            })
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () ->
                helper.assertTrue(
                    registry.extract(handle, IRON, 100, job).amount() == 60
                        && registry.stock(handle, IRON).total() == 40,
                    "Reconnection preserves claim ownership and consumes actual stock"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void multipleRootsSuspendOnlyTheirAffectedRegion(GameTestHelper helper) {
    root(helper, ROOT, UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    drive(helper, DRIVE);
    var independentRoot = new BlockPos(1, 1, 3);
    var independentDrive = new BlockPos(2, 1, 3);
    root(helper, independentRoot, UUID.randomUUID());
    drive(helper, independentDrive);
    var extra = new BlockPos(3, 1, 2);
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              status(helper, DRIVE, NetworkTopology.Status.VALID);
              status(helper, independentDrive, NetworkTopology.Status.VALID);
            })
        .thenExecute(
            () -> {
              root(helper, extra, UUID.randomUUID());
              status(helper, DRIVE, NetworkTopology.Status.REBUILDING);
              status(helper, independentRoot, NetworkTopology.Status.VALID);
            })
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.MULTIPLE_ROOTS))
        .thenExecute(() -> helper.setBlock(extra, Blocks.AIR))
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  // The fixture borrows a Minecraft-owned chunk, which must never be closed.
  @SuppressWarnings("PMD.CloseResource")
  public static void firstNeighborChunkLoadInvalidatesExistingBoundaryPublication(
      GameTestHelper helper) {
    // Deliberate fixture loading is distinct from the production nonloading reader.
    var neighbor = helper.getLevel().getChunk(helper.absolutePos(ROOT).offset(-16, 0, 0));
    root(helper, ROOT, UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    var drive = drive(helper, DRIVE);
    var handle = CellHandleData.read(drive.cell(0));
    var registry = DeviceSavedData.get(helper.getLevel().getServer()).registry();
    helper
        .startSequence()
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              registry.insert(handle, IRON, 100);
              // Invoke the typed lifecycle adapter; the fixture keeps the chunk loaded.
              WorldNetworks.loaded(
                  new net.neoforged.neoforge.event.level.ChunkEvent.Load(neighbor, false));
              status(helper, DRIVE, NetworkTopology.Status.REBUILDING);
              helper.assertTrue(
                  !registry.stock(handle, IRON).loaded()
                      && registry.extract(handle, IRON, 1, null).reason()
                          == OperationResult.Reason.OFFLINE,
                  "An unindexed neighboring chunk event immediately invalidates old routing");
            })
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () ->
                helper.assertTrue(
                    registry.stock(handle, IRON).total() == 100
                        && registry.stock(handle, IRON).loaded(),
                    "Event revalidation preserves stock"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void copiedRootLeaseDeniesOriginalStockBeforeRediscovery(GameTestHelper helper) {
    var original = root(helper, ROOT, UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    var drive = drive(helper, DRIVE);
    var handle = CellHandleData.read(drive.cell(0));
    var registry = DeviceSavedData.get(helper.getLevel().getServer()).registry();
    var job = UUID.randomUUID();
    helper
        .startSequence()
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              registry.insert(handle, IRON, 100);
              registry.reserve(handle, job, IRON, 60);
              var copy = new BlockPos(1, 1, 3);
              var saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
              helper.setBlock(copy, NetworkContent.CONTROLLER.get());
              NetworkNodeEntity cloned = helper.getBlockEntity(copy);
              cloned.loadWithComponents(saved, helper.getLevel().registryAccess());
              cloned.onLoad();
              helper.assertTrue(!cloned.diagnostic().isEmpty(), "Copied root is quarantined");
              status(helper, DRIVE, NetworkTopology.Status.UNLOADED);
              helper.assertTrue(
                  !registry.stock(handle, IRON).loaded()
                      && registry.stock(handle, IRON).total() == 100
                      && registry.stock(handle, IRON).reserved() == 60,
                  "Canonical structural conflict immediately preserves original stock offline");
              helper.assertTrue(
                  registry.extract(handle, IRON, 100, job).reason()
                      == OperationResult.Reason.OFFLINE,
                  "Original published geometry cannot bypass current root lease validation");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void currentPrivateViewsAndQueuedRevocationUseSharedAuthority(
      GameTestHelper helper) {
    var owner = UUID.randomUUID();
    var root = root(helper, ROOT, owner);
    helper.setBlock(
        CABLE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gate = helper.getBlockEntity(CABLE);
    gate.commission(null);
    drive(helper, DRIVE);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    NetworkAuthority.Decision[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> status(helper, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              var service = runtime(helper);
              var parent = service.membership(position(helper, ROOT)).scope();
              var child = service.membership(position(helper, DRIVE)).scope();
              var actor = new NetworkPermissions.Actor(owner, false);
              helper.assertTrue(
                  service
                      .storage(
                          actor, position(helper, ROOT), IRON, NetworkPermissions.Permission.VIEW)
                      .segments()
                      .equals(List.of(parent.segment())),
                  "Parent does not enumerate child storage");
              helper.assertTrue(
                  service
                      .storage(
                          actor, position(helper, DRIVE), IRON, NetworkPermissions.Permission.VIEW)
                      .segments()
                      .equals(List.of(child.segment(), parent.segment())),
                  "Child inherits only permitted ancestor storage");
              var grants = saved.network(root.handle().id());
              grants.setGrants(actor, guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
              service
                  .scheduler()
                  .submit(
                      parent.network(),
                      UUID.randomUUID(),
                      "revoked-query",
                      1,
                      () ->
                          result[0] =
                              service
                                  .storage(
                                      guest,
                                      position(helper, DRIVE),
                                      IRON,
                                      NetworkPermissions.Permission.VIEW)
                                  .decision());
              grants.setGrants(actor, guest.id(), Set.of());
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    result[0] == NetworkAuthority.Decision.DENIED,
                    "Queued query rechecks revoked grants"))
        .thenSucceed();
  }
}
