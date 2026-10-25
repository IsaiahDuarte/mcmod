package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanRowsTest {
  private static ResourceKey item(String id) {
    return new ResourceKey(ResourceKind.ITEM, "minecraft:" + id, new byte[0]);
  }

  private static final ResourceKey LOG = item("oak_log"),
      PLANKS = item("oak_planks"),
      STICK = item("stick"),
      COAL = item("coal"),
      CHARCOAL = item("charcoal"),
      TORCH = item("torch");

  private static Pattern pattern(
      int id, ResourceKey output, long amount, Map<ResourceKey, Long> in) {
    return new Pattern(new UUID(0, id), 1, Pattern.Type.CRAFTING, 0, in, output, amount, Map.of());
  }

  private static PlanRows.Summary summary(
      List<Pattern> patterns,
      ResourceKey request,
      long amount,
      Map<ResourceKey, Long> stock,
      int limit) {
    var plan = new CraftingPlanner(key -> patterns).plan(request, amount, stock);
    var units = new HashMap<UUID, Long>();
    for (var pattern : patterns) units.put(pattern.id(), pattern.outputAmount());
    return PlanRows.of(plan, id -> units.getOrDefault(id, 1L), limit);
  }

  @Test
  void twoLevelPlanShowsStoredAndCraftedUnits() {
    var patterns =
        List.of(pattern(1, PLANKS, 4, Map.of(LOG, 1L)), pattern(2, STICK, 4, Map.of(PLANKS, 2L)));
    // 8 sticks: 2 stick crafts need 4 planks; 2 planks are stored, 2 more come from 1 log craft.
    var result = summary(patterns, STICK, 8, Map.of(PLANKS, 2L, LOG, 3L), 64);
    assertEquals(
        List.of(
            new PlanRows.Row(PLANKS, 2, 4, 0),
            new PlanRows.Row(STICK, 0, 8, 0),
            new PlanRows.Row(LOG, 1, 0, 0)),
        result.rows());
    assertEquals(0, result.more());
  }

  @Test
  void shortagesComeFirst() {
    var patterns =
        List.of(
            pattern(1, PLANKS, 4, Map.of(LOG, 1L)),
            pattern(2, STICK, 4, Map.of(PLANKS, 2L)),
            pattern(3, TORCH, 4, Map.of(STICK, 1L, COAL, 1L)));
    var result = summary(patterns, TORCH, 8, Map.of(LOG, 3L, COAL, 1L), 64);
    assertEquals(new PlanRows.Row(COAL, 1, 0, 1), result.rows().getFirst());
    assertTrue(result.rows().stream().skip(1).allMatch(row -> row.missing() == 0));
  }

  @Test
  void cyclesAreExplainedAndTheirKeyIsMissing() {
    var patterns =
        List.of(
            pattern(1, COAL, 1, Map.of(CHARCOAL, 1L)), pattern(2, CHARCOAL, 1, Map.of(COAL, 1L)));
    var plan = new CraftingPlanner(key -> patterns).plan(COAL, 1, Map.of());
    assertEquals(List.of(List.of(COAL, CHARCOAL, COAL)), PlanRows.cycles(plan, 3));
    assertEquals(List.of(), PlanRows.cycles(plan, 0));
    var rows = PlanRows.of(plan, id -> 1, 64).rows();
    assertEquals(COAL, rows.getFirst().key());
    assertEquals(1, rows.getFirst().missing());
  }

  @Test
  void rowsAreBoundedAndTheRestCounted() {
    var patterns = new ArrayList<Pattern>();
    var inputs = new HashMap<ResourceKey, Long>();
    for (int index = 0; index < 9; index++) inputs.put(item("input_" + index), 1L);
    patterns.add(pattern(1, TORCH, 1, inputs));
    var result = summary(patterns, TORCH, 1, Map.of(), 4);
    assertEquals(4, result.rows().size());
    assertEquals(6, result.more(), "9 missing inputs + the torch, 4 shown");
    assertTrue(result.rows().stream().allMatch(row -> row.missing() == 1));
    assertThrows(IllegalArgumentException.class, () -> PlanRows.of(null, id -> 1, -1));
  }
}
