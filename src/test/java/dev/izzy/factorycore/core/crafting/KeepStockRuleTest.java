package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeepStockRuleTest {
  private static final ResourceKey STICK =
      new ResourceKey(ResourceKind.ITEM, "minecraft:stick", new byte[0]);
  private static final NodeRegistry.Position ORIGIN =
      new NodeRegistry.Position("minecraft:overworld", 1);

  private static KeepStockRule rule(UUID principal, ResourceKey key, long low, long high) {
    return new KeepStockRule(
        UUID.randomUUID(), principal, new UUID(0, 1), new UUID(0, 1), ORIGIN, key, low, high);
  }

  @Test
  void deficitRefillsToHighOnlyBelowLow() {
    var rule = rule(new UUID(0, 5), STICK, 16, 64);
    assertEquals(64, rule.deficit(0));
    assertEquals(49, rule.deficit(15));
    assertEquals(0, rule.deficit(16));
    assertEquals(0, rule.deficit(100));
    assertThrows(IllegalArgumentException.class, () -> rule.deficit(-1));
    assertThrows(IllegalArgumentException.class, () -> rule(new UUID(0, 5), STICK, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> rule(new UUID(0, 5), STICK, 8, 4));
    assertThrows(
        IllegalArgumentException.class,
        () -> rule(new UUID(0, 5), STICK, 1, KeepStockRule.MAX_TARGET + 1));
  }

  @Test
  void registryReplacesSameTargetAndBoundsRulesPerPrincipal() {
    int[] dirty = {0};
    var jobs = new CraftingJobs(List.of(), List.of(), () -> dirty[0]++);
    var principal = new UUID(0, 5);
    assertNull(jobs.setRule(rule(principal, STICK, 8, 8)));
    var replacement = rule(principal, STICK, 32, 64);
    assertNull(jobs.setRule(replacement));
    assertEquals(
        List.of(replacement),
        jobs.rules(),
        "Same principal/segment/key replaces, never duplicates");
    for (int i = 1; i < CraftingJobs.MAX_RULES_PER_PRINCIPAL; i++)
      assertNull(
          jobs.setRule(
              rule(
                  principal,
                  new ResourceKey(ResourceKind.ITEM, "minecraft:item_" + i, new byte[0]),
                  1,
                  1)));
    assertEquals(
        "principal_rule_limit",
        jobs.setRule(
            rule(
                principal,
                new ResourceKey(ResourceKind.ITEM, "minecraft:extra", new byte[0]),
                1,
                1)));
    assertTrue(jobs.removeRule(replacement.id()));
    assertFalse(jobs.removeRule(replacement.id()));
    assertNull(jobs.ruleFor(principal, new UUID(0, 1), new UUID(0, 1), STICK));
    assertTrue(dirty[0] > 0);
    var duplicate = rule(principal, STICK, 1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new CraftingJobs(List.of(), List.of(duplicate, duplicate), () -> {}));
  }
}
