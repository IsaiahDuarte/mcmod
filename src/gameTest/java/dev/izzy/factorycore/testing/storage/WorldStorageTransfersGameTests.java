package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WorldStorageTransfersGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(3, 1, 1);
  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private WorldStorageTransfersGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper helper, BlockPos position) {
    return new NodeRegistry.Position(
        helper.getLevel().dimension().location().toString(), helper.absolutePos(position).asLong());
  }

  private static void scenario(GameTestHelper helper, int change) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(owner.id());
    if (change == 4) {
      helper.setBlock(
          CABLE,
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(
                  net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                  net.minecraft.core.Direction.EAST));
      NetworkNodeEntity gate = helper.getBlockEntity(CABLE);
      gate.commission(null);
    } else helper.setBlock(CABLE, NetworkContent.CABLE.get());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var source = CellHandleData.read(drive.cell(0));
    var destination = CellHandleData.read(drive.cell(1));
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(position(helper, DRIVE)).status()
                        == NetworkTopology.Status.VALID,
                    "Drive ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(source, IRON, 100);
              saved.registry().reserve(source, UUID.randomUUID(), IRON, 60);
              if (change == 1)
                saved
                    .network(root.handle().id())
                    .setGrants(
                        owner,
                        guest.id(),
                        Set.of(
                            NetworkPermissions.Permission.WITHDRAW,
                            NetworkPermissions.Permission.DEPOSIT));
              var submission =
                  runtime
                      .transfers()
                      .submit(
                          id,
                          change == 1 ? guest : owner,
                          position(helper, ROOT),
                          source,
                          destination,
                          IRON,
                          128,
                          value -> result[0] = value);
              helper.assertTrue(
                  submission.admission() == FairScheduler.Admission.ACCEPTED, "Move queued");
              if (change == 5) {
                helper.assertTrue(
                    !runtime.transfers().cancelQueued(id, guest),
                    "Another principal cannot cancel");
                var network = runtime.membership(position(helper, ROOT)).scope().network();
                boolean[] unrelated = {false};
                runtime
                    .scheduler()
                    .submit(network, owner.id(), "other-operation", 1, () -> unrelated[0] = true);
                helper.assertTrue(
                    runtime.transfers().cancelQueued(id, owner), "Owner cancels undispatched move");
                helper.assertTrue(
                    runtime.scheduler().isQueued(network, owner.id(), "other-operation"),
                    "Targeted cancellation preserves unrelated operation");
                var retry =
                    runtime
                        .transfers()
                        .submit(
                            id,
                            owner,
                            position(helper, ROOT),
                            source,
                            destination,
                            IRON,
                            128,
                            value -> result[0] = value);
                helper.assertTrue(
                    retry.admission() == FairScheduler.Admission.ACCEPTED,
                    "Cancellation releases ticket");
                runtime.scheduler().cancelProgram(network, owner.id());
                retry =
                    runtime
                        .transfers()
                        .submit(
                            id,
                            owner,
                            position(helper, ROOT),
                            source,
                            destination,
                            IRON,
                            128,
                            value -> result[0] = value);
                helper.assertTrue(
                    retry.admission() == FairScheduler.Admission.ACCEPTED,
                    "External cancellation reclaimed on admission");
                helper.assertTrue(
                    !unrelated[0] && result[0] == null && !saved.transfers().contains(id),
                    "Cancellation precedes mutation and completion");
              }
              if (change == 1)
                saved.network(root.handle().id()).setGrants(owner, guest.id(), Set.of());
              if (change == 2) helper.setBlock(CABLE, Blocks.AIR);
              if (change == 3) drive.removeCell(0);
              if (change == 6) {
                helper.setBlock(ROOT, Blocks.AIR);
                helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
                NetworkNodeEntity replacement = helper.getBlockEntity(ROOT);
                replacement.commission(owner.id());
              }
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Completion reported"))
        .thenExecute(
            () -> {
              if (change == 0 || change == 5) {
                var context = saved.transfers().context(id);
                helper.assertTrue(
                    context != null
                        && context.origin().equals(position(helper, ROOT))
                        && context.source().backing().equals(source.backing())
                        && context.destination().backing().equals(destination.backing()),
                    "World transfer retains accepted endpoint and origin context");
                helper.assertTrue(
                    result[0].transfer().delivered() == 40
                        && saved.registry().stock(source, IRON).total() == 60
                        && saved.registry().stock(source, IRON).reserved() == 60
                        && saved.registry().stock(destination, IRON).total() == 40,
                    "Only unreserved stock moved exactly once");
              } else {
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && result[0].transfer() == null
                        && !saved.transfers().contains(id),
                    "Changed authority rejects before recording or extraction");
                var state =
                    saved.registry().snapshot().devices().stream()
                        .filter(value -> value.ledger().id().equals(source.backing()))
                        .findFirst()
                        .orElseThrow();
                helper.assertTrue(
                    state.ledger().contents().get(IRON) == 100,
                    "Rejected move preserved source stock");
                helper.assertTrue(
                    saved.registry().stock(destination, IRON).total() == 0,
                    "Rejected move deposited nothing");
              }
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedMoveCannotMigrateToReplacementRoot(GameTestHelper helper) {
    scenario(helper, 6);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void bankTransferUsesPhysicalCapacityAndCanonicalEnergy(GameTestHelper helper) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(actor.id());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    var other = new BlockPos(2, 1, 2);
    var tier = dev.izzy.factorycore.core.storage.EnergyBankTier.STARTER;
    helper.setBlock(DRIVE, dev.izzy.factorycore.platform.storage.EnergyBankContent.block(tier));
    helper.setBlock(other, dev.izzy.factorycore.platform.storage.EnergyBankContent.block(tier));
    dev.izzy.factorycore.platform.storage.EnergyBankEntity source = helper.getBlockEntity(DRIVE);
    dev.izzy.factorycore.platform.storage.EnergyBankEntity destination =
        helper.getBlockEntity(other);
    source.place(new ItemStack(dev.izzy.factorycore.platform.storage.EnergyBankContent.item(tier)));
    destination.place(
        new ItemStack(dev.izzy.factorycore.platform.storage.EnergyBankContent.item(tier)));
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(position(helper, DRIVE)).status()
                            == NetworkTopology.Status.VALID
                        && runtime.membership(position(helper, other)).status()
                            == NetworkTopology.Status.VALID,
                    "Both banks ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(source.handle(), ResourceKey.ENERGY, 1000);
              saved
                  .registry()
                  .insert(destination.handle(), ResourceKey.ENERGY, tier.capacity() - 300);
              var submission =
                  runtime
                      .transfers()
                      .submit(
                          UUID.randomUUID(),
                          actor,
                          position(helper, ROOT),
                          source.handle(),
                          destination.handle(),
                          ResourceKey.ENERGY,
                          1000,
                          value -> result[0] = value);
              helper.assertTrue(
                  submission.admission() == FairScheduler.Admission.ACCEPTED, "Bank move queued");
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Bank result reported"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    result[0].transfer().delivered() == 300
                        && source.getEnergyStored() == 700
                        && destination.getEnergyStored() == tier.capacity(),
                    "FE conserved with partial destination capacity through physical ports"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedCancellationReclaimsAdmission(GameTestHelper helper) {
    scenario(helper, 5);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void parentCannotMovePrivateChildStock(GameTestHelper helper) {
    scenario(helper, 4);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void movesOnlyUnreservedStock(GameTestHelper helper) {
    scenario(helper, 0);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedMoveRechecksRevokedGrants(GameTestHelper helper) {
    scenario(helper, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedMoveRechecksDisconnectedCable(GameTestHelper helper) {
    scenario(helper, 2);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void queuedMoveRechecksRemovedCell(GameTestHelper helper) {
    scenario(helper, 3);
  }
}
