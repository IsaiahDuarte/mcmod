package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * D2 (ADR 0062): a program moves between two machines with no storage involved, through the shared
 * staged transfer path, refused by a processing lease and conserving items when the target is full.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachineToMachineGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CONTROLLER = new BlockPos(1, 1, 3),
      SOURCE = new BlockPos(2, 1, 1),
      FURNACE = new BlockPos(2, 2, 1),
      TARGET = new BlockPos(2, 1, 3),
      CHEST = new BlockPos(3, 1, 3);

  private static final String SMELTED =
      "every 10 ticks as \"smelted\" {\n"
          + "  move item(\"iron_ingot\") from machines(\"furnace\") to machines(\"chest\") limit 8\n}";

  private record Fixture(
      NetworkPermissions.Actor owner,
      DeviceSavedData saved,
      StorageDriveEntity drive,
      ProgramControllerEntity controller,
      MachineInterfaceEntity source,
      MachineInterfaceEntity target,
      FurnaceBlockEntity furnace,
      ChestBlockEntity chest,
      PlatformResourceCodec codec) {
    int ingots(Container container) {
      int total = 0;
      for (int slot = 0; slot < container.getContainerSize(); slot++)
        if (container.getItem(slot).is(Items.IRON_INGOT))
          total += container.getItem(slot).getCount();
      return total;
    }

    long stored() {
      return saved
          .registry()
          .stock(CellHandleData.read(drive.cell(0)), codec.itemKey(new ItemStack(Items.IRON_INGOT)))
          .total();
    }

    /** Ingots staged by transfers touching this fixture's two machines (tests run in parallel). */
    long staged() {
      var ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
      var connectors = WorldNetworks.get(source.getLevel().getServer()).connectors();
      var mine = new java.util.HashSet<UUID>();
      for (var machine : List.of(source, target)) {
        var handle = connectors.bind(machine).handle();
        if (handle != null) mine.add(handle.backing());
      }
      long total = 0;
      for (var stored : saved.transfers().snapshot()) {
        var move = stored.transfer();
        if (move.key().equals(ingot)
            && (mine.contains(move.source()) || mine.contains(move.destination())))
          total += move.staged();
      }
      return total;
    }
  }

  private MachineToMachineGameTests() {}

  private static Fixture setup(GameTestHelper h, int ingots) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var net = Net.in(h, owner.id());
    net.controller(ROOT);
    var drive = net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    h.setBlock(FURNACE, Blocks.FURNACE);
    FurnaceBlockEntity furnace = h.getBlockEntity(FURNACE);
    furnace.setItem(2, new ItemStack(Items.IRON_INGOT, ingots));
    h.setBlock(CHEST, Blocks.CHEST);
    // The furnace gives up its output only through its bottom face.
    var source = net.machine(SOURCE, Direction.UP);
    var target = net.machine(TARGET, Direction.EAST);
    return new Fixture(
        owner,
        DeviceSavedData.get(h.getLevel().getServer()),
        drive,
        h.getBlockEntity(CONTROLLER),
        source,
        target,
        furnace,
        h.getBlockEntity(CHEST),
        new PlatformResourceCodec(h.getLevel().registryAccess()));
  }

  private static void ready(GameTestHelper h) {
    var networks = WorldNetworks.get(h.getLevel().getServer());
    for (var position : List.of(CONTROLLER, SOURCE, TARGET))
      h.assertTrue(
          networks.membership(Positions.of(h.getLevel(), h.absolutePos(position))).status()
              == NetworkTopology.Status.VALID,
          "Network ready");
  }

  private static void label(
      GameTestHelper h, Fixture f, MachineInterfaceEntity machine, String name, int slots) {
    var decision =
        machine.applySettings(
            f.owner,
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, slots, 64),
            name);
    h.assertTrue(decision == NetworkAuthority.Decision.ALLOWED, name + " labeled: " + decision);
  }

  private static void deploy(GameTestHelper h, Fixture f) {
    f.controller.energyStorage().receiveEnergy(1000, false);
    f.controller.draft(SMELTED);
    var diagnostics = WorldPrograms.get(h.getLevel().getServer()).deploy(f.owner, f.controller);
    h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
  }

  private static void conserved(GameTestHelper h, Fixture f, int total) {
    long sum = f.ingots(f.furnace) + f.ingots(f.chest) + f.staged() + f.stored();
    h.assertTrue(
        sum == total,
        "Ingots conserved: furnace "
            + f.ingots(f.furnace)
            + " chest "
            + f.ingots(f.chest)
            + " staged "
            + f.staged()
            + " stored "
            + f.stored());
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void furnaceOutputMovesIntoAChestWithoutStorage(GameTestHelper h) {
    var f = setup(h, 20);
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.source, "furnace", 3))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.target, "chest", 27))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> deploy(h, f))
        .thenWaitUntil(
            () -> {
              conserved(h, f, 20);
              h.assertTrue(
                  f.ingots(f.chest) == 20 && f.ingots(f.furnace) == 0,
                  "Every ingot reaches the chest: " + f.ingots(f.chest));
            })
        .thenExecute(
            () -> h.assertTrue(f.stored() == 0 && f.staged() == 0, "Storage never held an ingot"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void fullTargetMovesNothingAndConservesItems(GameTestHelper h) {
    var f = setup(h, 20);
    for (int slot = 0; slot < f.chest.getContainerSize(); slot++)
      f.chest.setItem(slot, new ItemStack(Items.STONE, 64));
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.source, "furnace", 3))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.target, "chest", 27))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> deploy(h, f))
        .thenIdle(100)
        .thenExecute(
            () -> {
              conserved(h, f, 20);
              h.assertTrue(
                  f.ingots(f.furnace) + f.staged() == 20 && f.ingots(f.chest) == 0,
                  "A full chest receives nothing and the furnace keeps its ingots");
            })
        .thenExecute(() -> f.chest.setItem(0, ItemStack.EMPTY))
        .thenWaitUntil(
            () -> {
              conserved(h, f, 20);
              h.assertTrue(f.ingots(f.chest) == 20, "Room frees: they move");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 2600)
  public static void aLeasedTargetRefusesTheMoveAsBusy(GameTestHelper h) {
    var f = setup(h, 20);
    // A Crafter facing the chest: its processing job leases that machine.
    var cable = new BlockPos(2, 1, 4);
    var crafterAt = new BlockPos(3, 1, 4);
    h.setBlock(cable, NetworkContent.CABLE.get());
    h.setBlock(
        crafterAt,
        CraftingContent.CRAFTER
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.NORTH));
    CrafterEntity crafter = h.getBlockEntity(crafterAt);
    var pattern = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    pattern.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.COBBLESTONE)),
            List.of(new ItemStack(Items.STONE))));
    crafter.patterns().insertItem(0, pattern, false);
    var jobs = WorldCraftingJobs.get(h.getLevel().getServer());
    UUID[] job = {null};
    var chestAt = Positions.of(h.getLevel(), h.absolutePos(CHEST));
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.source, "furnace", 3))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.target, "chest", 27))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              f.saved
                  .registry()
                  .insert(
                      CellHandleData.read(f.drive.cell(0)),
                      f.codec.itemKey(new ItemStack(Items.COBBLESTONE)),
                      1);
              var submission =
                  jobs.submit(
                      f.owner,
                      Positions.of(h.getLevel(), h.absolutePos(crafterAt)),
                      f.codec.itemKey(new ItemStack(Items.STONE)),
                      1);
              h.assertTrue(submission.job() != null, "Job submitted: " + submission.reason());
              job[0] = submission.job();
            })
        .thenWaitUntil(() -> h.assertTrue(jobs.machineLeased(chestAt), "The job leases the chest"))
        .thenExecute(() -> deploy(h, f))
        .thenWaitUntil(
            () -> {
              conserved(h, f, 20);
              h.assertTrue(
                  f.ingots(f.furnace) == 20
                      && f.controller.logs().stream()
                          .anyMatch(
                              line -> line.contains("busy: a crafting job is using this machine")),
                  "The leased chest refuses with the busy reason: " + f.controller.logs());
            })
        .thenExecute(() -> h.assertTrue(jobs.cancel(f.owner, job[0]), "Owner cancels the job"))
        .thenWaitUntil(
            () -> {
              conserved(h, f, 20);
              h.assertTrue(f.ingots(f.chest) == 20, "After the lease the ingots move");
            })
        .thenSucceed();
  }

  /**
   * Regression: a run that came due while the network was rebuilding (a nearby chunk load, any
   * edit) paused the program for good as "Deployer lost Deploy permission". It now skips the run.
   */
  @GameTest(template = "empty", timeoutTicks = 800)
  public static void rebuildsWhileRunningNeverPauseTheProgram(GameTestHelper h) {
    var f = setup(h, 20);
    var controllerAt = h.absolutePos(CONTROLLER);
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.source, "furnace", 3))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> label(h, f, f.target, "chest", 27))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> deploy(h, f))
        .thenExecuteFor(
            60,
            () ->
                dev.izzy.factorycore.platform.network.WorldNetworks.changed(
                    h.getLevel(), controllerAt))
        .thenWaitUntil(
            () -> {
              h.assertTrue(
                  f.controller.state() == ProgramControllerEntity.State.RUNNING,
                  "Still running after rebuilds: "
                      + f.controller.state()
                      + " "
                      + f.controller.logs());
              conserved(h, f, 20);
              h.assertTrue(f.ingots(f.chest) == 20, "And it keeps moving");
            })
        .thenSucceed();
  }
}
