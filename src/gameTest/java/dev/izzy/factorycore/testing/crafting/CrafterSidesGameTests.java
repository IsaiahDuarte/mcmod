package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * H8: the Crafter serves any machine beside it, taking turns between them, skips one that cannot
 * take the job, and never treats a pattern-feeding hopper, cable or network device as a machine.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CrafterSidesGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      CRAFTER = new BlockPos(1, 1, 2),
      EAST = new BlockPos(2, 1, 2),
      WEST = new BlockPos(0, 1, 2);

  private record Fixture(
      UUID owner,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      CrafterEntity crafter,
      BlastFurnaceBlockEntity east,
      BlastFurnaceBlockEntity west,
      ResourceKey ore,
      ResourceKey ingot) {}

  private CrafterSidesGameTests() {}

  private static ItemStack smelting() {
    var stack = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    stack.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    return stack;
  }

  /** A Crafter between two fuelled blast furnaces; its own facing points at neither. */
  private static Fixture setup(GameTestHelper h) {
    var owner = UUID.randomUUID();
    Net.in(h, owner).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(EAST, Blocks.BLAST_FURNACE);
    h.setBlock(WEST, Blocks.BLAST_FURNACE);
    BlastFurnaceBlockEntity east = h.getBlockEntity(EAST), west = h.getBlockEntity(WEST);
    east.setItem(1, new ItemStack(Items.COAL, 8));
    west.setItem(1, new ItemStack(Items.COAL, 8));
    h.setBlock(
        CRAFTER,
        CraftingContent.CRAFTER
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.UP));
    CrafterEntity crafter = h.getBlockEntity(CRAFTER);
    crafter.patterns().insertItem(0, smelting(), false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        crafter,
        east,
        west,
        codec.itemKey(new ItemStack(Items.RAW_IRON)),
        codec.itemKey(new ItemStack(Items.IRON_INGOT)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer())
                    .membership(Positions.of(h.getLevel(), h.absolutePos(CRAFTER)))
                    .status()
                == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.ore).loaded(),
        "Network ready");
  }

  private static UUID submit(GameTestHelper h, Fixture f, int amount) {
    var submission =
        WorldCraftingJobs.get(h.getLevel().getServer())
            .submit(
                new NetworkPermissions.Actor(f.owner, false),
                Positions.of(h.getLevel(), h.absolutePos(CRAFTER)),
                f.ingot,
                amount);
    h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
    return submission.job();
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void oneCrafterTakesTurnsBetweenTwoFurnaces(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    BlastFurnaceBlockEntity[] first = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.ore, 2);
              id[0] = submit(h, f, 2);
            })
        .thenWaitUntil(
            () -> {
              boolean east = f.east.getItem(0).is(Items.RAW_IRON);
              boolean west = f.west.getItem(0).is(Items.RAW_IRON);
              h.assertTrue(east || west, "The first craft goes into one furnace");
              first[0] = east ? f.east : f.west;
            })
        .thenWaitUntil(
            () -> {
              var other = first[0] == f.east ? f.west : f.east;
              h.assertTrue(
                  other.getItem(0).is(Items.RAW_IRON),
                  "The next craft goes into the other furnace");
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job finishes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.saved.registry().stock(f.cell, f.ingot).total() == 2
                        && f.saved.registry().stock(f.cell, f.ore).total() == 0
                        && f.east.getItem(2).isEmpty()
                        && f.west.getItem(2).isEmpty(),
                    "Both ingots are collected and stored; nothing is left in either furnace"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void aFurnaceHoldingTheOutputIsSkippedForTheOther(GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.east.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
              f.saved.registry().insert(f.cell, f.ore, 1);
              id[0] = submit(h, f, 1);
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.saved.craftingJobs().get(id[0]) == null, "Job finishes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.east.getItem(2).getCount() == 3
                        && f.east.getItem(0).isEmpty()
                        && f.saved.registry().stock(f.cell, f.ingot).total() == 1,
                    "The dirty furnace is left alone and the clean one makes the ingot"))
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void hoppersCablesAndDevicesAreNotMachines(GameTestHelper h) {
    var f = setup(h);
    // Above: a hopper pointing down into the Crafter feeds it patterns. Below: a chest.
    h.setBlock(
        CRAFTER.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    h.setBlock(CRAFTER.below(), Blocks.CHEST);
    h.setBlock(CRAFTER.south(), NetworkContent.CABLE.get());
    var sides = f.crafter.machineSides();
    h.assertTrue(
        sides.size() == 3
            && sides.containsAll(List.of(Direction.EAST, Direction.WEST, Direction.DOWN)),
        "Furnaces and the chest are machines; the feeding hopper, cable and controller are not: "
            + sides);
    h.succeed();
  }
}
