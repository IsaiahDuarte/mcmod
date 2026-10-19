package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
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

/**
 * ADR 0047: records saved without endpoint context settle only into canonical Factory Core holders
 * for their original owner, never by extracting again or guessing a foreign backing.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class LegacySettlementGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(3, 1, 1);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private LegacySettlementGameTests() {}

  private record Fixture(
      NetworkNodeEntity root,
      DeviceRegistry.Handle source,
      DeviceRegistry.Handle target,
      DeviceSavedData saved,
      WorldNetworks runtime,
      NodeRegistry.Position drive) {}

  private static Fixture setup(GameTestHelper h, UUID owner) {
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner);
    h.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    return new Fixture(
        root,
        CellHandleData.read(drive.cell(0)),
        CellHandleData.read(drive.cell(1)),
        DeviceSavedData.get(h.getLevel().getServer()),
        WorldNetworks.get(h.getLevel().getServer()),
        new NodeRegistry.Position(
            h.getLevel().dimension().location().toString(), h.absolutePos(DRIVE).asLong()));
  }

  private static boolean ready(Fixture f) {
    return f.runtime.membership(f.drive).status() == NetworkTopology.Status.VALID;
  }

  /** A real canonical cell port; used only to stage the legacy record's extraction. */
  private static ResourcePort cell(Fixture f, DeviceRegistry.Handle handle) {
    return new ResourcePort() {
      public UUID backingId() {
        return handle.backing();
      }

      public long simulateInsert(ResourceKey key, long amount) {
        return f.saved.registry().simulateInsert(handle, key, amount).amount();
      }

      public long extract(ResourceKey key, long amount) {
        return f.saved.registry().extract(handle, key, amount, null).amount();
      }

      public long insert(ResourceKey key, long amount) {
        return f.saved.registry().insert(handle, key, amount).amount();
      }
    };
  }

  /** A foreign endpoint with an unknown backing that accepts nothing (or fails mid-call). */
  private static ResourcePort foreign(int available, boolean failInsert) {
    return endpoint(UUID.randomUUID(), available, failInsert);
  }

  /** Test-owned endpoint for {@code backing}: provides {@code available}, accepts nothing. */
  private static ResourcePort endpoint(UUID backing, int available, boolean failInsert) {
    return new ResourcePort() {
      public UUID backingId() {
        return backing;
      }

      public long simulateInsert(ResourceKey key, long amount) {
        return amount;
      }

      public long extract(ResourceKey key, long amount) {
        return Math.min(amount, available);
      }

      public long insert(ResourceKey key, long amount) {
        if (failInsert) throw new IllegalStateException("Injected uncertain insert");
        return 0;
      }
    };
  }

  private static int command(net.minecraft.commands.CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException denied) {
      return -1;
    }
  }

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void originalOwnerSettlesLegacyStagingBackToItsCell(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    var f = setup(h, player.getUUID());
    var id = UUID.randomUUID();
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(ready(f), "Storage ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.source, KEY, 40);
              f.saved
                  .transfers()
                  .start(
                      id,
                      player.getUUID(),
                      KEY,
                      cell(f, f.source),
                      foreign(0, false),
                      12,
                      12,
                      () -> true);
              f.saved.transfers().unbind(id);
              h.assertTrue(
                  f.saved.transfers().get(id).staged() == 12
                      && f.saved.transfers().context(id) == null
                      && f.saved.registry().stock(f.source, KEY).total() == 28,
                  "A legacy record owns 12 staged items; the cell keeps 28");
              var ordinary = player.createCommandSourceStack().withPermission(0);
              var console = player.server.createCommandSourceStack();
              var settle = "factorycore recovery settle " + id;
              h.assertTrue(
                  command(console, settle) < 1 && command(console.withEntity(player), settle) < 1,
                  "Console and execute-as cannot settle for the owner");
              h.assertTrue(
                  command(ordinary, "factorycore recovery return " + id) == 0,
                  "Scoped return still refuses a legacy record");
              h.assertTrue(command(ordinary, settle) == 1, "The original owner queues settlement");
              var folder =
                  player
                      .server
                      .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                      .resolve("factorycore-recovery");
              try (var files = java.nio.file.Files.list(folder)) {
                h.assertTrue(
                    files.anyMatch(path -> path.getFileName().toString().startsWith(id.toString())),
                    "Before-state evidence is preserved before settlement");
              } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
              }
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.saved.transfers().get(id).state() == StagedTransfer.State.COMPLETE,
                    "Settlement completes"))
        .thenExecute(
            () -> {
              var record = f.saved.transfers().get(id);
              h.assertTrue(
                  record.returned() == 12
                      && record.delivered() == 0
                      && record.staged() == 0
                      && f.saved.registry().stock(f.source, KEY).total() == 40,
                  "Exactly the 12 staged items returned; nothing extracted again");
              h.assertTrue(
                  f.saved.transfers().unresolved(f.source.backing()) == null,
                  "The settled record no longer holds unresolved evidence");
              player.connection.disconnect(net.minecraft.network.chat.Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void legacyStagingFromAnUnknownSourceNeedsDepositAndNeverGuesses(GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var f = setup(h, owner.id());
    var delivered = UUID.randomUUID();
    var uncertain = UUID.randomUUID();
    var scoped = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(ready(f), "Storage ready"))
        .thenExecute(
            () -> {
              // Known: 64 items from a foreign provider with an unknown backing, never delivered.
              f.saved
                  .transfers()
                  .start(
                      delivered,
                      guest.id(),
                      KEY,
                      foreign(64, false),
                      endpoint(f.target.backing(), 0, false),
                      64,
                      64,
                      () -> true);
              f.saved.transfers().unbind(delivered);
              // Uncertain: an insert into the second cell failed mid-call (both ends canonical).
              f.saved.registry().insert(f.source, KEY, 5);
              f.saved
                  .transfers()
                  .start(
                      uncertain,
                      owner.id(),
                      KEY,
                      cell(f, f.source),
                      endpoint(f.target.backing(), 0, true),
                      5,
                      5,
                      () -> true);
              var transfers = f.runtime.transfers();
              h.assertTrue(
                  transfers.settleLegacy(delivered, owner, value -> {}).rejection()
                      == OperationResult.Reason.DENIED,
                  "Only the recorded principal may settle");
              h.assertTrue(
                  transfers.settleLegacy(delivered, guest, value -> {}).rejection()
                          == OperationResult.Reason.DENIED
                      && f.saved.transfers().get(delivered).staged() == 64,
                  "Delivery needs current DEPOSIT access to the destination cell");
              var before = f.saved.transfers().get(uncertain);
              h.assertTrue(
                  before.state() == StagedTransfer.State.QUARANTINED
                      && transfers.settleLegacy(uncertain, owner, value -> {}).rejection()
                          == OperationResult.Reason.UNCERTAIN
                      && before.equals(f.saved.transfers().get(uncertain)),
                  "An uncertain legacy outcome is never replayed or refunded");
              var scope = f.runtime.membership(f.drive).scope();
              var origin =
                  new NodeRegistry.Position(f.drive.dimension(), h.absolutePos(ROOT).asLong());
              f.saved.registry().insert(f.source, KEY, 2);
              f.saved
                  .transfers()
                  .start(
                      scoped,
                      owner.id(),
                      KEY,
                      cell(f, f.source),
                      endpoint(f.target.backing(), 0, false),
                      2,
                      2,
                      () -> true,
                      new TransferContext(
                          f.saved.registry().world(),
                          scope.network(),
                          scope.segment(),
                          origin,
                          new TransferContext.Endpoint(
                              TransferContext.Type.DIGITAL,
                              f.source.backing(),
                              f.source.backing(),
                              f.source.generation(),
                              0),
                          new TransferContext.Endpoint(
                              TransferContext.Type.DIGITAL,
                              f.target.backing(),
                              f.target.backing(),
                              f.target.generation(),
                              0)));
              h.assertTrue(
                  transfers.settleLegacy(scoped, owner, value -> {}).rejection()
                      == OperationResult.Reason.UNSUPPORTED,
                  "A record with context uses ordinary return, not settlement");
              h.assertTrue(
                  f.saved.transfers().hasUnscopedUnresolved()
                      && !f.saved.transfers().hasUnscopedUncertain(f.saved::canonicalIdentity),
                  "An uncertain legacy record between two cells cannot name a foreign provider");
              f.saved
                  .network(f.root.handle().id())
                  .setGrants(owner, guest.id(), Set.of(NetworkPermissions.Permission.DEPOSIT));
              h.assertTrue(
                  transfers.settleLegacy(delivered, guest, value -> result[0] = value).rejection()
                      == OperationResult.Reason.OK,
                  "With DEPOSIT the guest's staging is scheduled into the destination cell");
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "First settlement step"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  result[0].transfer().delivered() == 64
                      && result[0].transfer().staged() == 0
                      && result[0].transfer().state() == StagedTransfer.State.COMPLETE
                      && f.saved.registry().stock(f.target, KEY).total() == 64,
                  "Exactly 64 staged items reach the cell; the unknown source is never touched");
              result[0] = null;
              h.assertTrue(
                  f.runtime
                          .transfers()
                          .progress(scoped, owner, true, value -> result[0] = value)
                          .rejection()
                      == OperationResult.Reason.OK,
                  "The scoped record returns normally");
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Scoped return"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.saved.transfers().get(scoped).returned() == 2
                        && f.saved.registry().stock(f.source, KEY).total() == 2,
                    "The scoped cleanup restores its 2 items"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void movedLegacyHolderRequiresResubmissionOnItsCurrentNetwork(GameTestHelper h) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var f = setup(h, actor.id());
    var otherRoot = new BlockPos(1, 1, 4);
    var otherDrive = new BlockPos(3, 1, 4);
    h.setBlock(otherRoot, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(otherRoot);
    root.commission(actor.id());
    h.setBlock(new BlockPos(2, 1, 4), NetworkContent.CABLE.get());
    h.setBlock(otherDrive, StorageContent.DRIVE.get());
    StorageDriveEntity destination = h.getBlockEntity(otherDrive);
    var otherPosition =
        new NodeRegistry.Position(f.drive.dimension(), h.absolutePos(otherDrive).asLong());
    var id = UUID.randomUUID();
    WorldStorageTransfers.Result[] result = {null};
    DeviceRegistry.Handle[] moved = {null};
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    ready(f)
                        && f.runtime.membership(otherPosition).status()
                            == NetworkTopology.Status.VALID,
                    "Both independent networks ready"))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.source, KEY, 12);
              f.saved
                  .transfers()
                  .start(
                      id,
                      actor.id(),
                      KEY,
                      cell(f, f.source),
                      foreign(0, false),
                      12,
                      12,
                      () -> true);
              f.saved.transfers().unbind(id);
              var network = f.runtime.membership(f.drive).scope().network();
              // Bounded same-principal work keeps this ticket queued while topology revalidates.
              for (int n = 0; n < 100; n++)
                h.assertTrue(
                    f.runtime
                            .scheduler()
                            .submit(network, actor.id(), "legacy-move-delay-" + n, 512, () -> {})
                        == dev.izzy.factorycore.core.scheduling.FairScheduler.Admission.ACCEPTED,
                    "Delay admitted within the normal shared scheduler");
              h.assertTrue(
                  f.runtime
                          .transfers()
                          .settleLegacy(id, actor, value -> result[0] = value)
                          .rejection()
                      == OperationResult.Reason.OK,
                  "Settlement admitted on original network");
              StorageDriveEntity source = h.getBlockEntity(DRIVE);
              destination.installCell(source.removeCell(0));
              moved[0] = CellHandleData.read(destination.cell(0));
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Old ticket executes"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.runtime.membership(otherPosition).status() == NetworkTopology.Status.VALID,
                  "Moved holder revalidated before settlement");
              h.assertTrue(
                  result[0].reason() == OperationResult.Reason.DENIED
                      && f.saved.transfers().get(id).staged() == 12
                      && f.saved.registry().stock(moved[0], KEY).total() == 0,
                  "Old network ticket cannot spend quota on a different network; staging retained");
              result[0] = null;
              h.assertTrue(
                  f.runtime
                          .transfers()
                          .settleLegacy(id, actor, value -> result[0] = value)
                          .rejection()
                      == OperationResult.Reason.OK,
                  "Explicit retry admitted on current network");
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "New ticket executes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0].transfer().returned() == 12
                        && result[0].transfer().staged() == 0
                        && f.saved.registry().stock(moved[0], KEY).total() == 12,
                    "Current-network retry returns exactly the original staging"))
        .thenSucceed();
  }
}
