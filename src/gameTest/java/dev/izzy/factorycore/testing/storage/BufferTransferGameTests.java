package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferTransferGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos BUFFER = new BlockPos(3, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(2, 1, 2);

  private BufferTransferGameTests() {}

  private static ItemStack named(int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Shared buffered transfer identity"));
    return stack;
  }

  private static NodeRegistry.Position position(GameTestHelper helper, BlockPos pos) {
    return new NodeRegistry.Position(
        helper.getLevel().dimension().location().toString(), helper.absolutePos(pos).asLong());
  }

  private static void scenario(GameTestHelper helper, int mode) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var drivePosition = mode == 6 ? new BlockPos(1, 1, 2) : DRIVE;
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(actor.id());
    if (mode == 6) {
      helper.setBlock(
          new BlockPos(2, 1, 1),
          NetworkContent.GATEWAY
              .get()
              .defaultBlockState()
              .setValue(
                  net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                  net.minecraft.core.Direction.EAST));
      NetworkNodeEntity gate = helper.getBlockEntity(new BlockPos(2, 1, 1));
      gate.commission(null);
    } else helper.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), actor.id());
    helper.setBlock(drivePosition, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(drivePosition);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    var service = runtime.transfers();
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(named(1));
    var endpoint =
        new WorldStorageTransfers.Buffered(buffer.handle(), buffer.reference().configuration());
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null, null, null};
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(position(helper, drivePosition)).status()
                            == NetworkTopology.Status.VALID
                        && runtime.membership(buffer.position()).status()
                            == NetworkTopology.Status.VALID,
                    "Buffer and storage scope ready"))
        .thenExecute(
            () -> {
              if (mode == 1) {
                for (int slot = 0; slot < 9; slot++)
                  buffer.capability().insertItem(slot, new ItemStack(Items.GOLD_INGOT, 64), false);
                saved.registry().insert(cell, key, 64);
              } else if (mode == 8) {
                saved.registry().insert(cell, key, 128);
              } else {
                buffer.capability().insertItem(0, named(64), false);
                if (mode == 4) buffer.capability().insertItem(1, named(64), false);
                if (mode == 0) saved.registry().insert(cell, key, 8192 - 13);
              }
              if (mode == 3) {
                var tag = buffer.saveWithFullMetadata(helper.getLevel().registryAccess());
                tag.getCompound(BufferData.FIELD).putLong("Configuration", 2);
                buffer.loadWithComponents(tag, helper.getLevel().registryAccess());
                buffer.onLoad();
              }
              if (mode == 5) {
                var scope = runtime.membership(position(helper, ROOT)).scope();
                var context =
                    new TransferContext(
                        saved.registry().world(),
                        scope.network(),
                        scope.segment(),
                        position(helper, ROOT),
                        new TransferContext.Endpoint(
                            TransferContext.Type.BUFFER,
                            buffer.handle().id(),
                            buffer.handle().id(),
                            buffer.handle().generation(),
                            1),
                        new TransferContext.Endpoint(
                            TransferContext.Type.DIGITAL,
                            cell.backing(),
                            cell.backing(),
                            cell.generation(),
                            0));
                ResourcePort fullAfterSimulation =
                    new ResourcePort() {
                      public UUID backingId() {
                        return cell.backing();
                      }

                      public long simulateInsert(ResourceKey resource, long amount) {
                        return Math.min(64, amount);
                      }

                      public long insert(ResourceKey resource, long amount) {
                        return 0;
                      }

                      public long extract(ResourceKey resource, long amount) {
                        throw new IllegalStateException("Fixture never extracts target");
                      }
                    };
                saved
                    .transfers()
                    .start(
                        id,
                        actor.id(),
                        key,
                        buffer.inventory(),
                        fullAfterSimulation,
                        64,
                        64,
                        () -> true,
                        context);
                helper.assertTrue(
                    saved.transfers().get(id).staged() == 64 && buffer.inventory().total(key) == 0,
                    "Known source debit is owned staging");
                buffer.capability().insertItem(0, named(24), false);
                saved.transfers().unbind(id);
              }
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(position(helper, ROOT)).status()
                        == NetworkTopology.Status.VALID,
                    "Origin revalidated"))
        .thenExecute(
            () -> {
              if (mode == 2)
                saved
                    .network(root.handle().id())
                    .setGrants(
                        actor,
                        guest.id(),
                        Set.of(
                            NetworkPermissions.Permission.WITHDRAW,
                            NetworkPermissions.Permission.DEPOSIT));
              var admission =
                  mode == 7
                      ? service
                          .routes()
                          .priority(
                              id,
                              actor,
                              position(helper, ROOT),
                              endpoint,
                              key,
                              64,
                              WorldStorageRoutes.Direction.TO_STORAGE,
                              route -> result[0] = route.move())
                      : mode == 5
                          ? service.progress(id, actor, false, value -> result[0] = value)
                          : service.submit(
                              id,
                              mode == 2
                                  ? guest
                                  : mode == 6
                                      ? new NetworkPermissions.Actor(actor.id(), true)
                                      : actor,
                              position(helper, ROOT),
                              mode == 1 || mode == 8
                                  ? new WorldStorageTransfers.Digital(cell)
                                  : endpoint,
                              mode == 1 || mode == 8
                                  ? endpoint
                                  : new WorldStorageTransfers.Digital(cell),
                              key,
                              mode == 4 || mode == 8 ? 128 : 64,
                              value -> result[0] = value);
              helper.assertTrue(
                  admission.rejection() == OperationResult.Reason.OK,
                  "Buffered move queued through shared service");
              if (mode == 2)
                saved.network(root.handle().id()).setGrants(actor, guest.id(), Set.of());
              if (mode == 4 || mode == 8) {
                helper.assertTrue(
                    service
                            .submit(
                                UUID.randomUUID(),
                                actor,
                                position(helper, ROOT),
                                mode == 8 ? new WorldStorageTransfers.Digital(cell) : endpoint,
                                mode == 8 ? endpoint : new WorldStorageTransfers.Digital(cell),
                                key,
                                64,
                                value -> result[1] = value)
                            .rejection()
                        == OperationResult.Reason.OK,
                    "Second same-buffer move queued");
                if (mode == 8)
                  helper.assertTrue(
                      service
                              .submit(
                                  UUID.randomUUID(),
                                  actor,
                                  position(helper, ROOT),
                                  endpoint,
                                  new WorldStorageTransfers.Digital(cell),
                                  key,
                                  64,
                                  value -> result[2] = value)
                              .rejection()
                          == OperationResult.Reason.OK,
                      "Independent output direction queued");
                // Fixed server tick isolates physical rate accounting from wall-clock scheduler
                // cutoffs.
                for (int step = 0;
                    step < 16
                        && (result[0] == null
                            || result[1] == null
                            || mode == 8 && result[2] == null);
                    step++) runtime.scheduler().tick();
                helper.assertTrue(
                    result[0] != null && result[1] != null && (mode != 8 || result[2] != null),
                    "Both bounded fixture dispatches ran in one server tick");
              }
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Buffered move callback"))
        .thenExecute(
            () -> {
              if (mode == 0)
                helper.assertTrue(
                    result[0].transfer().delivered() == 13
                        && buffer.inventory().total(key) == 51
                        && saved.registry().stock(cell, key).total() == 8192
                        && saved.transfers().context(id).source().type()
                            == TransferContext.Type.BUFFER,
                    "Actual partial insertion conserves buffer and cell with durable context");
              if (mode == 1)
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.FULL
                        && result[0].transfer() == null
                        && saved.registry().stock(cell, key).total() == 64,
                    "Full buffer rejects before extraction/record creation");
              if (mode == 2 || mode == 3 || mode == 6)
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && result[0].transfer() == null
                        && buffer.inventory().total(key) == 64
                        && saved.registry().stock(cell, key).total() == 0,
                    "Revoked or old-configuration move cannot consume stock");
              if (mode == 4)
                helper.assertTrue(
                    result[0].transfer().delivered() == 64
                        && result[1].reason() == OperationResult.Reason.RATE_LIMIT
                        && result[1].transfer() == null
                        && buffer.inventory().total(key) == 64
                        && saved.registry().stock(cell, key).total() == 64,
                    "Different move ports share one 64-item source allowance per buffer identity/tick");
              if (mode == 8)
                helper.assertTrue(
                    result[0].transfer().delivered() == 64
                        && result[1].reason() == OperationResult.Reason.RATE_LIMIT
                        && result[1].transfer() == null
                        && result[2].transfer().delivered() == 64
                        && buffer.inventory().total(key) == 0
                        && saved.registry().stock(cell, key).total() == 128,
                    "Input ports share one allowance; separate output can return actual stock in the same tick");
              if (mode == 7)
                helper.assertTrue(
                    result[0].transfer().delivered() == 64
                        && buffer.inventory().total(key) == 0
                        && saved.registry().stock(cell, key).total() == 64,
                    "Global priority routes accept a fixed buffer while excluding it from global stock");
              if (mode == 5)
                helper.assertTrue(
                    result[0].transfer().delivered() == 64
                        && result[0].transfer().staged() == 0
                        && buffer.inventory().total(key) == 24
                        && saved.registry().stock(cell, key).total() == 64,
                    "Recovery delivers only owned staging without extracting refill");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void bufferToCellPartialMoveKeepsExactOwnedContext(GameTestHelper helper) {
    scenario(helper, 0);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fullBufferRejectsCellWithdrawalWithoutExtraction(GameTestHelper helper) {
    scenario(helper, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void queuedBufferTransferRechecksRevokedPrincipal(GameTestHelper helper) {
    scenario(helper, 2);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void oldBufferConfigurationRejectsAfterValidScopeRebuild(GameTestHelper helper) {
    scenario(helper, 3);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void bufferMovesShareOneTickAllowanceAcrossPorts(GameTestHelper helper) {
    scenario(helper, 4);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void savedBufferSourceProgressNeverReextractsRefilledItems(GameTestHelper helper) {
    scenario(helper, 5);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void parentOperatorCannotExtractPrivateChildBuffer(GameTestHelper helper) {
    scenario(helper, 6);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void globalPriorityImportsFromExplicitBufferEndpoint(GameTestHelper helper) {
    scenario(helper, 7);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void bufferInputAndOutputAllowancesAreIndependentAndSharedAcrossPorts(
      GameTestHelper helper) {
    scenario(helper, 8);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void bufferDeploymentUsesAuthenticatedGrantsInsteadOfReferencePrincipal(
      GameTestHelper helper) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), true);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(actor.id());
    helper.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), guest.id());
    var saved = DeviceSavedData.get(helper.getLevel().getServer());
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    var mode = dev.izzy.factorycore.core.storage.BufferRegistry.Mode.IMPORT;
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(buffer.position()).status() == NetworkTopology.Status.VALID,
                    "Buffer scope ready"))
        .thenExecute(
            () -> {
              boolean denied = false;
              try {
                buffer.configure(guest, mode);
              } catch (IllegalStateException expected) {
                denied = true;
              }
              helper.assertTrue(
                  denied
                      && saved.buffers().deployment(buffer.handle().id()) == null
                      && buffer.reference().configuration() == 1,
                  "Reference principal and operator status cannot authorize deployment");
              var permissions = saved.network(root.handle().id());
              permissions.setGrants(
                  actor,
                  guest.id(),
                  Set.of(NetworkPermissions.Permission.EDIT, NetworkPermissions.Permission.DEPLOY));
              denied = false;
              try {
                buffer.configure(guest, mode);
              } catch (IllegalStateException expected) {
                denied = true;
              }
              helper.assertTrue(
                  denied && saved.buffers().deployment(buffer.handle().id()) == null,
                  "Import also requires current view/deposit/withdraw grants");
              var paused =
                  buffer.configure(
                      guest, dev.izzy.factorycore.core.storage.BufferRegistry.Mode.PAUSED);
              helper.assertTrue(
                  paused.revision() == 2 && paused.principal().equals(guest.id()),
                  "Paused deployment records authenticated owner with edit/deploy rights");
              permissions.setGrants(
                  actor,
                  guest.id(),
                  Set.of(
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.DEPOSIT,
                      NetworkPermissions.Permission.WITHDRAW));
              var enabled = buffer.configure(guest, mode);
              helper.assertTrue(
                  enabled.revision() == 3
                      && enabled.leaseGeneration() == buffer.handle().generation()
                      && enabled.network().equals(root.handle().id())
                      && enabled.segment().equals(root.handle().id())
                      && buffer.reference().configuration() == 3,
                  "Server derives canonical network scope and advances reference revision");
              permissions.setGrants(actor, guest.id(), Set.of());
              denied = false;
              try {
                buffer.configure(guest, mode);
              } catch (IllegalStateException expected) {
                denied = true;
              }
              helper.assertTrue(
                  denied
                      && enabled.equals(saved.buffers().deployment(buffer.handle().id()))
                      && buffer.reference().configuration() == 3,
                  "Revoked configuration cannot replace ownership or increment revision");
              var replacement =
                  buffer.configure(
                      actor, dev.izzy.factorycore.core.storage.BufferRegistry.Mode.PAUSED);
              helper.assertTrue(
                  replacement.revision() == 4
                      && replacement.principal().equals(actor.id())
                      && saved.buffers().deployments().stream()
                              .filter(entry -> entry.backing().equals(buffer.handle().id()))
                              .count()
                          == 1,
                  "Authorized replacement retains exactly one deployment per buffer");
            })
        .thenSucceed();
  }
}
