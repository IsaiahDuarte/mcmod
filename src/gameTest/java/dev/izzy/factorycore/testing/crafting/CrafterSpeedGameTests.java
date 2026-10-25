package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.testing.Net;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * E2 (PD5 option a): Throughput and Overdrive in a Crafter raise crafting-table crafts per
 * scheduled run from 1 to 8 to 32, each run paying for its crafts on the shared scheduler.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CrafterSpeedGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      UNIT = new BlockPos(1, 1, 2);

  private CrafterSpeedGameTests() {}

  private static ItemStack sticks() {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_PLANKS));
    cells.set(3, new ItemStack(Items.OAK_PLANKS));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.STICK, 4), List.of()));
    return stack;
  }

  /** Runs a 64-craft stick job at {@code tier} and checks the largest jump in crafts per tick. */
  private static void measure(GameTestHelper h, ThroughputTier tier, int expected) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    Net.in(h, owner.id()).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns().insertItem(0, sticks(), false);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    var stick = codec.itemKey(new ItemStack(Items.STICK));
    var origin = Positions.of(h.getLevel(), h.absolutePos(UNIT));
    var jobs = WorldCraftingJobs.get(h.getLevel().getServer());
    UUID[] id = {null};
    long[] biggest = {0};
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer()).membership(origin).status()
                            == NetworkTopology.Status.VALID
                        && saved.registry().stock(cell, planks).loaded(),
                    "Network ready"))
        .thenExecute(
            () -> {
              if (tier != ThroughputTier.BASE) {
                h.assertTrue(
                    !unit.upgrade(
                            new NetworkPermissions.Actor(UUID.randomUUID(), false),
                            ThroughputTier.ADVANCED)
                        && unit.tier() == ThroughputTier.BASE,
                    "A player without Edit cannot upgrade");
                h.assertTrue(unit.upgrade(owner, ThroughputTier.ADVANCED), "Throughput installed");
              }
              if (tier == ThroughputTier.ENDGAME)
                h.assertTrue(unit.upgrade(owner, ThroughputTier.ENDGAME), "Overdrive installed");
              saved.registry().insert(cell, planks, 128);
              var submission = jobs.submit(owner, origin, stick, 256);
              h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
              id[0] = submission.job();
            })
        .thenWaitUntil(
            () -> {
              var job = saved.craftingJobs().get(id[0]);
              if (job != null) biggest[0] = Math.max(biggest[0], jobs.lastRunCrafts(id[0]));
              h.assertTrue(job == null, "Job finishes");
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  saved.registry().stock(cell, stick).total() == 256
                      && saved.registry().stock(cell, planks).total() == 0,
                  "256 sticks from 128 planks");
              h.assertTrue(
                  biggest[0] == expected,
                  tier + ": at most " + expected + " crafts per run, saw " + biggest[0]);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void baseCrafterMakesOneCraftPerRun(GameTestHelper h) {
    measure(h, ThroughputTier.BASE, 1);
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void throughputCrafterMakesEightCraftsPerRun(GameTestHelper h) {
    measure(h, ThroughputTier.ADVANCED, 8);
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void overdriveCrafterMakesThirtyTwoCraftsPerRun(GameTestHelper h) {
    measure(h, ThroughputTier.ENDGAME, 32);
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void breakingAnUpgradedCrafterReturnsItsUpgrades(GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    Net.in(h, owner.id()).controller(ROOT);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    var origin = Positions.of(h.getLevel(), h.absolutePos(UNIT));
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer()).membership(origin).status()
                        == NetworkTopology.Status.VALID,
                    "Network ready"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  unit.upgrade(owner, ThroughputTier.ADVANCED)
                      && unit.upgrade(owner, ThroughputTier.ENDGAME),
                  "Fully upgraded");
              var saved = unit.saveWithoutMetadata(h.getLevel().registryAccess());
              h.assertTrue(
                  saved.getString("FactoryCoreThroughput").equals("ENDGAME"), "Tier saved");
              h.getLevel().destroyBlock(h.absolutePos(UNIT), true);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var items =
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(UNIT)).inflate(2));
              boolean throughput =
                  items.stream().anyMatch(e -> e.getItem().is(ThroughputContent.THROUGHPUT.get()));
              boolean overdrive =
                  items.stream().anyMatch(e -> e.getItem().is(ThroughputContent.OVERDRIVE.get()));
              h.assertTrue(throughput && overdrive, "Both upgrades drop with the Crafter");
            })
        .thenSucceed();
  }
}
