package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingUnitEntity;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CraftingJobGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      UNIT = new BlockPos(1, 1, 2);

  private record Fixture(
      UUID owner,
      NodeRegistry.Position origin,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      CraftingUnitEntity unit,
      ResourceKey log,
      ResourceKey planks,
      ResourceKey stick) {
    long stock(ResourceKey key) {
      return saved.registry().stock(cell, key).total();
    }

    long reserved(ResourceKey key) {
      return saved.registry().stock(cell, key).reserved();
    }

    /** Wood conserved in half-plank units: a log is 8, a plank 2 and a stick 1. */
    long woodInPlanks(CraftingJob job) {
      long staged =
          job == null
              ? 0
              : job.stagedAmount(log) * 8 + job.stagedAmount(planks) * 2 + job.stagedAmount(stick);
      return (stock(log) * 4 + stock(planks)) * 2 + stock(stick) + staged;
    }
  }

  private CraftingJobGameTests() {}

  private static ItemStack pattern(int[] cells, Item input, Item output, int count) {
    var stacks = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int cell : cells) stacks.set(cell, new ItemStack(input));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, stacks, new ItemStack(output, count), List.of()));
    return stack;
  }

  private static Fixture setup(GameTestHelper h) {
    var owner = UUID.randomUUID();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner);
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    h.setBlock(UNIT, CraftingContent.UNIT.get());
    CraftingUnitEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        new NodeRegistry.Position(
            h.getLevel().dimension().location().toString(), h.absolutePos(UNIT).asLong()),
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        unit,
        codec.itemKey(new ItemStack(Items.OAK_LOG)),
        codec.itemKey(new ItemStack(Items.OAK_PLANKS)),
        codec.itemKey(new ItemStack(Items.STICK)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    var networks = WorldNetworks.get(h.getLevel().getServer());
    h.assertTrue(
        networks.membership(f.origin).status() == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.log).loaded(),
        "Network and cell ready");
  }

  private static WorldCraftingJobs jobs(GameTestHelper h) {
    return WorldCraftingJobs.get(h.getLevel().getServer());
  }

  private static NetworkPermissions.Actor actor(UUID id) {
    return new NetworkPermissions.Actor(id, false);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void recursiveJobReservesOnceCraftsAtUnitAndDeliversExactOutput(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 2);
              var preview = jobs(h).preview(actor(f.owner), f.origin, f.stick, 8);
              h.assertTrue(
                  preview.reason().isEmpty()
                      && preview.plan().steps().size() == 2
                      && preview.plan().consumed().get(f.log) == 1
                      && f.reserved(f.log) == 0,
                  "Preview plans one log and reserves nothing");
              var submission = jobs(h).submit(actor(f.owner), f.origin, f.stick, 8);
              h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
              id[0] = submission.job();
              h.assertTrue(
                  f.reserved(f.log) == 1 && f.stock(f.log) == 2,
                  "Exactly the planned log is reserved once");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job finishes and retires"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.stock(f.stick) == 8
                      && f.stock(f.log) == 1
                      && f.stock(f.planks) == 0
                      && f.reserved(f.log) == 0,
                  "Eight sticks delivered, one log used, no planks or reservation left");
              h.assertTrue(
                  jobs(h).notices(f.owner).stream()
                      .anyMatch(n -> n.job().equals(id[0]) && !n.cancelled()),
                  "Requester sees a completion notice");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void shortageOrDeniedRequestReservesNothing(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var missing = jobs(h).submit(actor(f.owner), f.origin, f.stick, 8);
              h.assertTrue(
                  missing.job() == null
                      && missing.reason().equals("missing")
                      && missing.plan().missing().get(f.log) == 1
                      && f.saved.craftingJobs().all().isEmpty(),
                  "Shortage explains the missing log and creates no job");
              f.saved.registry().insert(f.cell, f.log, 2);
              var stranger = jobs(h).submit(actor(UUID.randomUUID()), f.origin, f.stick, 8);
              h.assertTrue(
                  stranger.reason().equals("denied") && f.reserved(f.log) == 0,
                  "A principal without CRAFT reserves nothing");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void cancellationReleasesReservationsAndReturnsStagingWithoutLoss(
      GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 16);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stick, 64).job();
              h.assertTrue(id[0] != null && f.reserved(f.log) == 8, "Eight logs reserved");
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(job != null && job.craftsDone() >= 3, "Job has made progress");
              h.assertTrue(f.woodInPlanks(job) == 64 * 2, "Wood conserved while running");
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  !jobs(h).cancel(actor(UUID.randomUUID()), id[0]), "Others cannot cancel");
              h.assertTrue(jobs(h).cancel(actor(f.owner), id[0]), "Requester cancels");
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Cancelled job retires"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.reserved(f.log) == 0 && f.woodInPlanks(null) == 64 * 2 && f.stock(f.log) >= 8,
                  "Reservations released; staged items returned; unreserved logs untouched");
              h.assertTrue(
                  jobs(h).notices(f.owner).stream()
                      .anyMatch(n -> n.job().equals(id[0]) && n.cancelled()),
                  "Cancellation notice");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void removedPatternPausesJobUntilItReturns(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    ItemStack[] removed = {ItemStack.EMPTY};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 2);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stick, 8).job();
              removed[0] = f.unit.patterns().extractItem(1, 1, false);
              h.assertTrue(!removed[0].isEmpty(), "Stick pattern removed after submission");
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job.state() == CraftingJob.State.WAITING
                      && job.reason().equals("pattern_missing")
                      && job.stagedAmount(f.planks) == 4,
                  "Planks are staged and the job waits for its stick pattern");
            })
        .thenExecute(() -> f.unit.patterns().insertItem(1, removed[0], false))
        .thenWaitUntil(
            () ->
                h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job resumes and finishes"))
        .thenExecute(() -> h.assertTrue(f.stock(f.stick) == 8, "Eight sticks delivered"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fullStorageKeepsOutputsStagedUntilSpaceReturns(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    var dirt =
        new PlatformResourceCodec(h.getLevel().registryAccess()).itemKey(new ItemStack(Items.DIRT));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 2);
              var room =
                  f.saved.registry().simulateInsert(f.cell, dirt, Long.MAX_VALUE / 4).amount();
              f.saved.registry().insert(f.cell, dirt, room);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stick, 8).job();
              h.assertTrue(id[0] != null, "Submitted into a full cell");
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job != null
                      && job.state() == CraftingJob.State.DELIVERING
                      && job.reason().equals("full")
                      && job.stagedAmount(f.stick) + f.stock(f.stick) == 8,
                  "Undeliverable sticks stay staged, never voided");
            })
        .thenExecute(() -> f.saved.registry().extract(f.cell, dirt, 64, null))
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Delivery completes"))
        .thenExecute(() -> h.assertTrue(f.stock(f.stick) == 8, "All eight sticks stored"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void revokedCraftGrantPausesBeforeTheNextCraft(GameTestHelper h) {
    var f = setup(h);
    var member = UUID.randomUUID();
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(f.origin)
                      .scope()
                      .network();
              f.saved
                  .network(network)
                  .setGrants(
                      actor(f.owner), member, EnumSet.of(NetworkPermissions.Permission.CRAFT));
              f.saved.registry().insert(f.cell, f.log, 2);
              id[0] = jobs(h).submit(actor(member), f.origin, f.stick, 8).job();
              h.assertTrue(id[0] != null, "Member with CRAFT submits");
              f.saved
                  .network(network)
                  .setGrants(
                      actor(f.owner), member, EnumSet.noneOf(NetworkPermissions.Permission.class));
            })
        .thenWaitUntil(
            () -> {
              var job = f.saved.craftingJobs().get(id[0]);
              h.assertTrue(
                  job.state() == CraftingJob.State.WAITING
                      && job.reason().equals("denied")
                      && job.craftsDone() == 0
                      && f.reserved(f.log) == 1,
                  "Revoked grant stops crafting; reservation is kept for the job");
            })
        .thenExecute(
            () -> {
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(f.origin)
                      .scope()
                      .network();
              f.saved
                  .network(network)
                  .setGrants(
                      actor(f.owner), member, EnumSet.of(NetworkPermissions.Permission.CRAFT));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Restored grant completes"))
        .thenExecute(() -> h.assertTrue(f.stock(f.stick) == 8, "Eight sticks delivered"))
        .thenSucceed();
  }
}
