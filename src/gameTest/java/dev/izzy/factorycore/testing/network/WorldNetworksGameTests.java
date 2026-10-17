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

  @GameTest(template = "empty", batch = "network_node_recovery", timeoutTicks = 300)
  public static void rootRepairRetainsGrantsClaimsAndKnownStagingUntilFreshTopology(
      GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var original = root(h, ROOT, owner.id());
    h.setBlock(CABLE, NetworkContent.CABLE.get());
    var drive = drive(h, DRIVE);
    var cell = CellHandleData.read(drive.cell(0));
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var registry = saved.registry();
    var old = original.handle();
    saved
        .network(old.id())
        .setGrants(owner, guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
    var grants = saved.network(old.id()).snapshot();
    var claim = UUID.randomUUID();
    var operation = UUID.randomUUID();
    int[] calls = {0};
    dev.izzy.factorycore.core.resource.StagedTransfer.Snapshot[] staging = {null};
    NetworkTopology.Scope[] scope = {null};
    net.minecraft.nbt.CompoundTag[] physical = {null};
    NetworkNodeEntity[] clone = {null};
    h.startSequence()
        .thenWaitUntil(() -> status(h, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              registry.insert(cell, IRON, 100);
              registry.reserve(cell, claim, IRON, 60);
              scope[0] = runtime(h).membership(position(h, DRIVE)).scope();
              var source =
                  new dev.izzy.factorycore.core.resource.ResourcePort() {
                    public UUID backingId() {
                      return cell.backing();
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return registry.simulateInsert(cell, key, amount).amount();
                    }

                    public long extract(ResourceKey key, long amount) {
                      return registry.extract(cell, key, amount, null).amount();
                    }

                    public long insert(ResourceKey key, long amount) {
                      return registry.insert(cell, key, amount).amount();
                    }
                  };
              var destination =
                  new dev.izzy.factorycore.core.resource.ResourcePort() {
                    private final UUID id = UUID.randomUUID();

                    public UUID backingId() {
                      return id;
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    public long extract(ResourceKey key, long amount) {
                      return 0;
                    }

                    public long insert(ResourceKey key, long amount) {
                      calls[0]++;
                      return 0;
                    }
                  };
              staging[0] =
                  saved
                      .transfers()
                      .start(operation, owner.id(), IRON, source, destination, 10, 10, () -> true);
              h.assertTrue(
                  staging[0].staged() == 10 && registry.stock(cell, IRON).total() == 90,
                  "Real staged extraction conserves90+10");
              physical[0] = original.saveWithFullMetadata(h.getLevel().registryAccess());
              clone[0] = copyNode(h, new BlockPos(4, 1, 3), original, physical[0]);
              h.assertTrue(
                  saved.nodes().validate(old) == NodeRegistry.Failure.CONFLICT
                      && !registry.stock(cell, IRON).loaded(),
                  "Copied controller immediately suspends stock");
              var console = h.getLevel().getServer().createCommandSourceStack();
              var repair = "factorycore recovery node repair " + old.id() + " " + old.generation();
              h.assertTrue(
                  nodeCommand(console.withPermission(0), repair) < 1, "Operator-only node repair");
              h.assertTrue(
                  nodeCommand(console, "factorycore recovery node show " + old.id()) == 1,
                  "Inspect original owner/grants");
              h.assertTrue(
                  nodeCommand(console, "factorycore recovery node list") >= 0,
                  "Bounded node page executes");
              h.assertTrue(
                  nodeCommand(console, "factorycore recovery node show " + cell.backing()) == 0,
                  "Storage cell is not a structural node");
              h.assertTrue(
                  nodeCommand(console, repair + "0") == 0, "Wrong expected generation rejected");
              rejectNodeRepair(h, clone[0], old);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    nodeCommand(
                            h.getLevel().getServer().createCommandSourceStack(),
                            "factorycore recovery node repair " + old.id() + " " + old.generation())
                        == 1,
                    "Wait for shared explicit-repair throttle"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  original.handle().generation() == old.generation() + 1
                      && saved.nodes().validate(old) == NodeRegistry.Failure.STALE,
                  "Repaired controller consumes old references");
              h.assertTrue(
                  grants.equals(saved.network(old.id()).snapshot())
                      && staging[0].equals(saved.transfers().get(operation))
                      && calls[0] == 1,
                  "Owner/grants/generation and known staging unchanged without provider calls");
              h.assertTrue(
                  !registry.stock(cell, IRON).loaded()
                      && registry.stock(cell, IRON).total() == 90
                      && registry.stock(cell, IRON).reserved() == 60,
                  "No routing before normal revalidation");
              var repaired = original.saveWithFullMetadata(h.getLevel().registryAccess());
              original.loadWithComponents(repaired, h.getLevel().registryAccess());
              original.onLoad();
              clone[0].loadWithComponents(physical[0], h.getLevel().registryAccess());
              clone[0].onLoad();
              h.assertTrue(
                  saved.nodes().validate(original.handle()) == NodeRegistry.Failure.OK
                      && !clone[0].diagnostic().isEmpty(),
                  "Stale clone cannot requarantine repaired root");
            })
        .thenWaitUntil(() -> status(h, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              var current = runtime(h).membership(position(h, DRIVE)).scope();
              h.assertTrue(
                  current.network().equals(scope[0].network()) && !current.equals(scope[0]),
                  "Same logical network under a fresh scope");
              h.assertTrue(
                  registry.stock(cell, IRON).loaded()
                      && registry.stock(cell, IRON).total() == 90
                      && registry.stock(cell, IRON).reserved() == 60,
                  "Revalidation restores only unchanged available stock");
              h.assertTrue(
                  runtime(h)
                              .authorize(
                                  guest, position(h, DRIVE), NetworkPermissions.Permission.VIEW)
                          == NetworkAuthority.Decision.ALLOWED
                      && runtime(h)
                              .authorize(
                                  guest, position(h, DRIVE), NetworkPermissions.Permission.WITHDRAW)
                          == NetworkAuthority.Decision.DENIED,
                  "Repair does not widen view-only grant");
              h.assertTrue(
                  staging[0].equals(saved.transfers().get(operation)) && calls[0] == 1,
                  "Routing recovery never replays provider calls");
              var currentHandle = original.handle();
              original.loadWithComponents(physical[0], h.getLevel().registryAccess());
              original.onLoad();
              rejectNodeRepair(h, original, currentHandle);
              h.assertTrue(
                  !registry.stock(cell, IRON).loaded()
                      && saved.nodes().validate(currentHandle) == NodeRegistry.Failure.OK
                      && grants.equals(saved.network(old.id()).snapshot()),
                  "Old original chunk snapshot stays inert without changing canonical owner");
            })
        .thenIdle(20)
        .thenSucceed();
  }

  @GameTest(template = "empty", batch = "network_node_recovery", timeoutTicks = 300)
  public static void gatewayRepairPreservesSegmentPolicyOrientationAndPrivateViews(
      GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var controller = root(h, ROOT, owner.id());
    h.setBlock(
        CABLE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(CABLE);
    gateway.commission(null);
    var drive = drive(h, DRIVE);
    var cell = CellHandleData.read(drive.cell(0));
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var old = gateway.handle();
    var policy = saved.nodes().definition(old).policy();
    saved
        .network(controller.handle().id())
        .setGrants(owner, guest.id(), Set.of(NetworkPermissions.Permission.VIEW));
    var grants = saved.network(controller.handle().id()).snapshot();
    NetworkTopology.Scope[] prior = {null};
    net.minecraft.nbt.CompoundTag[] physical = {null};
    NetworkNodeEntity[] clone = {null};
    h.startSequence()
        .thenWaitUntil(() -> status(h, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              saved.registry().insert(cell, IRON, 50);
              saved.registry().reserve(cell, UUID.randomUUID(), IRON, 20);
              prior[0] = runtime(h).membership(position(h, DRIVE)).scope();
              physical[0] = gateway.saveWithFullMetadata(h.getLevel().registryAccess());
              clone[0] = copyNode(h, new BlockPos(4, 1, 3), gateway, physical[0]);
              h.assertTrue(
                  saved.nodes().validate(old) == NodeRegistry.Failure.CONFLICT
                      && !saved.registry().stock(cell, IRON).loaded(),
                  "Gateway copy suspends child stock");
              h.assertTrue(
                  nodeCommand(
                          h.getLevel().getServer().createCommandSourceStack(),
                          "factorycore recovery node show " + old.id())
                      == 1,
                  "Operator inspects current canonical gateway policy");
              rejectNodeRepair(h, clone[0], old);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    nodeCommand(
                            h.getLevel().getServer().createCommandSourceStack(),
                            "factorycore recovery node repair " + old.id() + " " + old.generation())
                        == 1,
                    "Wait for shared explicit-repair throttle"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  gateway.handle().generation() == old.generation() + 1
                      && saved.nodes().definition(gateway.handle()).policy().equals(policy)
                      && gateway.getBlockState().getValue(BlockStateProperties.FACING)
                          == Direction.EAST,
                  "Lease rotation preserves actual gateway policy and orientation");
              h.assertTrue(
                  !saved.registry().stock(cell, IRON).loaded(),
                  "Child stays offline pending topology publication");
              clone[0].loadWithComponents(physical[0], h.getLevel().registryAccess());
              clone[0].onLoad();
            })
        .thenWaitUntil(() -> status(h, DRIVE, NetworkTopology.Status.VALID))
        .thenExecute(
            () -> {
              var service = runtime(h);
              var child = service.membership(position(h, DRIVE)).scope();
              var parent = service.membership(position(h, ROOT)).scope();
              h.assertTrue(
                  child.segment().equals(old.id())
                      && child.network().equals(prior[0].network())
                      && !child.equals(prior[0]),
                  "Same private segment/network with fresh validated scope");
              h.assertTrue(
                  service
                      .storage(owner, position(h, ROOT), IRON, NetworkPermissions.Permission.VIEW)
                      .segments()
                      .equals(List.of(parent.segment())),
                  "Parent still cannot enumerate child storage");
              h.assertTrue(
                  service
                      .storage(owner, position(h, DRIVE), IRON, NetworkPermissions.Permission.VIEW)
                      .segments()
                      .equals(List.of(child.segment(), parent.segment())),
                  "Child sees only the previously permitted local/ancestor view");
              h.assertTrue(
                  service.authorize(
                              guest, position(h, DRIVE), NetworkPermissions.Permission.WITHDRAW)
                          == NetworkAuthority.Decision.DENIED
                      && grants.equals(saved.network(controller.handle().id()).snapshot()),
                  "Gateway repair cannot widen root grants");
              h.assertTrue(
                  saved.registry().stock(cell, IRON).total() == 50
                      && saved.registry().stock(cell, IRON).reserved() == 20
                      && saved.nodes().validate(old) == NodeRegistry.Failure.STALE,
                  "All stock/claims retained, old gateway inert");
            })
        .thenIdle(20)
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void failedNetworkNodeAuditLeavesControllerAndGatewayEvidenceUnchanged(
      GameTestHelper h) {
    var controller = root(h, ROOT, UUID.randomUUID());
    h.setBlock(
        DRIVE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(DRIVE);
    gateway.commission(null);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var grants = saved.network(controller.handle().id()).snapshot();
    int index = 0;
    for (var original : List.of(controller, gateway)) {
      var raw = original.saveWithFullMetadata(h.getLevel().registryAccess());
      copyNode(h, new BlockPos(1 + index++ * 2, 1, 3), original, raw);
      var old = original.handle();
      var node = saved.nodes().evidence(old.id());
      var folder =
          h.getLevel()
              .getServer()
              .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
              .resolve("factorycore-device-recovery");
      try {
        java.nio.file.Files.createDirectories(folder);
        var blocker =
            java.nio.file.Files.createDirectory(
                folder.resolve("unexpected-node-" + UUID.randomUUID()));
        boolean refused = false;
        try {
          original.repairConflict(old);
        } catch (java.io.IOException expected) {
          refused = true;
        } finally {
          java.nio.file.Files.delete(blocker);
        }
        h.assertTrue(refused, "Audit failure prevents structural repair");
      } catch (java.io.IOException failure) {
        throw new AssertionError(failure);
      }
      h.assertTrue(
          node.equals(saved.nodes().evidence(old.id()))
              && old.equals(original.handle())
              && raw.equals(original.saveWithFullMetadata(h.getLevel().registryAccess()))
              && grants.equals(saved.network(controller.handle().id()).snapshot()),
          "Failed audit retains all lease/policy/grants/physical evidence");
    }
    h.succeed();
  }

  private static NetworkNodeEntity copyNode(
      GameTestHelper h,
      BlockPos pos,
      NetworkNodeEntity original,
      net.minecraft.nbt.CompoundTag raw) {
    h.setBlock(pos, original.getBlockState());
    NetworkNodeEntity copy = h.getBlockEntity(pos);
    copy.loadWithComponents(raw, h.getLevel().registryAccess());
    copy.onLoad();
    return copy;
  }

  private static void rejectNodeRepair(
      GameTestHelper h, NetworkNodeEntity node, NodeRegistry.Handle expected) {
    boolean refused = false;
    try {
      node.repairConflict(expected);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      refused = true;
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
    h.assertTrue(refused, "Unsupported node repair refuses");
  }

  private static int nodeCommand(net.minecraft.commands.CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) {
      return -1;
    }
  }
}
