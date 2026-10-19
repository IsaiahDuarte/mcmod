package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A10 world integration: programs share the server's bounded scheduler with every other network,
 * report throttling and latency visibly, and one program's budget failure does not stop another.
 * The overload tests saturate the shared scheduler, so each runs in its own batch.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ProgramLimitsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CONTROLLER = new BlockPos(1, 1, 3),
      SECOND = new BlockPos(0, 1, 1),
      MACHINE = new BlockPos(2, 1, 1),
      CHEST = new BlockPos(3, 1, 1);
  private static final String EMPTY =
      "every 10 ticks as \"empty\" {\n"
          + "  move item(\"iron_ingot\") from machines(\"out\") to storage limit 4\n}";

  private record Fixture(
      NetworkPermissions.Actor owner,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      java.util.List<DeviceRegistry.Handle> cells,
      ProgramControllerEntity controller,
      MachineInterfaceEntity machine,
      ChestBlockEntity chest,
      PlatformResourceCodec codec) {
    long stored() {
      long total = 0;
      for (var each : cells)
        total +=
            saved.registry().stock(each, codec.itemKey(new ItemStack(Items.IRON_INGOT))).total();
      return total;
    }

    /** Ingots held by in-flight transfer staging between the chest and storage. */
    long staged() {
      var ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
      long total = 0;
      var backings = cells.stream().map(DeviceRegistry.Handle::backing).toList();
      for (var stored : saved.transfers().snapshot()) {
        var move = stored.transfer();
        if (move.key().equals(ingot)
            && (backings.contains(move.source()) || backings.contains(move.destination())))
          total += move.staged();
      }
      return total;
    }

    int inChest() {
      int total = 0;
      for (int slot = 0; slot < chest.getContainerSize(); slot++)
        if (chest.getItem(slot).is(Items.IRON_INGOT)) total += chest.getItem(slot).getCount();
      return total;
    }
  }

  /**
   * Switch for synthetic load. It also expires on its own so a failed test can never leave the
   * shared scheduler saturated for later batches.
   */
  private static final class Overload {
    private final net.minecraft.server.MinecraftServer server;
    private final int until;
    boolean on;

    Overload(GameTestHelper h, boolean on, int ticks) {
      server = h.getLevel().getServer();
      until = server.getTickCount() + ticks;
      this.on = on;
    }

    boolean active() {
      return on && server.getTickCount() < until;
    }
  }

  /**
   * Keeps the shared queue saturated: a run resubmits itself into the slot it just freed and tops
   * up one slot that real work may have freed.
   */
  private static final class Filler implements Runnable {
    private static final UUID NETWORK = new UUID(0xF111L, 0);
    private final FairScheduler scheduler;
    private final Overload on;
    private final int[] next;
    private final UUID program;
    private final String key;

    Filler(FairScheduler scheduler, Overload on, int[] next, int index) {
      this.scheduler = scheduler;
      this.on = on;
      this.next = next;
      program = new UUID(0xF111L, 1 + index / FairScheduler.Limits.defaults().queuedPerProgram());
      key = "fill:" + index;
    }

    /** Submits fresh fillers until the shared queue refuses one; returns how many it accepted. */
    static int fill(FairScheduler scheduler, Overload on, int[] next, int max) {
      int accepted = 0;
      while (accepted < max && next[0] < 60 * FairScheduler.Limits.defaults().queuedPerProgram()) {
        var filler = new Filler(scheduler, on, next, next[0]++);
        if (scheduler.submit(NETWORK, filler.program, filler.key, 1, filler)
            != FairScheduler.Admission.ACCEPTED) return accepted;
        accepted++;
      }
      return accepted;
    }

    @Override
    public void run() {
      if (!on.active()) return;
      scheduler.submit(NETWORK, program, key, 1, this);
      fill(scheduler, on, next, 1);
    }
  }

  private ProgramLimitsGameTests() {}

  private static Fixture setup(GameTestHelper h, int cells) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.id());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    var handles = new java.util.ArrayList<DeviceRegistry.Handle>();
    for (int i = 0; i < cells; i++) {
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
      handles.add(CellHandleData.read(drive.cell(i)));
    }
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    h.setBlock(
        MACHINE,
        MachineInterfaceContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    MachineInterfaceEntity machine = h.getBlockEntity(MACHINE);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    h.setBlock(CHEST, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(CHEST);
    chest.setItem(0, new ItemStack(Items.IRON_INGOT, 20));
    return new Fixture(
        owner,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        java.util.List.copyOf(handles),
        h.getBlockEntity(CONTROLLER),
        machine,
        chest,
        new PlatformResourceCodec(h.getLevel().registryAccess()));
  }

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos pos) {
    return new NodeRegistry.Position(
        h.getLevel().dimension().location().toString(), h.absolutePos(pos).asLong());
  }

  private static void ready(GameTestHelper h, Fixture f) {
    var networks = WorldNetworks.get(h.getLevel().getServer());
    h.assertTrue(
        networks.membership(position(h, CONTROLLER)).status() == NetworkTopology.Status.VALID
            && networks.membership(position(h, MACHINE)).status() == NetworkTopology.Status.VALID
            && f.saved
                .registry()
                .stock(f.cell, f.codec.itemKey(new ItemStack(Items.STONE)))
                .loaded(),
        "Network ready");
  }

  private static void label(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.machine.applySettings(
                f.owner,
                new ConnectorSettings(
                    ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 27, 64),
                "out")
            == dev.izzy.factorycore.core.network.NetworkAuthority.Decision.ALLOWED,
        "Machine labeled");
  }

  private static WorldPrograms programs(GameTestHelper h) {
    return WorldPrograms.get(h.getLevel().getServer());
  }

  private static void deploy(
      GameTestHelper h, Fixture f, ProgramControllerEntity controller, String source) {
    controller.energyStorage().receiveEnergy(1000, false);
    controller.draft(source);
    var diagnostics = programs(h).deploy(f.owner, controller);
    h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
  }

  private static void conserved(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.stored() + f.staged() + f.inChest() == 20,
        "Ingots conserved: stored "
            + f.stored()
            + " staged "
            + f.staged()
            + " chest "
            + f.inChest());
  }

  @GameTest(template = "empty", batch = "program_queue_overload", timeoutTicks = 1600)
  public static void saturatedSharedQueueThrottlesProgramsVisiblyAndTheyRecover(GameTestHelper h) {
    var f = setup(h, 1);
    var scheduler = WorldNetworks.get(h.getLevel().getServer()).scheduler();
    var overload = new Overload(h, false, 1200);
    int[] next = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> deploy(h, f, f.controller, EMPTY))
        .thenWaitUntil(() -> h.assertTrue(f.stored() >= 4, "Program moves before the overload"))
        .thenExecute(
            () -> {
              overload.on = true;
              Filler.fill(scheduler, overload, next, FairScheduler.Limits.defaults().queuedTotal());
              h.assertTrue(
                  scheduler.backlog() == FairScheduler.Limits.defaults().queuedTotal(),
                  "Shared queue saturated: " + scheduler.backlog());
            })
        .thenWaitUntil(
            () -> {
              conserved(h, f);
              h.assertTrue(
                  programs(h).status(f.controller).equals("throttled")
                      && f.controller.logs().stream()
                          .anyMatch(
                              line ->
                                  line.equals(
                                      "rule empty throttled: server work queue_full; retrying at"
                                          + " its next interval"))
                      && f.controller.state() == ProgramControllerEntity.State.RUNNING,
                  "Refused admission is visible, logged once and leaves the deployment running: "
                      + programs(h).status(f.controller)
                      + " "
                      + f.controller.logs());
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              conserved(h, f);
              h.assertTrue(
                  scheduler.backlog()
                      <= FairScheduler.Limits.defaults().queuedTotal()
                          + FairScheduler.RESERVED_SLOTS,
                  "The queue stays bounded under sustained overload");
              h.assertTrue(
                  f.controller.logs().stream().filter(line -> line.contains("throttled")).count()
                      == 1,
                  "Repeated refusals do not flood the log: " + f.controller.logs());
              overload.on = false;
            })
        .thenWaitUntil(
            () -> {
              conserved(h, f);
              h.assertTrue(
                  f.stored() == 20
                      && f.inChest() == 0
                      && !programs(h).status(f.controller).equals("throttled"),
                  "Once the overload drains the program runs again: "
                      + f.stored()
                      + " "
                      + programs(h).status(f.controller));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", batch = "program_share_overload", timeoutTicks = 2400)
  public static void saturatedNetworkStillGivesTheProgramItsShareWithBoundedLatency(
      GameTestHelper h) {
    var f = setup(h, 1);
    var networks = WorldNetworks.get(h.getLevel().getServer());
    var scheduler = networks.scheduler();
    var overload = new Overload(h, true, 2000);
    long[] worst = {0};
    var cost = FairScheduler.Limits.defaults().programCredits();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              // 60 other programs in the same network each keep a maximum-cost task queued, so
              // the network's per-tick credits admit only four of them per tick.
              var network = networks.membership(position(h, CONTROLLER)).scope().network();
              for (int p = 0; p < 60; p++) {
                var program = new UUID(0xF222L, p);
                Runnable[] task = {null};
                task[0] =
                    () -> {
                      if (overload.active())
                        scheduler.submit(network, program, "heavy", cost, task[0]);
                    };
                h.assertTrue(
                    scheduler.submit(network, program, "heavy", cost, task[0])
                        == FairScheduler.Admission.ACCEPTED,
                    "Competing program admitted");
              }
              deploy(h, f, f.controller, EMPTY);
            })
        .thenWaitUntil(
            () -> {
              conserved(h, f);
              worst[0] = Math.max(worst[0], programs(h).lag(f.controller));
              h.assertTrue(
                  f.stored() >= 8
                      && f.controller.logs().stream()
                          .anyMatch(
                              line -> line.matches("rule empty ran \\d+ ticks late: server busy")),
                  "The program keeps progressing under saturation and reports its delay: "
                      + f.stored()
                      + " "
                      + programs(h).status(f.controller)
                      + " "
                      + f.controller.logs());
            })
        .thenExecute(
            () -> {
              overload.on = false;
              h.assertTrue(
                  worst[0] > 0 && worst[0] < 100,
                  "Latency is measured and bounded by the fair rotation: " + worst[0]);
              h.assertTrue(
                  f.controller.state() == ProgramControllerEntity.State.RUNNING,
                  "No failure under saturation");
            })
        .thenWaitUntil(
            () -> {
              conserved(h, f);
              h.assertTrue(f.stored() == 20, "Program finishes once the competitors stop");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void hostWorkBudgetTrapsOneProgramWhileItsNeighbourKeepsWorking(GameTestHelper h) {
    var f = setup(h, 2);
    h.setBlock(SECOND, ProgramContent.BLOCK.get());
    ProgramControllerEntity heavy = h.getBlockEntity(SECOND);
    var source = new StringBuilder("every 10 ticks as \"census\" {\n");
    // Two item cells: every stock read costs two catalog pages, so 130 reads need 260 units.
    for (int i = 0; i < 130; i++) source.append(" if stock(item(\"stone\")) < 0 { log \"x\" }\n");
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              deploy(h, f, heavy, source.append("}").toString());
              deploy(h, f, f.controller, EMPTY);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    heavy.state() == ProgramControllerEntity.State.TRAPPED
                        && heavy.message().contains("host work budget of 256 units")
                        && heavy.logs().stream().anyMatch(line -> line.startsWith("trap census")),
                    "Host-side work beyond the invocation budget traps that program: "
                        + heavy.state()
                        + " "
                        + heavy.message()))
        .thenWaitUntil(
            () -> {
              conserved(h, f);
              h.assertTrue(
                  f.stored() == 20 && f.controller.state() == ProgramControllerEntity.State.RUNNING,
                  "The neighbouring program keeps its share and finishes");
            })
        .thenSucceed();
  }
}
