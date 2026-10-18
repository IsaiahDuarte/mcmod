package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CraftingPlannerTest {
  private static ResourceKey item(String id) {
    return new ResourceKey(ResourceKind.ITEM, "minecraft:" + id, new byte[0]);
  }

  private static final ResourceKey LOG = item("oak_log"),
      PLANKS = item("oak_planks"),
      STICK = item("stick"),
      COAL = item("coal"),
      CHARCOAL = item("charcoal"),
      TORCH = item("torch"),
      MILK = item("milk_bucket"),
      BUCKET = item("bucket"),
      CAKE = item("cake"),
      SUGAR = item("sugar"),
      IRON = item("iron_ingot");

  private static Pattern pattern(
      int id, int priority, ResourceKey output, long amount, Map<ResourceKey, Long> inputs) {
    return pattern(id, priority, output, amount, inputs, Map.of());
  }

  private static Pattern pattern(
      int id,
      int priority,
      ResourceKey output,
      long amount,
      Map<ResourceKey, Long> inputs,
      Map<ResourceKey, Long> byproducts) {
    return new Pattern(
        new UUID(0, id), 1, Pattern.Type.CRAFTING, priority, inputs, output, amount, byproducts);
  }

  private static CraftingPlanner planner(Pattern... patterns) {
    var all = List.of(patterns);
    return new CraftingPlanner(key -> all);
  }

  @Test
  void recursivePlanOrdersChildrenFirstAndReusesSurplus() {
    var planks = pattern(1, 0, PLANKS, 4, Map.of(LOG, 1L));
    var sticks = pattern(2, 0, STICK, 4, Map.of(PLANKS, 2L));
    var torches = pattern(3, 0, TORCH, 4, Map.of(STICK, 1L, COAL, 1L));
    var plan = planner(planks, sticks, torches).plan(TORCH, 8, Map.of(LOG, 3L, COAL, 5L));
    assertEquals(CraftingPlanner.Status.OK, plan.status());
    assertEquals(
        List.of(
            new CraftingPlanner.Step(planks.id(), 1, PLANKS, 1),
            new CraftingPlanner.Step(sticks.id(), 1, STICK, 1),
            new CraftingPlanner.Step(torches.id(), 1, TORCH, 2)),
        plan.steps(),
        "One log makes 4 planks; 2 planks make 4 sticks; 2 sticks + 2 coal make 8 torches");
    assertEquals(Map.of(LOG, 1L, COAL, 2L), plan.consumed());
    assertTrue(plan.missing().isEmpty() && plan.cycles().isEmpty());
  }

  @Test
  void existingIntermediateStockIsUsedBeforeCraftingButTopLevelIsAlwaysCrafted() {
    var planks = pattern(1, 0, PLANKS, 4, Map.of(LOG, 1L));
    var sticks = pattern(2, 0, STICK, 4, Map.of(PLANKS, 2L));
    var plan = planner(planks, sticks).plan(STICK, 4, Map.of(PLANKS, 1L, LOG, 1L, STICK, 64L));
    assertEquals(
        List.of(
            new CraftingPlanner.Step(planks.id(), 1, PLANKS, 1),
            new CraftingPlanner.Step(sticks.id(), 1, STICK, 1)),
        plan.steps());
    assertEquals(Map.of(PLANKS, 1L, LOG, 1L), plan.consumed(), "Stock sticks are not consumed");
  }

  @Test
  void shortagesAreExplainedWithExactMissingUnits() {
    var planks = pattern(1, 0, PLANKS, 4, Map.of(LOG, 1L));
    var sticks = pattern(2, 0, STICK, 4, Map.of(PLANKS, 2L));
    var torches = pattern(3, 0, TORCH, 4, Map.of(STICK, 1L, COAL, 1L));
    var plan = planner(planks, sticks, torches).plan(TORCH, 16, Map.of(COAL, 1L));
    assertEquals(CraftingPlanner.Status.MISSING, plan.status());
    assertEquals(Map.of(LOG, 1L, COAL, 3L), plan.missing());
    assertEquals(Map.of(COAL, 1L), plan.consumed());
    assertEquals(CraftingPlanner.Status.MISSING, planner().plan(IRON, 1, Map.of()).status());
    assertEquals(Map.of(IRON, 1L), planner().plan(IRON, 1, Map.of()).missing());
  }

  @Test
  void cyclesAreReportedAndNeverExpandedForever() {
    var coalFromCharcoal = pattern(1, 0, COAL, 1, Map.of(CHARCOAL, 1L));
    var charcoalFromCoal = pattern(2, 0, CHARCOAL, 1, Map.of(COAL, 1L));
    var plan = planner(coalFromCharcoal, charcoalFromCoal).plan(COAL, 2, Map.of());
    assertEquals(CraftingPlanner.Status.MISSING, plan.status());
    assertEquals(List.of(List.of(COAL, CHARCOAL, COAL)), plan.cycles());
    assertEquals(Map.of(COAL, 2L), plan.missing());
    var stocked = planner(coalFromCharcoal, charcoalFromCoal).plan(COAL, 2, Map.of(CHARCOAL, 2L));
    assertEquals(CraftingPlanner.Status.OK, stocked.status(), "Stock breaks the cycle");
    assertTrue(stocked.cycles().isEmpty());
  }

  @Test
  void priorityChoosesAlternativeOnlyWhenHigherRankLacksInputsAndIdsBreakTies() {
    var fromCoal = pattern(5, 10, TORCH, 4, Map.of(STICK, 1L, COAL, 1L));
    var fromCharcoal = pattern(6, 0, TORCH, 4, Map.of(STICK, 1L, CHARCOAL, 1L));
    var both = Map.of(STICK, 1L, COAL, 1L, CHARCOAL, 1L);
    assertEquals(
        fromCoal.id(),
        planner(fromCoal, fromCharcoal).plan(TORCH, 4, both).steps().get(0).pattern());
    var noCoal = planner(fromCoal, fromCharcoal).plan(TORCH, 4, Map.of(STICK, 1L, CHARCOAL, 1L));
    assertEquals(CraftingPlanner.Status.OK, noCoal.status());
    assertEquals(fromCharcoal.id(), noCoal.steps().get(0).pattern());
    var neither = planner(fromCoal, fromCharcoal).plan(TORCH, 4, Map.of(STICK, 1L));
    assertEquals(fromCoal.id(), neither.steps().get(0).pattern(), "First kept to explain shortage");
    assertEquals(Map.of(COAL, 1L), neither.missing());
    var tieLow = pattern(7, 0, TORCH, 4, Map.of(STICK, 1L, COAL, 1L));
    var tieHigh = pattern(8, 0, TORCH, 4, Map.of(STICK, 1L, CHARCOAL, 1L));
    assertEquals(
        tieLow.id(), planner(tieHigh, tieLow).plan(TORCH, 4, both).steps().get(0).pattern());
  }

  @Test
  void byproductsServeLaterSiblingNeedsWithoutReservingMoreStock() {
    var milk = pattern(1, 0, MILK, 1, Map.of(BUCKET, 1L));
    var cake = pattern(2, 0, CAKE, 1, Map.of(MILK, 3L, SUGAR, 2L), Map.of(BUCKET, 3L));
    var single = planner(milk, cake).plan(CAKE, 1, Map.of(BUCKET, 3L, SUGAR, 2L));
    assertEquals(CraftingPlanner.Status.OK, single.status());
    assertEquals(Map.of(BUCKET, 3L, SUGAR, 2L), single.consumed());
    var ordered = new java.util.LinkedHashMap<ResourceKey, Long>();
    ordered.put(CAKE, 1L);
    ordered.put(MILK, 1L);
    var box = pattern(3, 0, item("cake_box"), 1, ordered);
    var plan = planner(milk, cake, box).plan(item("cake_box"), 1, Map.of(BUCKET, 3L, SUGAR, 2L));
    assertEquals(CraftingPlanner.Status.OK, plan.status());
    assertEquals(
        Map.of(BUCKET, 3L, SUGAR, 2L),
        plan.consumed(),
        "The fourth milk refills a bucket returned by the cake step");
    assertEquals(
        List.of(milk.id(), cake.id(), milk.id(), box.id()),
        plan.steps().stream().map(CraftingPlanner.Step::pattern).toList());
    var twoCakes = planner(milk, cake).plan(CAKE, 2, Map.of(BUCKET, 3L, SUGAR, 4L));
    assertEquals(
        Map.of(BUCKET, 3L),
        twoCakes.missing(),
        "Steps aggregate crafts, so returned buckets do not serve the same step");
  }

  @Test
  void depthNodeAndQuantityLimitsReturnLimitWithNoPartialPlan() {
    var chain = new ArrayList<Pattern>();
    var keys = new ArrayList<ResourceKey>();
    for (int i = 0; i <= CraftingPlanner.MAX_DEPTH + 1; i++) keys.add(item("chain_" + i));
    for (int i = 0; i <= CraftingPlanner.MAX_DEPTH; i++)
      chain.add(pattern(i, 0, keys.get(i), 1, Map.of(keys.get(i + 1), 1L)));
    var deep = new CraftingPlanner(key -> chain).plan(keys.get(0), 1, Map.of());
    assertEquals(CraftingPlanner.Status.LIMIT, deep.status());
    assertTrue(deep.steps().isEmpty() && deep.consumed().isEmpty() && deep.missing().isEmpty());
    var huge = pattern(1, 0, STICK, 1, Map.of(PLANKS, Long.MAX_VALUE));
    assertEquals(CraftingPlanner.Status.LIMIT, planner(huge).plan(STICK, 2, Map.of()).status());
    assertThrows(IllegalArgumentException.class, () -> planner().plan(STICK, 0, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> planner().plan(STICK, 1, Map.of(LOG, -1L)));
  }

  @Test
  void patternContractRejectsInvalidRecipes() {
    var id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> new Pattern(id, 0, Pattern.Type.CRAFTING, 0, Map.of(LOG, 1L), PLANKS, 4, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Pattern(id, 1, Pattern.Type.CRAFTING, 0, Map.of(), PLANKS, 4, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Pattern(id, 1, Pattern.Type.CRAFTING, 0, Map.of(LOG, 1L), PLANKS, 0, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pattern(id, 1, Pattern.Type.CRAFTING, 0, Map.of(PLANKS, 1L), PLANKS, 4, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pattern(id, 1, Pattern.Type.CRAFTING, 2_000, Map.of(LOG, 1L), PLANKS, 4, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Pattern(id, 1, Pattern.Type.CRAFTING, 0, Map.of(LOG, 0L), PLANKS, 4, Map.of()));
  }
}
