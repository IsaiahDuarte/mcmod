package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.testing.Net;
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
public final class KeepStockGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      UNIT = new BlockPos(1, 1, 2);

  private KeepStockGameTests() {}

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

  private record Fixture(
      UUID owner,
      NodeRegistry.Position origin,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ResourceKey log,
      ResourceKey stick) {
    long stock(ResourceKey key) {
      return saved.registry().stock(cell, key).total();
    }
  }

  private static Fixture setup(GameTestHelper h) {
    var owner = UUID.randomUUID();
    NetworkNodeEntity root = Net.in(h, owner).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        Positions.of(h.getLevel(), h.absolutePos(UNIT)),
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        codec.itemKey(new ItemStack(Items.OAK_LOG)),
        codec.itemKey(new ItemStack(Items.STICK)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.origin).status()
                == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.log).loaded(),
        "Ready");
  }

  private static WorldCraftingJobs jobs(GameTestHelper h) {
    return WorldCraftingJobs.get(h.getLevel().getServer());
  }

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void ruleFillsAndRefillsWithOneJobInFlightAndStopsWhenRemoved(GameTestHelper h) {
    var f = setup(h);
    var actor = new NetworkPermissions.Actor(f.owner, false);
    int[] maxInFlight = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 4);
              h.assertTrue(jobs(h).keep(actor, f.origin, f.stick, 8, 8).isEmpty(), "Rule set");
            })
        .thenWaitUntil(
            () -> {
              maxInFlight[0] = Math.max(maxInFlight[0], jobs(h).jobs(f.owner).size());
              h.assertTrue(
                  f.stock(f.stick) == 8 && jobs(h).jobs(f.owner).isEmpty(), "Filled to eight");
            })
        .thenExecute(() -> f.saved.registry().extract(f.cell, f.stick, 4, null))
        .thenWaitUntil(
            () -> {
              maxInFlight[0] = Math.max(maxInFlight[0], jobs(h).jobs(f.owner).size());
              h.assertTrue(
                  f.stock(f.stick) == 8 && jobs(h).jobs(f.owner).isEmpty(), "Refilled to eight");
            })
        .thenExecute(
            () -> {
              h.assertTrue(maxInFlight[0] <= 1, "Never more than one job per rule");
              h.assertTrue(f.stock(f.log) == 2, "Two logs used: 8 then 4 sticks");
              var rule = jobs(h).rule(actor, f.origin, f.stick);
              h.assertTrue(rule != null && jobs(h).unkeep(actor, rule.id()), "Rule removed");
              f.saved.registry().extract(f.cell, f.stick, 5, null);
            })
        .thenIdle(250)
        .thenExecute(
            () ->
                h.assertTrue(
                    f.stock(f.stick) == 3 && jobs(h).jobs(f.owner).isEmpty(),
                    "Without the rule nothing refills"))
        .thenSucceed();
  }

  /**
   * M50 regression: setting Keep again mid-job used to make a new rule ID, so evaluation no longer
   * saw the running job as in flight and started a second one. Enough logs for both are stored so
   * the duplicate would run and overshoot.
   */
  private static void toggledMidJob(GameTestHelper h, long high, boolean offFirst) {
    var f = setup(h);
    var actor = new NetworkPermissions.Actor(f.owner, false);
    int[] maxInFlight = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.log, 20);
              h.assertTrue(jobs(h).keep(actor, f.origin, f.stick, 64, 64).isEmpty(), "Rule set");
            })
        .thenWaitUntil(() -> h.assertTrue(jobs(h).jobs(f.owner).size() == 1, "Job started"))
        .thenExecute(
            () -> {
              if (offFirst) {
                var rule = jobs(h).rule(actor, f.origin, f.stick);
                h.assertTrue(rule != null && jobs(h).unkeep(actor, rule.id()), "Keep off");
              }
              h.assertTrue(
                  jobs(h).keep(actor, f.origin, f.stick, high, high).isEmpty(), "Keep set again");
            })
        .thenWaitUntil(
            () -> {
              maxInFlight[0] = Math.max(maxInFlight[0], jobs(h).jobs(f.owner).size());
              h.assertTrue(
                  f.stock(f.stick) == high && jobs(h).jobs(f.owner).isEmpty(),
                  "Filled to " + high + ", found " + f.stock(f.stick) + " sticks");
            })
        .thenIdle(WorldCraftingJobs.RULE_TICKS + 20)
        .thenExecute(
            () -> {
              h.assertTrue(maxInFlight[0] == 1, "One job at a time, saw " + maxInFlight[0]);
              h.assertTrue(
                  f.stock(f.stick) == high && jobs(h).jobs(f.owner).isEmpty(),
                  "No overshoot past " + high + ", found " + f.stock(f.stick));
              h.assertTrue(f.stock(f.log) == 20 - high / 8, "Logs used only for the target");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 2400)
  public static void keepTurnedOffAndOnMidJobStartsNoSecondJob(GameTestHelper h) {
    toggledMidJob(h, 64, true);
  }

  @GameTest(template = "empty", timeoutTicks = 2400)
  public static void keepAmountChangedMidJobStartsNoSecondJob(GameTestHelper h) {
    toggledMidJob(h, 80, false);
  }

  /** M51: rule status and due-tick entries stay at the number of live rules. */
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void ruleStateStaysAtLiveRulesAcrossSetReplaceAndRemove(GameTestHelper h) {
    var f = setup(h);
    var actor = new NetworkPermissions.Actor(f.owner, false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var keys =
        List.of(Items.STONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.CLAY_BALL).stream()
            .map(item -> codec.itemKey(new ItemStack(item)))
            .toList();
    var sequence = h.startSequence().thenWaitUntil(() -> ready(h, f));
    for (int round = 0; round < 4; round++) {
      long amount = 4 + round;
      sequence =
          sequence
              .thenExecute(
                  () -> {
                    for (var key : keys)
                      h.assertTrue(
                          jobs(h).keep(actor, f.origin, key, amount, amount).isEmpty(), "Set");
                  })
              .thenIdle(5)
              .thenExecute(
                  () -> {
                    for (var key : keys)
                      h.assertTrue(
                          jobs(h).keep(actor, f.origin, key, amount + 1, amount + 1).isEmpty(),
                          "Replaced");
                  })
              .thenIdle(5)
              .thenExecute(
                  () -> {
                    h.assertTrue(
                        jobs(h).ruleStateEntries() <= liveRules(h),
                        "Replacing keeps one entry per rule, found " + jobs(h).ruleStateEntries());
                    for (var key : keys) {
                      var rule = jobs(h).rule(actor, f.origin, key);
                      h.assertTrue(rule != null && jobs(h).unkeep(actor, rule.id()), "Removed");
                    }
                  })
              .thenIdle(5)
              .thenExecute(
                  () ->
                      h.assertTrue(
                          jobs(h).ruleStateEntries() <= liveRules(h),
                          "Removed rules leave no entries, found " + jobs(h).ruleStateEntries()));
    }
    sequence.thenSucceed();
  }

  /** Every live rule on the server, including other GameTests' rules in the same batch. */
  private static int liveRules(GameTestHelper h) {
    return DeviceSavedData.get(h.getLevel().getServer()).craftingJobs().rules().size();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void ruleOfARevokedPrincipalStartsNothing(GameTestHelper h) {
    var f = setup(h);
    var member = UUID.randomUUID();
    var memberActor = new NetworkPermissions.Actor(member, false);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(f.origin)
                      .scope()
                      .network();
              var owner = new NetworkPermissions.Actor(f.owner, false);
              f.saved
                  .network(network)
                  .setGrants(owner, member, EnumSet.of(NetworkPermissions.Permission.CRAFT));
              f.saved.registry().insert(f.cell, f.log, 4);
              h.assertTrue(
                  jobs(h).keep(memberActor, f.origin, f.stick, 8, 8).isEmpty(),
                  "Member sets a rule");
              f.saved
                  .network(network)
                  .setGrants(owner, member, EnumSet.noneOf(NetworkPermissions.Permission.class));
            })
        .thenWaitUntil(
            () -> {
              var rule = jobs(h).rule(memberActor, f.origin, f.stick);
              h.assertTrue(
                  rule != null && jobs(h).ruleStatus(rule.id()).equals("denied"),
                  "Rule evaluated as denied");
            })
        .thenExecute(
            () ->
                h.assertTrue(
                    jobs(h).jobs(member).isEmpty()
                        && f.stock(f.stick) == 0
                        && f.saved.registry().stock(f.cell, f.log).reserved() == 0,
                    "No job and no reservation under revoked grants"))
        .thenSucceed();
  }
}
