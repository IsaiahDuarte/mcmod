package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferImportGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos BUFFER = new BlockPos(3, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(2, 1, 2);

  private record Fixture(
      NetworkPermissions.Actor actor,
      NetworkNodeEntity root,
      BufferedInterfaceEntity buffer,
      DeviceRegistry.Handle cell,
      DeviceSavedData saved,
      WorldNetworks runtime,
      ResourceKey key) {}

  private BufferImportGameTests() {}

  private static ItemStack named(int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Automatic conserved hopper input"));
    return stack;
  }

  private static Fixture setup(GameTestHelper helper) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(actor.id());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), actor.id());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    return new Fixture(
        actor,
        root,
        buffer,
        CellHandleData.read(drive.cell(0)),
        DeviceSavedData.get(helper.getLevel().getServer()),
        WorldNetworks.get(helper.getLevel().getServer()),
        new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(named(1)));
  }

  private static void ready(GameTestHelper helper, Fixture fixture) {
    helper.assertTrue(
        fixture.runtime().membership(fixture.buffer().position()).status()
            == NetworkTopology.Status.VALID,
        "Import scope ready");
    var drive =
        new NodeRegistry.Position(
            helper.getLevel().dimension().location().toString(),
            helper.absolutePos(DRIVE).asLong());
    helper.assertTrue(
        fixture.runtime().membership(drive).status() == NetworkTopology.Status.VALID,
        "Storage scope ready");
  }

  private static void enable(Fixture fixture) {
    fixture.buffer().configure(fixture.actor(), BufferRegistry.Mode.IMPORT);
  }

  private static void exact(GameTestHelper helper, Fixture fixture, long buffer, long cell) {
    helper.assertTrue(
        fixture.buffer().inventory().total(fixture.key()) == buffer
            && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total() == cell,
        "Exact buffer/storage conservation: " + buffer + "/" + cell);
  }

  private static void queuePreparation(GameTestHelper helper, Fixture fixture) {
    for (int step = 0;
        step < BufferRegistry.MAX_BUFFERS / WorldBufferImports.VISITS_PER_TICK
            && !fixture.runtime().imports().status(fixture.buffer()).waiting();
        step++) fixture.runtime().imports().tick();
    helper.assertTrue(
        fixture.runtime().imports().status(fixture.buffer()).waiting(),
        "Bounded fixture visits admitted import preparation before the next principal task");
  }

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 500)
  public static void auditedBufferRepairRetainsInventoryAndRequiresAuthorizedPresetReapply(
      GameTestHelper h) {
    var f = setup(h);
    var old = f.buffer.handle();
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(BUFFER)));
    h.assertTrue(!player.hasPermissions(2), "Recovery deployer has ordinary player grants");
    var deployer = new NetworkPermissions.Actor(player.getUUID(), false);
    var policy =
        new dev.izzy.factorycore.core.storage.BufferPolicy(
            ResourceFilter.all(ResourceKind.ITEM), 3, 17);
    BufferRegistry.Deployment[] accepted = {null};
    BufferData.State[] originalReference = {null};
    dev.izzy.factorycore.core.storage.ItemBuffer.State[] inventory = {null};
    net.minecraft.nbt.CompoundTag[] originalChunk = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(50)
        .thenExecute(
            () -> {
              f.saved
                  .network(f.root.handle().id())
                  .setGrants(
                      f.actor,
                      deployer.id(),
                      java.util.EnumSet.allOf(NetworkPermissions.Permission.class));
              accepted[0] = f.buffer.configure(deployer, BufferRegistry.Mode.IMPORT, policy);
              originalReference[0] = f.buffer.reference();
              var stale = f.buffer.capability();
              h.assertTrue(
                  stale.insertItem(8, named(19), false).isEmpty(),
                  "Seed exact named physical stock");
              inventory[0] = f.saved.buffers().evidence(old.id());
              queuePreparation(h, f);
              originalChunk[0] = f.buffer.saveWithFullMetadata(h.getLevel().registryAccess());
              var clone = recoveryCopy(h, originalChunk[0]);
              h.assertTrue(
                  f.saved.nodes().validate(old) == NodeRegistry.Failure.CONFLICT
                      && f.buffer.capability() == null
                      && clone.capability() == null,
                  "Real copied holder quarantines canonical buffer");
              var console = h.getLevel().getServer().createCommandSourceStack();
              var repair =
                  "factorycore recovery buffer repair " + old.id() + " " + old.generation();
              h.assertTrue(
                  recoveryCommand(console.withPermission(0), repair) < 1,
                  "Only operator can repair");
              h.assertTrue(
                  recoveryCommand(console, "factorycore recovery buffer show " + old.id()) == 1,
                  "Inspect original authority and count");
              h.assertTrue(
                  recoveryCommand(console, "factorycore recovery buffer list") >= 0,
                  "Metadata page command executes");
              h.assertTrue(
                  recoveryCommand(
                          console, "factorycore recovery buffer show " + f.root.handle().id())
                      == 0,
                  "Structural root cannot be reinterpreted as buffer");
              h.assertTrue(
                  recoveryCommand(console, repair + "0") == 0, "Wrong expected generation rejects");
              rejectRepair(h, clone, old);
              h.assertTrue(
                  recoveryCommand(console, repair) == 1, "Repair exact original loaded holder");
              var current = f.buffer.handle();
              h.assertTrue(
                  current.generation() == old.generation() + 1
                      && f.saved.nodes().validate(old) == NodeRegistry.Failure.STALE,
                  "Consumes every old copied lease");
              h.assertTrue(
                  inventory[0].equals(f.saved.buffers().evidence(old.id()))
                      && accepted[0].equals(f.saved.buffers().deployment(old.id())),
                  "Exact slots and accepted original deployment retained");
              h.assertTrue(
                  f.buffer.reference().principal().equals(originalReference[0].principal())
                      && f.buffer.reference().configuration()
                          == originalReference[0].configuration()
                      && !current.equals(old)
                      && accepted[0].leaseGeneration() == old.generation(),
                  "Repair does not publish a deployment under the new lease or operator identity");
              h.assertTrue(
                  stale.getStackInSlot(8).isEmpty()
                      && stale.extractItem(8, 1, false).isEmpty()
                      && stale.insertItem(0, named(1), false).getCount() == 1
                      && f.buffer.capability().getStackInSlot(8).getCount() == 19,
                  "Old cached handler inert while current holder owns19 items");
              var repairedChunk = f.buffer.saveWithFullMetadata(h.getLevel().registryAccess());
              f.buffer.loadWithComponents(repairedChunk, h.getLevel().registryAccess());
              f.buffer.onLoad();
              h.assertTrue(
                  f.buffer.handle().equals(current)
                      && f.buffer.capability().getStackInSlot(8).getCount() == 19,
                  "Repaired physical reference reload preserves ownership");
              clone.loadWithComponents(originalChunk[0], h.getLevel().registryAccess());
              clone.onLoad();
              h.assertTrue(
                  f.saved.nodes().validate(current) == NodeRegistry.Failure.OK
                      && clone.capability() == null,
                  "Stale clone cannot requarantine new generation");
              var canonical = f.saved.nodes().snapshot();
              var restoredNodes = new NodeRegistry(current.world(), canonical, () -> {});
              var restoredBuffers =
                  new BufferRegistry(
                      restoredNodes,
                      f.saved.buffers().snapshot(),
                      f.saved.buffers().deployments(),
                      () -> {});
              h.assertTrue(
                  restoredBuffers.evidence(old.id()).equals(inventory[0])
                      && restoredBuffers.deployment(old.id()).equals(accepted[0])
                      && restoredNodes.validate(old) == NodeRegistry.Failure.STALE,
                  "Canonical restore retains stock and inactive accepted lease");
            })
        .thenIdle(30)
        .thenExecute(
            () -> {
              ready(h, f);
              exact(h, f, 19, 0);
              h.assertTrue(
                  accepted[0].equals(f.saved.buffers().deployment(old.id())),
                  "Queued old work and retained old deployment never extract");
              var grants = java.util.EnumSet.allOf(NetworkPermissions.Permission.class);
              grants.remove(NetworkPermissions.Permission.DEPLOY);
              f.saved.network(f.root.handle().id()).setGrants(f.actor, deployer.id(), grants);
              h.useBlock(BUFFER, player);
              h.assertTrue(
                  player.containerMenu instanceof BufferSettingsMenu,
                  "Physical use opens recovered buffer settings");
              var menu = (BufferSettingsMenu) player.containerMenu;
              menu.broadcastChanges();
              menu.command(
                  player,
                  new BufferSettingsPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.view().revision(),
                      BufferSettingsPackets.APPLY,
                      0,
                      0,
                      "",
                      new byte[32]));
              h.assertTrue(
                  menu.view().status().equals("denied")
                      && accepted[0].equals(f.saved.buffers().deployment(old.id())),
                  "Native Apply cannot bypass current revoked DEPLOY grant");
              f.saved
                  .network(f.root.handle().id())
                  .setGrants(
                      f.actor,
                      deployer.id(),
                      java.util.EnumSet.allOf(NetworkPermissions.Permission.class));
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = (BufferSettingsMenu) player.containerMenu;
              menu.broadcastChanges();
              menu.command(
                  player,
                  new BufferSettingsPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.view().revision(),
                      BufferSettingsPackets.APPLY,
                      0,
                      0,
                      "",
                      new byte[32]));
              h.assertTrue(
                  menu.view().status().equals("applied"),
                  "Native Apply republishes recovered policy");
              var reapplied = f.saved.buffers().deployment(old.id());
              h.assertTrue(
                  reapplied.principal().equals(deployer.id())
                      && reapplied.policy().equals(policy)
                      && reapplied.leaseGeneration() == f.buffer.handle().generation()
                      && reapplied.revision() == accepted[0].revision() + 1,
                  "Explicit authenticated reapply preserves intended policy/principal");
            })
        .thenWaitUntil(() -> exact(h, f, 0, 19))
        .thenExecute(
            () -> {
              player.closeContainer();
              var current = f.buffer.handle();
              var before = f.saved.buffers().evidence(old.id());
              f.buffer.loadWithComponents(originalChunk[0], h.getLevel().registryAccess());
              f.buffer.onLoad();
              rejectRepair(h, f.buffer, current);
              h.assertTrue(
                  f.buffer.capability() == null
                      && before.equals(f.saved.buffers().evidence(old.id()))
                      && f.saved.nodes().validate(current) == NodeRegistry.Failure.OK,
                  "Old original chunk snapshot stays inert without stock/lease changes");
              player.connection.disconnect(Component.literal("Buffer recovery fixture complete"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void failedBufferAuditPreservesSlotsDeploymentAndReference(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.buffer.configure(f.actor, BufferRegistry.Mode.PAUSED);
              f.buffer.capability().insertItem(8, named(19), false);
              var raw = f.buffer.saveWithFullMetadata(h.getLevel().registryAccess());
              recoveryCopy(h, raw);
              var old = f.buffer.handle();
              var node = f.saved.nodes().evidence(old.id());
              var inventory = f.saved.buffers().evidence(old.id());
              var deployment = f.saved.buffers().deployment(old.id());
              var folder =
                  h.getLevel()
                      .getServer()
                      .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                      .resolve("factorycore-device-recovery");
              try {
                java.nio.file.Files.createDirectories(folder);
                var blocker =
                    java.nio.file.Files.createDirectory(
                        folder.resolve("unexpected-buffer-" + UUID.randomUUID()));
                boolean refused = false;
                try {
                  f.buffer.repairConflict(old);
                } catch (java.io.IOException expected) {
                  refused = true;
                } finally {
                  java.nio.file.Files.delete(blocker);
                }
                h.assertTrue(refused, "Evidence failure blocks buffer repair");
              } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
              }
              h.assertTrue(
                  node.equals(f.saved.nodes().evidence(old.id()))
                      && inventory.equals(f.saved.buffers().evidence(old.id()))
                      && deployment.equals(f.saved.buffers().deployment(old.id()))
                      && old.equals(f.buffer.handle())
                      && raw.equals(f.buffer.saveWithFullMetadata(h.getLevel().registryAccess())),
                  "Audit failure changes no canonical or physical evidence");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void unresolvedBufferStagingPreventsLeaseRepair(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.buffer.capability().insertItem(8, named(19), false);
              var destination =
                  new ResourcePort() {
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
                      return 0;
                    }
                  };
              var id = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      id, f.actor.id(), f.key, f.buffer.inventory(), destination, 3, 3, () -> true);
              exact(h, f, 16, 0);
              h.assertTrue(
                  f.saved.transfers().get(id).staged() == 3,
                  "Real extracted remainder owns3 items");
              recoveryCopy(h, f.buffer.saveWithFullMetadata(h.getLevel().registryAccess()));
              var old = f.buffer.handle();
              var node = f.saved.nodes().evidence(old.id());
              var before = f.saved.buffers().evidence(old.id());
              var transfer = f.saved.transfers().get(id);
              rejectRepair(h, f.buffer, old);
              h.assertTrue(
                  node.equals(f.saved.nodes().evidence(old.id()))
                      && before.equals(f.saved.buffers().evidence(old.id()))
                      && transfer.equals(f.saved.transfers().get(id))
                      && old.equals(f.buffer.handle()),
                  "Unresolved16+3 ownership blocks repair unchanged");
            })
        .thenSucceed();
  }

  private static BufferedInterfaceEntity recoveryCopy(
      GameTestHelper h, net.minecraft.nbt.CompoundTag raw) {
    var position = new BlockPos(3, 1, 3);
    h.setBlock(position, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity clone = h.getBlockEntity(position);
    clone.loadWithComponents(raw, h.getLevel().registryAccess());
    clone.onLoad();
    return clone;
  }

  private static void rejectRepair(
      GameTestHelper h, BufferedInterfaceEntity buffer, NodeRegistry.Handle expected) {
    boolean refused = false;
    try {
      buffer.repairConflict(expected);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      refused = true;
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
    h.assertTrue(refused, "Unsupported buffer repair must refuse");
  }

  private static int recoveryCommand(
      net.minecraft.commands.CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) {
      return -1;
    }
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void actualHopperAutomaticallyImportsExactComponentsWithoutCode(
      GameTestHelper helper) {
    var fixture = setup(helper);
    helper.setBlock(
        BUFFER.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = helper.getBlockEntity(BUFFER.above());
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              enable(fixture);
              hopper.setItem(0, named(32));
              hopper.setItem(1, new ItemStack(Items.DIAMOND, 7));
            })
        .thenWaitUntil(
            () -> {
              var diamond =
                  new PlatformResourceCodec(helper.getLevel().registryAccess())
                      .itemKey(new ItemStack(Items.DIAMOND));
              helper.assertTrue(
                  fixture.saved().registry().stock(fixture.cell(), fixture.key()).total() == 32
                      && fixture.saved().registry().stock(fixture.cell(), diamond).total() == 7,
                  "All real hopper identities reached permitted storage");
            })
        .thenExecute(
            () -> {
              exact(helper, fixture, 0, 32);
              helper.assertTrue(
                  hopper.getItem(0).isEmpty() && hopper.getItem(1).isEmpty(),
                  "Physical hopper drained");
              helper.assertTrue(
                  fixture.saved().transfers().unresolved(fixture.buffer().handle().id()) == null,
                  "Completed imports retain no staging/recovery blocker");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void fullStorageBacksUpBufferAndHopperThenResumesWithoutLoss(
      GameTestHelper helper) {
    var fixture = setup(helper);
    helper.setBlock(
        BUFFER.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = helper.getBlockEntity(BUFFER.above());
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.saved().registry().insert(fixture.cell(), fixture.key(), 8192);
              for (int slot = 0; slot < 9; slot++)
                fixture.buffer().capability().insertItem(slot, named(64), false);
              hopper.setItem(0, named(17));
              enable(fixture);
            })
        .thenIdle(60)
        .thenExecute(
            () -> {
              exact(helper, fixture, 576, 8192);
              helper.assertTrue(
                  hopper.getItem(0).getCount() == 17, "Full destination preserves hopper input");
              helper.assertTrue(
                  fixture
                          .saved()
                          .registry()
                          .extract(fixture.cell(), fixture.key(), 32, null)
                          .amount()
                      == 32,
                  "Fixture retains thirty-two withdrawn items");
            })
        .thenWaitUntil(
            () -> {
              exact(helper, fixture, 561, 8192);
              helper.assertTrue(
                  hopper.getItem(0).isEmpty(), "Freed network capacity resumes physical hopper");
            })
        .thenExecute(
            () ->
                helper.assertTrue(
                    8192 + 561 + 32 == 8192 + 576 + 17,
                    "Storage, buffer, hopper and fixture withdrawal conserve all items"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void queuedImportRechecksDeploymentGrantEvenWhenResourceGrantsRemain(
      GameTestHelper helper) {
    var fixture = setup(helper);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              var permissions = fixture.saved().network(fixture.root().handle().id());
              permissions.setGrants(
                  fixture.actor(),
                  guest.id(),
                  Set.of(
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.WITHDRAW,
                      NetworkPermissions.Permission.DEPOSIT));
              fixture.buffer().capability().insertItem(0, named(64), false);
              fixture.buffer().configure(guest, BufferRegistry.Mode.IMPORT);
              queuePreparation(helper, fixture);
              fixture
                  .runtime()
                  .scheduler()
                  .submit(
                      fixture.root().handle().id(),
                      guest.id(),
                      "revoke-deployment",
                      16,
                      () ->
                          permissions.setGrants(
                              fixture.actor(),
                              guest.id(),
                              Set.of(
                                  NetworkPermissions.Permission.EDIT,
                                  NetworkPermissions.Permission.VIEW,
                                  NetworkPermissions.Permission.WITHDRAW,
                                  NetworkPermissions.Permission.DEPOSIT)));
            })
        .thenIdle(50)
        .thenExecute(
            () -> {
              exact(helper, fixture, 64, 0);
              helper.assertTrue(
                  fixture.runtime().imports().status(fixture.buffer()).reason()
                      == OperationResult.Reason.DENIED,
                  "Selective DEPLOY revocation stops accepted automatic routes");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void pausingQueuedImportPreservesStockAndNewRevisionResumes(GameTestHelper helper) {
    var fixture = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.buffer().capability().insertItem(0, named(64), false);
              enable(fixture);
              queuePreparation(helper, fixture);
              fixture
                  .runtime()
                  .scheduler()
                  .submit(
                      fixture.root().handle().id(),
                      fixture.actor().id(),
                      "pause-buffer",
                      16,
                      () ->
                          fixture.buffer().configure(fixture.actor(), BufferRegistry.Mode.PAUSED));
            })
        .thenIdle(50)
        .thenExecute(
            () -> {
              exact(helper, fixture, 64, 0);
              enable(fixture);
            })
        .thenWaitUntil(() -> exact(helper, fixture, 0, 64))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void disconnectedImportKeepsStockAndReconnectUsesAcceptedScope(
      GameTestHelper helper) {
    var fixture = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.buffer().capability().insertItem(0, named(64), false);
              enable(fixture);
              queuePreparation(helper, fixture);
              fixture
                  .runtime()
                  .scheduler()
                  .submit(
                      fixture.root().handle().id(),
                      fixture.actor().id(),
                      "disconnect-buffer",
                      16,
                      () -> helper.setBlock(CABLE, Blocks.AIR));
            })
        .thenIdle(50)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  fixture.buffer().inventory().total(fixture.key()) == 64,
                  "Disconnect rejects queued extraction and retains local stock");
              helper.setBlock(CABLE, NetworkContent.CABLE.get());
            })
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenWaitUntil(() -> exact(helper, fixture, 0, 64))
        .thenSucceed();
  }

  private static UUID staged(Fixture fixture, boolean uncertain, int[] writes) {
    var scope = fixture.runtime().membership(fixture.buffer().position()).scope();
    var source = fixture.buffer();
    var id = UUID.randomUUID();
    var output =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return fixture.cell().backing();
          }

          @Override
          public long simulateInsert(ResourceKey key, long offered) {
            return offered;
          }

          @Override
          public long extract(ResourceKey key, long offered) {
            throw new AssertionError("Never re-extract destination");
          }

          @Override
          public long insert(ResourceKey key, long offered) {
            writes[0]++;
            if (uncertain)
              throw new IllegalStateException("Fixture destination fails before mutation");
            return 0;
          }
        };
    var context =
        new TransferContext(
            fixture.saved().registry().world(),
            scope.network(),
            scope.segment(),
            source.position(),
            new TransferContext.Endpoint(
                TransferContext.Type.BUFFER,
                source.handle().id(),
                source.handle().id(),
                source.handle().generation(),
                source.reference().configuration()),
            new TransferContext.Endpoint(
                TransferContext.Type.DIGITAL,
                fixture.cell().backing(),
                fixture.cell().backing(),
                fixture.cell().generation(),
                0));
    fixture
        .saved()
        .transfers()
        .start(
            id,
            fixture.actor().id(),
            fixture.key(),
            source.inventory(),
            output,
            64,
            64,
            () -> true,
            context);
    fixture.saved().transfers().unbind(id);
    return id;
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void automaticRecoveryDeliversOnlyOwnedStagingWithoutReextractingRefill(
      GameTestHelper helper) {
    var fixture = setup(helper);
    UUID[] record = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.saved().registry().insert(fixture.cell(), fixture.key(), 8192 - 64);
              fixture.buffer().capability().insertItem(0, named(64), false);
              enable(fixture);
              record[0] = staged(fixture, false, new int[1]);
              fixture.buffer().capability().insertItem(0, named(23), false);
            })
        .thenWaitUntil(
            () -> {
              helper.assertTrue(
                  !fixture.saved().transfers().contains(record[0]),
                  "Known staging delivered and retired");
              exact(helper, fixture, 23, 8192);
            })
        .thenIdle(30)
        .thenExecute(() -> exact(helper, fixture, 23, 8192))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void uncertainImportBlocksNewExtractionAndNeverReplaysDestination(
      GameTestHelper helper) {
    var fixture = setup(helper);
    UUID[] record = {null};
    int[] writes = {0};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.buffer().capability().insertItem(0, named(64), false);
              enable(fixture);
              record[0] = staged(fixture, true, writes);
              fixture.buffer().capability().insertItem(0, named(17), false);
            })
        .thenIdle(60)
        .thenExecute(
            () -> {
              exact(helper, fixture, 17, 0);
              var snapshot = fixture.saved().transfers().get(record[0]);
              helper.assertTrue(
                  snapshot.state() == StagedTransfer.State.QUARANTINED
                      && snapshot.staged() == 64
                      && writes[0] == 1
                      && fixture.runtime().imports().status(fixture.buffer()).reason()
                          == OperationResult.Reason.UNCERTAIN,
                  "Uncertain evidence remains owned; no replay or refill extraction");
              var cleanup =
                  fixture
                      .buffer()
                      .returnStaging(
                          fixture.actor(),
                          result -> {
                            throw new AssertionError("Uncertain cleanup must not dispatch");
                          });
              helper.assertTrue(
                  cleanup.rejection() == OperationResult.Reason.UNCERTAIN
                      && fixture.saved().transfers().get(record[0]).equals(snapshot),
                  "Explicit return cannot replay or refund uncertain intent");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void referenceSaveDivergenceCannotAdoptCanonicalDeploymentUntilReconfigured(
      GameTestHelper helper) {
    var fixture = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.buffer().capability().insertItem(0, named(64), false);
              var old = fixture.buffer().saveWithFullMetadata(helper.getLevel().registryAccess());
              enable(fixture);
              fixture.buffer().loadWithComponents(old, helper.getLevel().registryAccess());
              fixture.buffer().onLoad();
            })
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenIdle(40)
        .thenExecute(
            () -> {
              exact(helper, fixture, 64, 0);
              helper.assertTrue(
                  fixture.runtime().imports().status(fixture.buffer()).reason()
                      == OperationResult.Reason.DENIED,
                  "Old reference revision cannot impersonate canonical intent");
              enable(fixture);
            })
        .thenWaitUntil(() -> exact(helper, fixture, 0, 64))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void emptyImportSleepsAndActualInsertionWakesWhileSimulationDoesNot(
      GameTestHelper helper) {
    var fixture = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(() -> enable(fixture))
        .thenIdle(30)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  !fixture.runtime().imports().status(fixture.buffer()).waiting(),
                  "Empty rule leaves the active work queue");
              fixture.buffer().capability().insertItem(0, named(9), true);
              fixture.runtime().imports().tick();
              helper.assertTrue(
                  !fixture.runtime().imports().status(fixture.buffer()).waiting()
                      && fixture.buffer().inventory().total(fixture.key()) == 0,
                  "Simulation does not wake or mutate an empty rule");
              fixture.buffer().capability().insertItem(0, named(9), false);
              queuePreparation(helper, fixture);
            })
        .thenWaitUntil(() -> exact(helper, fixture, 0, 9))
        .thenIdle(20)
        .thenExecute(
            () ->
                helper.assertTrue(
                    !fixture.runtime().imports().status(fixture.buffer()).waiting(),
                    "Completed empty rule sleeps again"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void playerControlsToggleOnlyWithCurrentEditDeploymentAndResourceGrants(
      GameTestHelper helper) {
    var fixture = setup(helper);
    var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
    player.setShiftKeyDown(true);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  fixture.saved().buffers().deployment(fixture.buffer().handle().id()) == null,
                  "Ungrantable player input cannot enable automation");
              var permissions = fixture.saved().network(fixture.root().handle().id());
              permissions.setGrants(
                  fixture.actor(),
                  player.getUUID(),
                  Set.of(
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.DEPOSIT,
                      NetworkPermissions.Permission.WITHDRAW));
              helper.useBlock(BUFFER, player);
              var enabled = fixture.saved().buffers().deployment(fixture.buffer().handle().id());
              helper.assertTrue(
                  enabled.mode() == BufferRegistry.Mode.IMPORT
                      && enabled.principal().equals(player.getUUID()),
                  "Native crouch-use authenticates the player and enables Import");
              player.setShiftKeyDown(false);
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  enabled.equals(
                      fixture.saved().buffers().deployment(fixture.buffer().handle().id())),
                  "Normal empty-hand use inspects without changing mode or revision");
              player.setShiftKeyDown(true);
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  fixture.saved().buffers().deployment(fixture.buffer().handle().id()).mode()
                      == BufferRegistry.Mode.PAUSED,
                  "Second native crouch-use pauses Import");
              permissions.setGrants(fixture.actor(), player.getUUID(), Set.of());
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  fixture.saved().buffers().deployment(fixture.buffer().handle().id()).mode()
                      == BufferRegistry.Mode.PAUSED,
                  "Revoked physical controls cannot enable a new rule");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void breakingQueuedBufferPreservesOneOwnerAndMovementRequiresReconfiguration(
      GameTestHelper helper) {
    var fixture = setup(helper);
    BufferedInterfaceEntity[] moved = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.buffer().capability().insertItem(0, named(64), false);
              enable(fixture);
              queuePreparation(helper, fixture);
              fixture
                  .runtime()
                  .scheduler()
                  .submit(
                      fixture.root().handle().id(),
                      fixture.actor().id(),
                      "remove-buffer",
                      16,
                      () -> {
                        helper.getLevel().destroyBlock(helper.absolutePos(BUFFER), true);
                        var drops =
                            helper
                                .getLevel()
                                .getEntitiesOfClass(
                                    net.minecraft.world.entity.item.ItemEntity.class,
                                    new net.minecraft.world.phys.AABB(helper.absolutePos(BUFFER))
                                        .inflate(.8),
                                    entity ->
                                        entity.getItem().is(BufferedInterfaceContent.ITEM.get()));
                        helper.assertTrue(
                            drops.size() == 1, "One portable canonical reference after removal");
                        var stack = drops.getFirst().getItem().copy();
                        drops.getFirst().discard();
                        helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
                        moved[0] = helper.getBlockEntity(BUFFER);
                        moved[0].place(stack, fixture.actor().id());
                      });
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    moved[0] != null
                        && fixture.runtime().membership(moved[0].position()).status()
                            == NetworkTopology.Status.VALID,
                    "Moved holder and valid network ready"))
        .thenIdle(40)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  moved[0].inventory().total(fixture.key()) == 64
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 0
                      && fixture.runtime().imports().status(moved[0]).reason()
                          == OperationResult.Reason.DENIED,
                  "Unregister blocks old queued work; moved lease does not renew preset authority");
              moved[0].configure(fixture.actor(), BufferRegistry.Mode.IMPORT);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    moved[0].inventory().total(fixture.key()) == 0
                        && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                            == 64,
                    "Explicit reconfiguration safely resumes moved stock"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void originalOwnerReturnsKnownStagingAfterPauseWithoutCurrentResourceGrants(
      GameTestHelper helper) {
    var original = setup(helper);
    var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    var fixture =
        new Fixture(
            actor,
            original.root(),
            original.buffer(),
            original.cell(),
            original.saved(),
            original.runtime(),
            original.key());
    UUID[] record = {null};
    var grants =
        Set.of(
            NetworkPermissions.Permission.EDIT,
            NetworkPermissions.Permission.DEPLOY,
            NetworkPermissions.Permission.VIEW,
            NetworkPermissions.Permission.DEPOSIT,
            NetworkPermissions.Permission.WITHDRAW);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture
                  .saved()
                  .network(fixture.root().handle().id())
                  .setGrants(original.actor(), actor.id(), grants);
              fixture.buffer().capability().insertItem(0, named(64), false);
              enable(fixture);
              record[0] = staged(fixture, false, new int[1]);
              fixture.buffer().capability().insertItem(0, named(17), false);
              fixture.buffer().configure(actor, BufferRegistry.Mode.PAUSED);
              fixture
                  .saved()
                  .network(fixture.root().handle().id())
                  .setGrants(original.actor(), actor.id(), Set.of());
              var denied =
                  fixture
                      .buffer()
                      .returnStaging(
                          new NetworkPermissions.Actor(UUID.randomUUID(), true),
                          result -> {
                            throw new AssertionError("Other owner cannot schedule cleanup");
                          });
              helper.assertTrue(
                  denied.rejection() == OperationResult.Reason.DENIED
                      && fixture.saved().transfers().get(record[0]).staged() == 64,
                  "Operator flag does not spend another principal's staging");
              player.setShiftKeyDown(true);
              player.setItemInHand(
                  net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(Items.REDSTONE));
              helper.useBlock(BUFFER, player);
            })
        .thenWaitUntil(
            () -> {
              helper.assertTrue(
                  !fixture.saved().transfers().contains(record[0]),
                  "Known owned cleanup completed and retired");
              exact(helper, fixture, 81, 0);
            })
        .thenExecute(
            () -> {
              helper.assertTrue(
                  player.getOffhandItem().getCount() == 1, "Cleanup redstone is not consumed");
              fixture
                  .saved()
                  .network(fixture.root().handle().id())
                  .setGrants(original.actor(), actor.id(), grants);
              enable(fixture);
            })
        .thenWaitUntil(() -> exact(helper, fixture, 0, 81))
        .thenSucceed();
  }
}
