package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferExportGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      BUFFER = new BlockPos(3, 1, 1);

  private record Fixture(
      NetworkPermissions.Actor actor,
      BufferedInterfaceEntity buffer,
      DeviceRegistry.Handle cell,
      DeviceSavedData saved,
      WorldNetworks runtime,
      ResourceKey iron,
      ResourceKey gold) {}

  private BufferExportGameTests() {}

  private static Fixture setup(GameTestHelper helper) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    NetworkNodeEntity root = Net.in(helper, actor.id()).controller(ROOT);
    StorageDriveEntity drive = Net.in(helper).drive(DRIVE, CellTier.STARTER_ITEM);
    BufferedInterfaceEntity buffer = Net.in(helper, actor.id()).buffer(BUFFER);
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    return new Fixture(
        actor,
        buffer,
        CellHandleData.read(drive.cell(0)),
        DeviceSavedData.get(helper.getLevel().getServer()),
        WorldNetworks.get(helper.getLevel().getServer()),
        codec.itemKey(new ItemStack(Items.IRON_INGOT)),
        codec.itemKey(new ItemStack(Items.GOLD_INGOT)));
  }

  private static void ready(GameTestHelper helper, Fixture f) {
    helper.assertTrue(
        f.runtime.membership(f.buffer.position()).status() == NetworkTopology.Status.VALID,
        "Export scope ready");
  }

  private static BufferPolicy policy(Fixture f, int target) {
    return new BufferPolicy(
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Registry(f.iron.registryId()))),
        16,
        target);
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void automaticExportFeedsRealHopperAndNeverWithdrawsExcludedGold(
      GameTestHelper helper) {
    var f = setup(helper);
    helper.setBlock(
        BUFFER.below(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = helper.getBlockEntity(BUFFER.below());
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 23);
              f.saved.registry().insert(f.cell, f.gold, 9);
              f.buffer.configure(f.actor, BufferRegistry.Mode.EXPORT, policy(f, 64));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.iron).total() == 0
                        && f.buffer.inventory().total(f.iron) == 0
                        && hopper.getItem(0).is(Items.IRON_INGOT)
                        && hopper.getItem(0).getCount() == 23,
                    "Actual source/cell/buffer/hopper export conserves all selected items"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.gold).total() == 9
                        && f.buffer.inventory().total(f.gold) == 0,
                    "Excluded source remains untouched"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void stockStopsAndReplenishesWithoutOverWithdrawing(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 100);
              f.buffer.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 20));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) == 20
                        && f.saved.registry().stock(f.cell, f.iron).total() == 80,
                    "Stock reaches exact physical target"))
        .thenIdle(30)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.buffer.inventory().total(f.iron) == 20
                      && f.saved.registry().stock(f.cell, f.iron).total() == 80,
                  "At-goal polling does not over-withdraw");
              var extracted = f.buffer.capability().extractItem(0, 7, false);
              helper.assertTrue(
                  extracted.getCount() == 7, "Real physical extraction removes seven");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) == 20
                        && f.saved.registry().stock(f.cell, f.iron).total() == 73,
                    "Stock replenishes only exact deficit: buffer="
                        + f.buffer.inventory().total(f.iron)
                        + " cell="
                        + f.saved.registry().stock(f.cell, f.iron).total()
                        + " status="
                        + f.runtime.imports().status(f.buffer)
                        + " membership="
                        + f.runtime.membership(f.buffer.position()).status()
                        + " diagnostic="
                        + f.runtime.diagnostic()
                        + " transfer="
                        + f.saved.transfers().unresolved(f.buffer.handle().id())))
        .thenSucceed();
  }

  /** M145: as above, with a rebuild of the buffer's network before the seven are taken. */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void stockRecoversAfterANetworkRebuild(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 100);
              f.buffer.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 20));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) == 20
                        && f.saved.registry().stock(f.cell, f.iron).total() == 80,
                    "Stock reaches exact physical target"))
        .thenExecute(
            () ->
                // A rebuild publishes a new topology generation, so a catalog cursor kept from
                // before it is refused; the buffer must drop it and start over (M145).
                WorldNetworks.changed(helper.getLevel(), helper.absolutePos(BUFFER)))
        .thenWaitUntil(() -> ready(helper, f))
        .thenIdle(30)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.buffer.inventory().total(f.iron) == 20
                      && f.saved.registry().stock(f.cell, f.iron).total() == 80,
                  "At-goal polling does not over-withdraw");
              var extracted = f.buffer.capability().extractItem(0, 7, false);
              helper.assertTrue(
                  extracted.getCount() == 7, "Real physical extraction removes seven");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) == 20
                        && f.saved.registry().stock(f.cell, f.iron).total() == 73,
                    "Stock replenishes only exact deficit: buffer="
                        + f.buffer.inventory().total(f.iron)
                        + " cell="
                        + f.saved.registry().stock(f.cell, f.iron).total()
                        + " status="
                        + f.runtime.imports().status(f.buffer)
                        + " membership="
                        + f.runtime.membership(f.buffer.position()).status()
                        + " diagnostic="
                        + f.runtime.diagnostic()
                        + " transfer="
                        + f.saved.transfers().unresolved(f.buffer.handle().id())))
        .thenSucceed();
  }

  /**
   * Q108 (M147): discovery visits only the asking network's cells. A second network beside this one
   * has its own cell, and the world holds every other test's cells too; the buffer's network lists
   * exactly its own cell, and a catalog read finds the iron on its first page.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void discoveryVisitsOnlyItsOwnNetworksCells(GameTestHelper helper) {
    var f = setup(helper);
    Net.in(helper).controller(new BlockPos(4, 1, 4));
    StorageDriveEntity foreign = Net.in(helper).drive(new BlockPos(4, 1, 3), CellTier.STARTER_ITEM);
    var foreignCell = CellHandleData.read(foreign.cell(0));
    int[] pages = {0};
    boolean[] found = {false}, busy = {false};
    WorldStorageCatalog.Cursor[] cursor = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenWaitUntil(
            () -> {
              var local = f.runtime.storageDevices(f.buffer.position());
              helper.assertTrue(
                  local != null
                      && local.equals(java.util.Set.of(f.cell.backing()))
                      && !local.contains(foreignCell.backing())
                      && f.saved.registry().handles(null, 16).handles().size() > 1,
                  "The network lists exactly its own cell: " + local);
            })
        .thenExecute(() -> f.saved.registry().insert(f.cell, f.iron, 5))
        .thenWaitUntil(
            () -> {
              if (!busy[0] && !found[0]) {
                busy[0] = true;
                var submission =
                    f.runtime
                        .transfers()
                        .allKeys()
                        .page(
                            UUID.randomUUID(),
                            f.actor,
                            f.buffer.position(),
                            ResourceKind.ITEM,
                            cursor[0],
                            page -> {
                              busy[0] = false;
                              pages[0]++;
                              if (page.rows().stream().anyMatch(row -> row.key().equals(f.iron)))
                                found[0] = true;
                              else cursor[0] = page.next();
                            });
                if (submission.rejection() != OperationResult.Reason.OK) busy[0] = false;
              }
              helper.assertTrue(found[0], "Iron found after " + pages[0] + " pages");
            })
        .thenExecute(
            () ->
                helper.assertTrue(
                    pages[0] == 1, "The first page is this network's cell: " + pages[0]))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void fullBufferDoesNotExtractAndVacancyWakesExport(GameTestHelper helper) {
    var f = setup(helper);
    var stone =
        new PlatformResourceCodec(helper.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.buffer.inventory().insert(stone, 576);
              f.saved.registry().insert(f.cell, f.iron, 10);
              f.buffer.configure(f.actor, BufferRegistry.Mode.EXPORT, policy(f, 64));
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.iron).total() == 10
                      && f.buffer.inventory().total(stone) == 576,
                  "Full local target never extracts source");
              f.buffer.capability().extractItem(0, 64, false);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) == 10
                        && f.saved.registry().stock(f.cell, f.iron).total() == 0
                        && f.buffer.inventory().total(stone) == 512,
                    "Physical vacancy wakes exact export"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void stockNetworkInsertCapRechecksCurrentResidentAfterPlanning(
      GameTestHelper helper) {
    var f = setup(helper);
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 32);
              f.buffer.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 8));
              f.buffer.inventory().insert(f.iron, 5);
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      f.buffer.position(),
                      new WorldStorageTransfers.Digital(f.cell),
                      new WorldStorageTransfers.Buffered(
                          f.buffer.handle(), f.buffer.reference().configuration()),
                      f.iron,
                      16,
                      r -> result[0] = r);
            })
        .thenWaitUntil(
            () -> helper.assertTrue(result[0] != null, "Known partial network insertion completes"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) == 8
                        && f.saved.registry().stock(f.cell, f.iron).total() == 29
                        && result[0].transfer().delivered() == 3,
                    "Matching network insertion caps to live three-item stock room before extraction"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void knownInboundStagingCompletesStockWithoutReextractingRefill(
      GameTestHelper helper) {
    var f = setup(helper);
    UUID[] operation = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 15);
              f.buffer.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 12));
              var scope = f.runtime.membership(f.buffer.position()).scope();
              ResourcePort source =
                  new ResourcePort() {
                    public UUID backingId() {
                      return f.cell.backing();
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return f.saved.registry().simulateInsert(f.cell, key, amount).amount();
                    }

                    public long insert(ResourceKey key, long amount) {
                      return f.saved.registry().insert(f.cell, key, amount).amount();
                    }

                    public long extract(ResourceKey key, long amount) {
                      return f.saved.registry().extract(f.cell, key, amount, null).amount();
                    }
                  };
              ResourcePort reject =
                  new ResourcePort() {
                    public UUID backingId() {
                      return f.buffer.handle().id();
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    public long insert(ResourceKey key, long amount) {
                      return 0;
                    }

                    public long extract(ResourceKey key, long amount) {
                      throw new AssertionError("Never extract target");
                    }
                  };
              var context =
                  new TransferContext(
                      f.saved.registry().world(),
                      scope.network(),
                      scope.segment(),
                      f.buffer.position(),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          f.cell.backing(),
                          f.cell.backing(),
                          f.cell.generation(),
                          0),
                      new TransferContext.Endpoint(
                          TransferContext.Type.BUFFER,
                          f.buffer.handle().id(),
                          f.buffer.handle().id(),
                          f.buffer.handle().generation(),
                          f.buffer.reference().configuration()));
              operation[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      operation[0],
                      f.actor.id(),
                      f.iron,
                      source,
                      reject,
                      12,
                      12,
                      () -> true,
                      context);
              f.saved.transfers().unbind(operation[0]);
              f.saved.registry().insert(f.cell, f.iron, 20);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    !f.saved.transfers().contains(operation[0])
                        && f.buffer.inventory().total(f.iron) == 12,
                    "Known inbound operation delivers and retires through native buffer: stock="
                        + f.buffer.inventory().total(f.iron)
                        + " status="
                        + f.runtime.imports().status(f.buffer)
                        + " record="
                        + (f.saved.transfers().contains(operation[0])
                            ? f.saved.transfers().get(operation[0])
                            : "retired")))
        .thenIdle(30)
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.iron).total() == 23
                        && f.buffer.inventory().total(f.iron) == 12,
                    "Refilled digital source is not re-extracted; all 35 original/refilled units conserved"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void pausedDiscoveryAndZeroStockTargetNeverMutateSource(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 16);
              f.buffer.configure(f.actor, BufferRegistry.Mode.EXPORT, policy(f, 64));
              for (int i = 0; i < 32 && !f.runtime.imports().status(f.buffer).waiting(); i++)
                f.runtime.imports().tick();
              f.buffer.configure(f.actor, BufferRegistry.Mode.PAUSED);
            })
        .thenIdle(30)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.iron).total() == 16
                      && f.buffer.inventory().total(f.iron) == 0,
                  "Pause invalidates undispatched discovery/route");
              f.buffer.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 0));
            })
        .thenIdle(30)
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.iron).total() == 16
                        && f.buffer.inventory().total(f.iron) == 0,
                    "Zero physical goal never withdraws or voids source items"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void competingStockGoalsNeverTakeReservedSupplyOrDuplicateAvailability(
      GameTestHelper helper) {
    var f = setup(helper);
    var secondPos = new BlockPos(3, 1, 2);
    BufferedInterfaceEntity other = Net.in(helper, f.actor.id()).buffer(secondPos);
    var hold = UUID.randomUUID();
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, f);
              helper.assertTrue(
                  f.runtime.membership(other.position()).status() == NetworkTopology.Status.VALID,
                  "Competing holder joins root");
            })
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.iron, 19);
              helper.assertTrue(
                  f.saved.registry().reserve(f.cell, hold, f.iron, 7).amount() == 7,
                  "Another owner's seven-item hold exists");
              f.buffer.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 8));
              other.configure(f.actor, BufferRegistry.Mode.STOCK, policy(f, 8));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.iron) + other.inventory().total(f.iron) == 12,
                    "Competing goals consume only twelve unreserved units"))
        .thenIdle(30)
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.iron).total() == 7
                        && f.saved.registry().stock(f.cell, f.iron).reserved() == 7
                        && f.buffer.inventory().total(f.iron) <= 8
                        && other.inventory().total(f.iron) <= 8,
                    "Reserved source stays owned; best-effort targets conserve all nineteen without overshoot"))
        .thenSucceed();
  }
}
