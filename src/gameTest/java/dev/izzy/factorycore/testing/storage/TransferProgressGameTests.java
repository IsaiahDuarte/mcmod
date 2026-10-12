package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.*;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TransferProgressGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(3, 1, 1);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private TransferProgressGameTests() {}

  private static void scenario(GameTestHelper helper, int mode) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var actor = mode == 1 ? new NetworkPermissions.Actor(UUID.randomUUID(), false) : owner;
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(owner.id());
    helper.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var source = CellHandleData.read(drive.cell(0));
    var target = CellHandleData.read(drive.cell(1));
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    var origin =
        new NodeRegistry.Position(
            helper.getLevel().dimension().location().toString(), helper.absolutePos(ROOT).asLong());
    var location =
        new NodeRegistry.Position(origin.dimension(), helper.absolutePos(DRIVE).asLong());
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(location).status() == NetworkTopology.Status.VALID,
                    "Storage ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(source, KEY, mode == 6 ? 300 : 100);
              if (mode == 1)
                saved
                    .network(root.handle().id())
                    .setGrants(
                        owner,
                        actor.id(),
                        Set.of(
                            NetworkPermissions.Permission.WITHDRAW,
                            NetworkPermissions.Permission.DEPOSIT));
              var scope = runtime.membership(origin).scope();
              var context =
                  new TransferContext(
                      saved.registry().world(),
                      scope.network(),
                      scope.segment(),
                      origin,
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          source.backing(),
                          source.backing(),
                          source.generation(),
                          0),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          target.backing(),
                          target.backing(),
                          target.generation(),
                          0));
              var input =
                  new ResourcePort() {
                    @Override
                    public UUID backingId() {
                      return source.backing();
                    }

                    @Override
                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    @Override
                    public long extract(ResourceKey key, long amount) {
                      return saved.registry().extract(source, key, amount, null).amount();
                    }

                    @Override
                    public long insert(ResourceKey key, long amount) {
                      return saved.registry().insert(source, key, amount).amount();
                    }
                  };
              var partial =
                  new ResourcePort() {
                    @Override
                    public UUID backingId() {
                      return target.backing();
                    }

                    @Override
                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    @Override
                    public long extract(ResourceKey key, long amount) {
                      throw new IllegalStateException("Never extract destination");
                    }

                    @Override
                    public long insert(ResourceKey key, long amount) {
                      return 0;
                    }
                  };
              saved
                  .transfers()
                  .start(
                      id,
                      actor.id(),
                      KEY,
                      input,
                      partial,
                      mode == 6 ? 250 : 64,
                      mode == 6 ? 250 : 64,
                      () -> true,
                      mode == 5 ? null : context);
              saved.transfers().unbind(id);
              if (mode == 2) drive.removeCell(0);
              if (mode == 3) drive.removeCell(1);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(origin).status() == NetworkTopology.Status.VALID,
                    "Accepted origin ready after cell change"))
        .thenExecute(
            () -> {
              if (mode == 4) {
                var before = saved.transfers().get(id);
                var wrong = new NetworkPermissions.Actor(UUID.randomUUID(), true);
                helper.assertTrue(
                    runtime
                            .transfers()
                            .progress(id, wrong, false, value -> result[0] = value)
                            .rejection()
                        == OperationResult.Reason.DENIED,
                    "Operator flag cannot adopt another principal's staging");
                helper.assertTrue(
                    saved.transfers().get(id).equals(before),
                    "Unauthorized progress preserves evidence");
              }
              if (mode == 5) {
                helper.assertTrue(
                    runtime
                            .transfers()
                            .progress(id, actor, false, value -> result[0] = value)
                            .rejection()
                        == OperationResult.Reason.UNSUPPORTED,
                    "Legacy record has no guessed context");
                result[0] =
                    new WorldStorageTransfers.Result(
                        id, OperationResult.Reason.UNSUPPORTED, saved.transfers().get(id));
              } else {
                runtime.transfers().progress(id, actor, mode == 3, value -> result[0] = value);
                if (mode == 1)
                  saved.network(root.handle().id()).setGrants(owner, actor.id(), Set.of());
              }
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Progress result"))
        .thenExecute(
            () -> {
              if (mode == 0 || mode == 4)
                helper.assertTrue(
                    result[0].transfer().delivered() == 64
                        && result[0].transfer().staged() == 0
                        && saved.registry().stock(source, KEY).total() == 36
                        && saved.registry().stock(target, KEY).total() == 64,
                    "Only owned staging delivered; source never extracted twice");
              if (mode == 1) {
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && result[0].transfer().staged() == 64
                        && saved.registry().stock(source, KEY).total() == 36,
                    "Execution-time revocation retains staging");
                result[0] = null;
                runtime.transfers().progress(id, actor, true, value -> result[0] = value);
              }
              if (mode == 2)
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.OFFLINE
                        && result[0].transfer().staged() == 64
                        && saved.registry().stock(target, KEY).total() == 0,
                    "Stale source cannot progress or lose staging");
              if (mode == 3)
                helper.assertTrue(
                    result[0].transfer().returned() == 64
                        && result[0].transfer().staged() == 0
                        && saved.registry().stock(source, KEY).total() == 100,
                    "Cleanup does not depend on missing destination");
              if (mode == 5)
                helper.assertTrue(
                    result[0].transfer().staged() == 64
                        && saved.registry().stock(source, KEY).total() == 36,
                    "Legacy staging preserved without replay");
              if (mode == 6) {
                helper.assertTrue(
                    result[0].transfer().delivered() == 128
                        && result[0].transfer().staged() == 122
                        && saved.registry().stock(source, KEY).total() == 50
                        && saved.registry().stock(target, KEY).total() == 128,
                    "First recovery step respects item quantum and conserves remainder");
                result[0] = null;
                runtime.transfers().progress(id, actor, false, value -> result[0] = value);
              }
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Cleanup callback complete"))
        .thenExecute(
            () -> {
              if (mode == 1)
                helper.assertTrue(
                    result[0].transfer().returned() == 64
                        && result[0].transfer().staged() == 0
                        && saved.registry().stock(source, KEY).total() == 100
                        && saved.registry().stock(target, KEY).total() == 0,
                    "Revocation permits only owned return, not new withdrawal");
              if (mode == 6)
                helper.assertTrue(
                    result[0].transfer().delivered() == 250
                        && result[0].transfer().staged() == 0
                        && saved.registry().stock(source, KEY).total() == 50
                        && saved.registry().stock(target, KEY).total() == 250,
                    "Second scheduled step completes exactly without new extraction");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void unboundKnownStagingDeliversWithoutExtraction(GameTestHelper helper) {
    scenario(helper, 0);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void revocationBlocksDeliveryButAllowsOwnedCleanup(GameTestHelper helper) {
    scenario(helper, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void staleSourcePreservesOwnedStaging(GameTestHelper helper) {
    scenario(helper, 2);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void missingDestinationDoesNotBlockSourceCleanup(GameTestHelper helper) {
    scenario(helper, 3);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void anotherPrincipalCannotAdoptStaging(GameTestHelper helper) {
    scenario(helper, 4);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void legacyMissingContextDoesNotReplay(GameTestHelper helper) {
    scenario(helper, 5);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void recoveryQuantumRetainsRemainderAcrossScheduledSteps(GameTestHelper helper) {
    scenario(helper, 6);
  }
}
