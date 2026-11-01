package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q115: a Signal Interface set to Craft offers its item to crafting. A job that needs the item
 * turns it on, takes the units that arrive in storage, and turns it off when it has them or is
 * cancelled. The "machine" here is driven only by the lamp the Interface powers: while the lamp is
 * lit it outputs one oak log a second into storage, as an Importer would.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class EmitCraftGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      SIGNAL = new BlockPos(1, 1, 2),
      LAMP = new BlockPos(1, 1, 3),
      UNIT = new BlockPos(0, 1, 1);

  private record Fixture(
      ServerPlayer owner,
      SignalInterfaceEntity signal,
      StorageDriveEntity drive,
      ResourceKey log,
      ResourceKey planks) {}

  private EmitCraftGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, owner.getUUID()).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(SIGNAL, InterfaceContent.state(InterfaceKind.SIGNAL, Direction.NORTH));
    SignalInterfaceEntity signal = h.getBlockEntity(SIGNAL);
    signal.place(owner.getUUID());
    h.setBlock(LAMP, Blocks.REDSTONE_LAMP);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_LOG));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.OAK_PLANKS, 4), List.of()));
    unit.patterns().insertItem(0, pattern, false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        signal,
        drive,
        codec.itemKey(new ItemStack(Items.OAK_LOG)),
        codec.itemKey(new ItemStack(Items.OAK_PLANKS)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer())
                    .membership(Positions.of(h.getLevel(), h.absolutePos(SIGNAL)))
                    .status()
                == NetworkTopology.Status.VALID
            && DeviceSavedData.get(h.getLevel().getServer())
                .registry()
                .stock(CellHandleData.read(f.drive.cell(0)), f.log)
                .loaded(),
        "Network valid with its cell online");
  }

  private static void craftMode(Fixture f) {
    f.signal.configure(
        f.owner.getUUID(),
        new SignalRule(
            SignalRule.Condition.CRAFT, SignalRule.Compare.AT_LEAST, 1, SignalRule.Output.FULL),
        f.log);
  }

  private static WorldCraftingJobs.Submission request(
      GameTestHelper h, Fixture f, ResourceKey key, long amount) {
    return WorldCraftingJobs.get(h.getLevel().getServer())
        .submit(
            new NetworkPermissions.Actor(f.owner.getUUID(), false),
            Positions.of(h.getLevel(), h.absolutePos(ROOT)),
            key,
            amount);
  }

  private static long stored(GameTestHelper h, Fixture f, ResourceKey key) {
    return DeviceSavedData.get(h.getLevel().getServer())
        .registry()
        .stock(CellHandleData.read(f.drive.cell(0)), key)
        .total();
  }

  private static boolean lit(GameTestHelper h) {
    return h.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT);
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void aRedstoneMachineMakesTheMissingLogsForAPlankJob(GameTestHelper h) {
    var f = setup(h);
    UUID[] job = {null};
    long[] made = {0};
    // The redstone-only machine: one log a second while its lamp is lit.
    h.onEachTick(
        () -> {
          if (lit(h) && h.getTick() % 20 == 0) {
            DeviceSavedData.get(h.getLevel().getServer())
                .registry()
                .insert(CellHandleData.read(f.drive.cell(0)), f.log, 1);
            made[0]++;
          }
        });
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var none = request(h, f, f.planks, 8);
              h.assertTrue(
                  none.job() == null && none.reason() == JobStatus.MISSING,
                  "No logs and no Craft Interface: planks are missing: " + none.reason());
              craftMode(f);
            })
        .thenIdle(1)
        .thenExecute(
            () -> {
              h.assertTrue(f.signal.power() == 0 && !lit(h), "Idle Craft Interface: off");
              var submission = request(h, f, f.planks, 8);
              job[0] = submission.job();
              h.assertTrue(
                  job[0] != null, "The Craft Interface supplies two logs: " + submission.reason());
            })
        .thenWaitUntil(() -> h.assertTrue(lit(h), "A job waits for logs: the lamp is lit"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    DeviceSavedData.get(h.getLevel().getServer()).craftingJobs().get(job[0]) == null
                        && stored(h, f, f.planks) == 8,
                    "The job took two arriving logs, crafted and delivered 8 planks"))
        .thenWaitUntil(() -> h.assertTrue(!lit(h) && f.signal.power() == 0, "Then off again"))
        .thenIdle(45)
        .thenExecute(
            () -> {
              long logs = stored(h, f, f.log);
              h.assertTrue(
                  made[0] >= 2 && logs == made[0] - 2,
                  "Two logs were used; the rest stay in storage: made " + made[0] + ", " + logs);
              long before = made[0];
              h.runAfterDelay(
                  45, () -> h.assertTrue(made[0] == before, "The machine stopped with the lamp"));
            })
        .thenIdle(50)
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void cancellingTheJobTurnsTheSignalOff(GameTestHelper h) {
    var f = setup(h);
    UUID[] job = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              craftMode(f);
              job[0] = request(h, f, f.log, 3).job();
              h.assertTrue(job[0] != null, "A job for three logs from the Craft Interface");
            })
        .thenWaitUntil(() -> h.assertTrue(lit(h), "Waiting for logs: on"))
        .thenExecute(
            () -> {
              // One log arrives; it is now the job's.
              DeviceSavedData.get(h.getLevel().getServer())
                  .registry()
                  .insert(CellHandleData.read(f.drive.cell(0)), f.log, 1);
            })
        .thenWaitUntil(
            () -> {
              var running =
                  DeviceSavedData.get(h.getLevel().getServer()).craftingJobs().get(job[0]);
              h.assertTrue(
                  running != null && running.steps().getFirst().done() == 1,
                  "The arriving log counts as one craft");
            })
        .thenExecute(
            () ->
                h.assertTrue(
                    WorldCraftingJobs.get(h.getLevel().getServer())
                        .cancel(new NetworkPermissions.Actor(f.owner.getUUID(), false), job[0]),
                    "Cancel"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    !lit(h)
                        && f.signal.power() == 0
                        && DeviceSavedData.get(h.getLevel().getServer()).craftingJobs().get(job[0])
                            == null,
                    "Cancelled: the job ends and the signal turns off"))
        .thenExecute(
            () ->
                h.assertTrue(
                    stored(h, f, f.log) == 1
                        && DeviceSavedData.get(h.getLevel().getServer())
                                .registry()
                                .stock(CellHandleData.read(f.drive.cell(0)), f.log)
                                .available()
                            == 1,
                    "The arrived log is released, not lost"))
        .thenSucceed();
  }
}
