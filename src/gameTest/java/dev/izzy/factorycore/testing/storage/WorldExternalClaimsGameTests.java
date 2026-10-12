package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.ConnectorFixture;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WorldExternalClaimsGameTests {
  private WorldExternalClaimsGameTests() {}

  private static void scenario(GameTestHelper helper, int mode) {
    var rootPos = new BlockPos(1, 1, 1);
    var connectorPos = new BlockPos(3, 1, 1);
    var drivePos = new BlockPos(2, 1, 2);
    var admin = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var actor = mode == 1 ? new NetworkPermissions.Actor(UUID.randomUUID(), false) : admin;
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    helper.setBlock(rootPos, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(rootPos);
    root.commission(admin.id());
    helper.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    helper.setBlock(
        connectorPos,
        ConnectorContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ConnectorEntity connector = helper.getBlockEntity(connectorPos);
    connector.place(new ItemStack(ConnectorContent.ITEM.get()));
    helper.setBlock(new BlockPos(4, 1, 1), ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(new BlockPos(4, 1, 1));
    provider.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
    helper.setBlock(drivePos, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(drivePos);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    var service = runtime.transfers();
    var origin =
        new NodeRegistry.Position(
            helper.getLevel().dimension().location().toString(),
            helper.absolutePos(rootPos).asLong());
    var target =
        new NodeRegistry.Position(origin.dimension(), helper.absolutePos(drivePos).asLong());
    var key =
        new PlatformResourceCodec(helper.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.IRON_INGOT));
    WorldConnectors.Handle[] endpoint = {null};
    OperationResult[] claim = {null, null};
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(target).status() == NetworkTopology.Status.VALID
                        && runtime.membership(connector.position()).status()
                            == NetworkTopology.Status.VALID,
                    "Endpoints ready"))
        .thenExecute(
            () -> {
              endpoint[0] = runtime.connectors().bind(connector).handle();
              saved
                  .network(root.handle().id())
                  .setGrants(
                      admin,
                      guest.id(),
                      Set.of(
                          NetworkPermissions.Permission.WITHDRAW,
                          NetworkPermissions.Permission.DEPOSIT));
              if (mode == 1)
                saved
                    .network(root.handle().id())
                    .setGrants(
                        admin,
                        actor.id(),
                        Set.of(
                            NetworkPermissions.Permission.WITHDRAW,
                            NetworkPermissions.Permission.DEPOSIT));
              service.claim(
                  UUID.randomUUID(),
                  actor,
                  origin,
                  endpoint[0],
                  key,
                  48,
                  false,
                  value -> claim[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(claim[0] != null, "First claim callback"))
        .thenExecute(
            () ->
                service.claim(
                    UUID.randomUUID(),
                    guest,
                    origin,
                    endpoint[0],
                    key,
                    40,
                    false,
                    value -> claim[1] = value))
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    claim[0] != null && claim[1] != null, "Competing claim callbacks"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    claim[0].amount() == 48 && claim[1].reason() == OperationResult.Reason.SHORTAGE,
                    "Only one competing reservation fits"))
        .thenWaitUntil(() -> helper.assertTrue(claim[0].amount() == 48, "Actor owns 48 held items"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  saved.externalClaims().held(endpoint[0].backing(), actor.id(), key) == 48
                      && provider.items.getStackInSlot(0).getCount() == 64,
                  "Claims never debit resource stock");
              if (mode == 2) {
                provider.failAfterExtraction = true;
                service.submitClaimed(
                    UUID.randomUUID(),
                    actor,
                    origin,
                    endpoint[0],
                    new WorldStorageTransfers.Digital(cell),
                    key,
                    30,
                    value -> result[0] = value);
              } else if (mode == 1) {
                saved.network(root.handle().id()).setGrants(admin, actor.id(), Set.of());
                claim[0] = null;
                claim[1] = null;
                service.claim(
                    UUID.randomUUID(),
                    actor,
                    origin,
                    endpoint[0],
                    key,
                    1,
                    false,
                    value -> claim[1] = value);
                service.claim(
                    UUID.randomUUID(),
                    actor,
                    origin,
                    endpoint[0],
                    key,
                    48,
                    true,
                    value -> claim[0] = value);
                result[0] =
                    new WorldStorageTransfers.Result(
                        UUID.randomUUID(), OperationResult.Reason.OK, null);
              } else
                service.submit(
                    UUID.randomUUID(),
                    actor,
                    origin,
                    new WorldStorageTransfers.External(endpoint[0]),
                    new WorldStorageTransfers.Digital(cell),
                    key,
                    64,
                    value -> result[0] = value);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    result[0] != null && (mode != 1 || claim[0] != null && claim[1] != null),
                    "First mutation callback"))
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    mode != 2
                        || runtime.membership(origin).status() == NetworkTopology.Status.VALID,
                    "Quarantine topology revalidated before cleanup admission"))
        .thenExecute(
            () -> {
              if (mode == 0) {
                helper.assertTrue(
                    result[0].transfer().delivered() == 16
                        && provider.items.getStackInSlot(0).getCount() == 48
                        && saved.externalClaims().held(endpoint[0].backing(), actor.id(), key)
                            == 48,
                    "Background move uses only unclaimed stock");
                result[0] = null;
                service.submitClaimed(
                    UUID.randomUUID(),
                    guest,
                    origin,
                    endpoint[0],
                    new WorldStorageTransfers.Digital(cell),
                    key,
                    30,
                    value -> result[0] = value);
              } else if (mode == 1) {
                helper.assertTrue(
                    claim[0].amount() == 48
                        && claim[1].reason() == OperationResult.Reason.DENIED
                        && saved.externalClaims().held(endpoint[0].backing(), actor.id(), key) == 0
                        && provider.items.getStackInSlot(0).getCount() == 64,
                    "Revocation permits metadata cleanup without withdrawal");
              } else {
                helper.assertTrue(
                    result[0].transfer().pending() == StagedTransfer.PendingCall.EXTRACT
                        && saved.externalClaims().held(endpoint[0].backing(), actor.id(), key)
                            == 48,
                    "Unknown extraction retains full claim and journal evidence");
                claim[0] = null;
                var cleanupAdmission =
                    service.claim(
                        UUID.randomUUID(),
                        actor,
                        origin,
                        endpoint[0],
                        key,
                        48,
                        true,
                        value -> claim[0] = value);
                helper.assertTrue(
                    cleanupAdmission.rejection() == OperationResult.Reason.OK,
                    "Uncertain cleanup admitted before waiting for callback");
              }
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    result[0] != null && (mode != 2 || claim[0] != null), "Second callback"))
        .thenExecute(
            () -> {
              if (mode == 0) {
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.RESERVED
                        && result[0].transfer() == null,
                    "Another actor cannot adopt held stock");
                result[0] = null;
                service.submitClaimed(
                    UUID.randomUUID(),
                    actor,
                    origin,
                    endpoint[0],
                    new WorldStorageTransfers.Digital(cell),
                    key,
                    30,
                    value -> result[0] = value);
              } else if (mode == 2)
                helper.assertTrue(
                    claim[0].reason() == OperationResult.Reason.UNCERTAIN
                        && saved.externalClaims().held(endpoint[0].backing(), actor.id(), key) == 48
                        && provider.items.getStackInSlot(0).getCount() == 34
                        && saved.registry().stock(cell, key).total() == 0,
                    "Uncertain holds cannot be released or refunded");
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Claimed callback complete"))
        .thenExecute(
            () -> {
              if (mode == 0)
                helper.assertTrue(
                    result[0].transfer().delivered() == 30
                        && saved.externalClaims().held(endpoint[0].backing(), actor.id(), key) == 18
                        && provider.items.getStackInSlot(0).getCount() == 18
                        && saved.registry().stock(cell, key).total() == 46,
                    "Claimed move debits only actual units and conserves stock");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void competingClaimsProtectBackgroundAndOtherPrincipals(GameTestHelper helper) {
    scenario(helper, 0);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void revokedActorCanReleaseUnusedHoldWithoutResourceMutation(
      GameTestHelper helper) {
    scenario(helper, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void uncertainClaimedExtractionBlocksHoldRelease(GameTestHelper helper) {
    scenario(helper, 2);
  }
}
