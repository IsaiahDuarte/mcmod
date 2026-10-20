package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingInterfaceEntity;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.ProcessingJournal;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.testing.ConnectorFixture;
import java.util.List;
import java.util.Map;
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

/**
 * ADR 0057 regressions: the co-located processing journal quarantines uncertain machine calls and
 * save-order disagreements instead of replaying, refunding or delivering possibly duplicated units.
 * The provider is a real test block entity whose saved contents and journal share one record.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ProcessingJournalGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 0),
      INTERFACE = new BlockPos(1, 1, 2),
      MACHINE = new BlockPos(2, 1, 2),
      CONTROLLER = new BlockPos(0, 1, 1);

  private record Fixture(
      UUID owner,
      NodeRegistry.Position origin,
      NodeRegistry.Position target,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ConnectorFixture.ProviderEntity machine,
      ResourceKey cobble,
      ResourceKey stone) {
    long stock(ResourceKey key) {
      return saved.registry().stock(cell, key).total();
    }

    int held(net.minecraft.world.item.Item item) {
      int total = 0;
      for (int slot = 0; slot < machine.items.getSlots(); slot++)
        if (machine.items.getStackInSlot(slot).is(item))
          total += machine.items.getStackInSlot(slot).getCount();
      return total;
    }

    CraftingJob job(UUID id) {
      return saved.craftingJobs().get(id);
    }

    ProcessingJournal.Entry journal() {
      return ProcessingJournal.read(machine, saved.registry().world());
    }

    /** Journaled inserted units, or -1 without a journal. */
    long journaled() {
      var entry = journal();
      return entry == null ? -1 : entry.inserted();
    }

    boolean pending() {
      var entry = journal();
      return entry != null && entry.pending();
    }
  }

  private ProcessingJournalGameTests() {}

  private static ItemStack pattern() {
    var stack = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    stack.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.COBBLESTONE, 2)),
            List.of(new ItemStack(Items.STONE))));
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
    h.setBlock(MACHINE, ConnectorFixture.BLOCK.get());
    h.setBlock(
        INTERFACE,
        CraftingContent.INTERFACE
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    CraftingInterfaceEntity face = h.getBlockEntity(INTERFACE);
    face.patterns().insertItem(0, pattern(), false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var dimension = h.getLevel().dimension().location().toString();
    return new Fixture(
        owner,
        new NodeRegistry.Position(dimension, h.absolutePos(INTERFACE).asLong()),
        new NodeRegistry.Position(dimension, h.absolutePos(MACHINE).asLong()),
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        h.getBlockEntity(MACHINE),
        codec.itemKey(new ItemStack(Items.COBBLESTONE)),
        codec.itemKey(new ItemStack(Items.STONE)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.origin).status()
                == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.cobble).loaded(),
        "Network ready");
  }

  private static WorldCraftingJobs jobs(GameTestHelper h) {
    return WorldCraftingJobs.get(h.getLevel().getServer());
  }

  private static NetworkPermissions.Actor actor(UUID id) {
    return new NetworkPermissions.Actor(id, false);
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  private static int resolve(GameTestHelper h) {
    var server = h.getLevel().getServer();
    var at = h.absolutePos(MACHINE);
    try {
      return server
          .getCommands()
          .getDispatcher()
          .execute(
              "factorycore recovery processing resolve %d %d %d"
                  .formatted(at.getX(), at.getY(), at.getZ()),
              server.createCommandSourceStack().withLevel(h.getLevel()));
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
      throw new AssertionError(invalid);
    }
  }

  /** Simulates the machine processing what it holds: two cobblestone become one stone. */
  private static void processInMachine(Fixture f) {
    for (int slot = 0; slot < f.machine.items.getSlots(); slot++)
      f.machine.items.setStackInSlot(slot, ItemStack.EMPTY);
    f.machine.items.setStackInSlot(1, new ItemStack(Items.STONE));
  }

  private static int recoveryFiles(GameTestHelper h) {
    var dir =
        h.getLevel()
            .getServer()
            .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("factorycore-device-recovery");
    if (!java.nio.file.Files.isDirectory(dir)) return 0;
    try (var files = java.nio.file.Files.list(dir)) {
      return (int) files.count();
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void callThatFailsAfterAcceptingIsQuarantinedAndNeverReplayed(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    int[] audits = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.partitioned = false;
              f.machine.afterInsertion =
                  () -> {
                    throw new IllegalStateException("Injected failure after accepting inputs");
                  };
              f.saved.registry().insert(f.cell, f.cobble, 2);
              var submitted = jobs(h).submit(actor(f.owner), f.origin, f.stone, 1);
              h.assertTrue(submitted.job() != null, "Submitted: " + submitted.reason());
              id[0] = submitted.job();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]) != null && f.job(id[0]).reason().equals("uncertain"),
                    "The failed call is reported as uncertain"))
        .thenIdle(60)
        .thenExecute(
            () -> {
              var job = f.job(id[0]);
              h.assertTrue(
                  f.held(Items.COBBLESTONE) == 2
                      && job.stagedAmount(f.cobble) == 2
                      && job.dispatch().inserted().isEmpty()
                      && job.reason().equals("uncertain")
                      && f.pending(),
                  "Machine keeps its two accepted units, staging is unchanged and the pending"
                      + " journal mark stays: no retry inserted a second copy");
              f.machine.afterInsertion = () -> {};
              h.assertTrue(jobs(h).cancel(actor(f.owner), id[0]), "Owner cancels");
            })
        .thenIdle(60)
        .thenExecute(
            () -> {
              var job = f.job(id[0]);
              h.assertTrue(
                  job != null
                      && job.state() == CraftingJob.State.CANCELLING
                      && job.stagedAmount(f.cobble) == 2
                      && f.stock(f.cobble) == 0
                      && f.held(Items.COBBLESTONE) == 2,
                  "Cancellation freezes the possibly duplicated staging: nothing delivered");
              // The operator inspects the machine, removes the two units the job record still
              // owns as staging, then resolves; the job record becomes authoritative.
              f.machine.items.setStackInSlot(0, ItemStack.EMPTY);
              f.machine.items.setStackInSlot(1, ItemStack.EMPTY);
              audits[0] = recoveryFiles(h);
              h.assertTrue(resolve(h) == 1, "Operator resolves with evidence");
              h.assertTrue(recoveryFiles(h) == audits[0] + 1, "Before-state evidence written");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.stock(f.cobble) == 2 && f.held(Items.COBBLESTONE) == 0,
                    "Cancelled staging returns once; exactly two units exist"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void machineSavedBeforeItsInsertionFreezesTheJobUntilResolved(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.partitioned = false;
              f.saved.registry().insert(f.cell, f.cobble, 2);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stone, 1).job();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]).dispatch() != null
                        && f.job(id[0]).dispatch().inserted().getOrDefault(f.cobble, 0L) == 2
                        && f.journaled() == 2
                        && !f.pending(),
                    "Both inputs inserted and journaled"))
        .thenExecute(
            () -> {
              // Inject the older chunk: the machine as saved before the insertion, with its
              // journal at the empty dispatch, while the job table remembers the insertion.
              var dispatch = f.job(id[0]).dispatch();
              f.machine.items.setStackInSlot(0, ItemStack.EMPTY);
              f.machine.items.setStackInSlot(1, ItemStack.EMPTY);
              ProcessingJournal.write(
                  f.machine,
                  f.saved.registry().world(),
                  id[0],
                  new CraftingJob.Dispatch(
                      dispatch.machine(),
                      dispatch.target(),
                      dispatch.providers(),
                      Map.of(),
                      Map.of()),
                  false);
              f.machine.items.setStackInSlot(1, new ItemStack(Items.STONE));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]).reason().equals("save_mismatch"), "Mismatch is diagnosed"))
        .thenIdle(60)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.held(Items.STONE) == 1
                      && f.job(id[0]).dispatch().collected().isEmpty()
                      && f.stock(f.stone) == 0,
                  "No output is collected from a machine that disagrees with the job");
              h.assertTrue(jobs(h).cancel(actor(f.owner), id[0]), "Owner cancels");
            })
        .thenIdle(60)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.job(id[0]) != null && f.job(id[0]).reason().equals("save_mismatch"),
                  "Cancellation stays frozen until an operator resolves");
              h.assertTrue(resolve(h) == 1, "Operator resolves");
              h.assertTrue(
                  f.journaled() == 2 && !f.pending(), "Journal now matches the saved job record");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.stock(f.stone) == 1 && f.held(Items.STONE) == 0,
                    "Bounded cleanup collects the machine's output once; no input refund"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.stock(f.cobble) == 0, "Inserted inputs are never refunded by recovery"))
        .thenSucceed();
  }

  /**
   * Found by the two-JVM data-old run: older SavedData claims an insertion while the newer chunk
   * holds no journal. The job must stop, and the operator must be able to resolve it.
   */
  @GameTest(template = "empty", timeoutTicks = 900)
  public static void missingJournalForASavedDispatchIsQuarantinedAndResolvable(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    int[] files = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.partitioned = false;
              f.saved.registry().insert(f.cell, f.cobble, 2);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stone, 1).job();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]).dispatch() != null && f.journaled() == 2 && !f.pending(),
                    "Both inputs inserted and journaled"))
        .thenExecute(
            () -> {
              // The newer chunk: the machine already returned its output and dropped the journal.
              for (int slot = 0; slot < f.machine.items.getSlots(); slot++)
                f.machine.items.setStackInSlot(slot, ItemStack.EMPTY);
              ProcessingJournal.clear(f.machine);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]).reason().equals("save_mismatch"),
                    "A missing journal for saved progress is diagnosed"))
        .thenExecute(
            () -> {
              files[0] = recoveryFiles(h);
              h.assertTrue(resolve(h) == 1, "Operator resolves a missing journal");
              h.assertTrue(recoveryFiles(h) == files[0] + 1, "Absence is preserved as evidence");
              h.assertTrue(
                  f.journaled() == 2 && !f.pending(), "Journal now matches the saved job record");
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              h.assertTrue(
                  !f.job(id[0]).reason().equals("save_mismatch"), "Job is no longer quarantined");
              h.assertTrue(
                  f.stock(f.cobble) == 0 && f.stock(f.stone) == 0,
                  "Nothing is refunded or invented for units the skew lost");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void unfinishedJournalBlocksNewDispatchBeforeAnyIngredientIsPulled(
      GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.partitioned = false;
              ProcessingJournal.write(
                  f.machine,
                  f.saved.registry().world(),
                  UUID.randomUUID(),
                  new CraftingJob.Dispatch(
                      f.origin, f.target, Map.of(), Map.of(f.cobble, 1L), Map.of()),
                  false);
              f.saved.registry().insert(f.cell, f.cobble, 2);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stone, 1).job();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]).reason().equals("machine_journal"),
                    "An unfinished foreign journal blocks dispatch"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.job(id[0]).dispatch() == null
                      && f.job(id[0]).staged().isEmpty()
                      && f.saved.registry().stock(f.cell, f.cobble).reserved() == 2,
                  "Ingredients stay reserved in storage; nothing was pulled");
              h.assertTrue(resolve(h) == 1, "Operator removes the orphaned journal");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.held(Items.COBBLESTONE) == 2 && f.journaled() == 2,
                    "Dispatch proceeds and journals its insertion"))
        .thenExecute(() -> processInMachine(f))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.job(id[0]) == null && f.stock(f.stone) == 1 && f.journal() == null,
                    "Job completes and clears its journal"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void emptyOrphanJournalIsClearedWithoutOperator(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.partitioned = false;
              ProcessingJournal.write(
                  f.machine,
                  f.saved.registry().world(),
                  UUID.randomUUID(),
                  new CraftingJob.Dispatch(f.origin, f.target, Map.of(), Map.of(), Map.of()),
                  false);
              f.saved.registry().insert(f.cell, f.cobble, 2);
              id[0] = jobs(h).submit(actor(f.owner), f.origin, f.stone, 1).job();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.held(Items.COBBLESTONE) == 2
                        && f.journal() != null
                        && f.journal().job().equals(id[0]),
                    "A journal proving nothing moved is replaced by the new dispatch"))
        .thenExecute(() -> processInMachine(f))
        .thenWaitUntil(
            () -> h.assertTrue(f.job(id[0]) == null && f.stock(f.stone) == 1, "Job completes"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void programCraftRequestIsNotResubmittedAfterControllerReload(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
    var programs = WorldPrograms.get(h.getLevel().getServer());
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(controller.origin())
                            .status()
                        == NetworkTopology.Status.VALID,
                    "Controller joined"))
        .thenExecute(
            () -> {
              f.machine.partitioned = false;
              // Enough stock for two jobs: only deduplication keeps a second one from starting.
              f.saved.registry().insert(f.cell, f.cobble, 4);
              controller.energyStorage().receiveEnergy(10_000, false);
              controller.draft("every 10 ticks as \"stone\" { craft 1 of item(\"stone\") }");
              h.assertTrue(programs.deploy(actor(f.owner), controller).isEmpty(), "Deployed");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.held(Items.COBBLESTONE) == 2, "The program's job dispatched to the machine"))
        .thenExecute(
            () -> {
              // Chunk unload/reload (or a restart) drops the controller's transient runtime.
              programs.removed(controller);
              programs.loaded(controller);
            })
        .thenIdle(60)
        .thenExecute(
            () -> {
              long jobs =
                  f.saved.craftingJobs().all().stream()
                      .filter(job -> job.principal().equals(f.owner))
                      .count();
              h.assertTrue(
                  jobs == 1 && f.saved.registry().stock(f.cell, f.cobble).reserved() == 0,
                  "Exactly one job for the program request; the spare stock stays unreserved: "
                      + jobs);
              programs.control(actor(f.owner), controller, ProgramControllerEntity.State.STOPPED);
              processInMachine(f);
            })
        .thenWaitUntil(() -> h.assertTrue(f.stock(f.stone) == 1, "The one job completes"))
        .thenSucceed();
  }
}
