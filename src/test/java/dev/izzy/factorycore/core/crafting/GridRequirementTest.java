package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GridRequirementTest {
  private static final ResourceKey MILK =
      new ResourceKey(ResourceKind.ITEM, "minecraft:milk_bucket", new byte[0]);
  private static final ResourceKey SUGAR =
      new ResourceKey(ResourceKind.ITEM, "minecraft:sugar", new byte[0]);
  private static final ResourceKey NAMED_SUGAR =
      new ResourceKey(ResourceKind.ITEM, "minecraft:sugar", new byte[] {1, 2});

  private static List<ResourceKey> grid(ResourceKey... keys) {
    var cells = new ArrayList<ResourceKey>(Arrays.asList(keys));
    while (cells.size() < 9) cells.add(null);
    return cells;
  }

  @Test
  void countsExactKeysInFirstCellOrderAndTreatsComponentsAsDistinct() {
    var requirement = new GridRequirement(grid(MILK, MILK, MILK, SUGAR, null, NAMED_SUGAR));
    assertEquals(List.of(MILK, SUGAR, NAMED_SUGAR), List.copyOf(requirement.perCraft().keySet()));
    assertEquals(3L, requirement.perCraft().get(MILK));
    assertEquals(1L, requirement.perCraft().get(NAMED_SUGAR));
    assertFalse(requirement.empty());
    assertTrue(new GridRequirement(grid()).empty());
  }

  @Test
  void deficitReportsFirstMissingKeyForAllRequestedCrafts() {
    var requirement = new GridRequirement(grid(MILK, SUGAR, SUGAR));
    assertNull(requirement.deficit(2, Map.of(MILK, 2L, SUGAR, 4L)));
    assertEquals(
        new GridRequirement.Deficit(MILK, 1), requirement.deficit(3, Map.of(MILK, 2L, SUGAR, 6L)));
    assertEquals(
        new GridRequirement.Deficit(SUGAR, 128), requirement.deficit(64, Map.of(MILK, 64L)));
    assertThrows(IllegalArgumentException.class, () -> requirement.deficit(0, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> requirement.deficit(65, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> requirement.deficit(1, Map.of(MILK, -1L)));
  }

  @Test
  void rejectsWrongShapeOrNonItemKeys() {
    assertThrows(IllegalArgumentException.class, () -> new GridRequirement(List.of(MILK)));
    assertThrows(
        IllegalArgumentException.class, () -> new GridRequirement(grid(ResourceKey.ENERGY)));
    assertThrows(IllegalArgumentException.class, () -> new GridRequirement.Deficit(MILK, 0));
  }
}
