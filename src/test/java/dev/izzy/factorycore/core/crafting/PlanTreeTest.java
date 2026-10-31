package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanTreeTest {
  private static ResourceKey item(String id) {
    return new ResourceKey(ResourceKind.ITEM, "minecraft:" + id, new byte[0]);
  }

  private static final ResourceKey LOG = item("oak_log"),
      PLANKS = item("oak_planks"),
      STICK = item("stick"),
      COAL = item("coal"),
      TORCH = item("torch");

  private static Pattern pattern(
      int id, ResourceKey output, long amount, Map<ResourceKey, Long> in) {
    return new Pattern(new UUID(0, id), 1, Pattern.Type.CRAFTING, 0, in, output, amount, Map.of());
  }

  private static Map<ResourceKey, Long> ordered(Object... pairs) {
    var map = new LinkedHashMap<ResourceKey, Long>();
    for (int i = 0; i < pairs.length; i += 2) map.put((ResourceKey) pairs[i], (Long) pairs[i + 1]);
    return map;
  }

  private static String line(PlanTree.Node node) {
    return "  ".repeat(node.depth())
        + node.key().registryId().substring(10)
        + " "
        + node.amount()
        + " "
        + node.kind();
  }

  @Test
  void twoLevelsDepthFirstWithScaledAmounts() {
    var planks = pattern(1, PLANKS, 4, ordered(LOG, 1L));
    var sticks = pattern(2, STICK, 4, ordered(PLANKS, 2L));
    var torches = pattern(3, TORCH, 4, ordered(STICK, 1L, COAL, 1L));
    var all = List.of(planks, sticks, torches);
    var plan = new CraftingPlanner(key -> all).plan(TORCH, 8, Map.of(LOG, 3L));
    var byId = Map.of(planks.id(), planks, sticks.id(), sticks, torches.id(), torches);
    var tree = PlanTree.of(plan, id -> byId.get(id).inputs(), 64);
    assertFalse(tree.truncated());
    assertEquals(
        List.of(
            "torch 8 CRAFTED",
            "  stick 2 CRAFTED",
            "    oak_planks 2 CRAFTED",
            "      oak_log 1 STORED",
            "  coal 2 MISSING"),
        tree.nodes().stream().map(PlanTreeTest::line).toList());
  }

  @Test
  void theLimitTruncatesAndAChangedPatternIsALeaf() {
    var planks = pattern(1, PLANKS, 4, ordered(LOG, 1L));
    var sticks = pattern(2, STICK, 4, ordered(PLANKS, 2L));
    var all = List.of(planks, sticks);
    var plan = new CraftingPlanner(key -> all).plan(STICK, 4, Map.of(LOG, 1L));
    var byId = Map.of(planks.id(), planks, sticks.id(), sticks);
    var cut = PlanTree.of(plan, id -> byId.get(id).inputs(), 2);
    assertTrue(cut.truncated());
    assertEquals(2, cut.nodes().size());
    var leaf = PlanTree.of(plan, id -> null, 64);
    assertEquals(1, leaf.nodes().size(), "A pattern that changed is not expanded");
    assertEquals(PlanTree.Kind.CRAFTED, leaf.nodes().getFirst().kind());
  }

  @Test
  void aLoopIsShownOnceAndNotExpanded() {
    var a = item("a");
    var b = item("b");
    var makeA = pattern(1, a, 1, ordered(b, 1L));
    var makeB = pattern(2, b, 1, ordered(a, 1L));
    var step = new CraftingPlanner.Step(makeA.id(), 1, a, 1);
    var stepB = new CraftingPlanner.Step(makeB.id(), 1, b, 1);
    var plan =
        new CraftingPlanner.Plan(
            CraftingPlanner.Status.OK,
            a,
            1,
            List.of(stepB, step),
            Map.of(),
            Map.of(),
            List.of(),
            2);
    var byId = Map.of(makeA.id(), makeA, makeB.id(), makeB);
    var tree = PlanTree.of(plan, id -> byId.get(id).inputs(), 64);
    assertEquals(
        List.of("a 1 CRAFTED", "  b 1 CRAFTED", "    a 1 LOOP"),
        tree.nodes().stream().map(PlanTreeTest::line).toList());
  }
}
