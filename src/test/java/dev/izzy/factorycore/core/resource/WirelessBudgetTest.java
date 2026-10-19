package dev.izzy.factorycore.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WirelessBudgetTest {
  private static WirelessBudget.Link link(int value) {
    return new WirelessBudget.Link(
        new NodeRegistry.Position("minecraft:overworld", value * 2L),
        new NodeRegistry.Position("minecraft:overworld", value * 2L + 1));
  }

  @Test
  void bidirectionalPairsShareEachResourceCapWhileOtherPairsAndWiredPathsRemainIndependent() {
    var budget = new WirelessBudget();
    var pair = link(1);
    var reverse = new WirelessBudget.Link(pair.second(), pair.first());
    assertEquals(pair, reverse);
    for (var kind : ResourceKind.values()) {
      long cap = WirelessBudget.limit(kind);
      var first = budget.claim(List.of(pair), 10, kind, cap - 1);
      first.finish(cap - 1);
      var second = budget.claim(List.of(reverse), 10, kind, 100);
      assertEquals(1, second.amount());
      assertEquals(OperationResult.Reason.RATE_LIMIT, second.reason());
      second.finish(1);
      assertEquals(0, budget.available(List.of(pair), 10, kind, Long.MAX_VALUE));
      assertEquals(cap, budget.available(List.of(link(2)), 10, kind, Long.MAX_VALUE));
      assertEquals(Long.MAX_VALUE, budget.available(List.of(), 10, kind, Long.MAX_VALUE));
      assertEquals(cap, budget.available(List.of(pair), 11, kind, Long.MAX_VALUE));
    }
  }

  @Test
  void nestedPathsUseTightestHopDeduplicateAndReleaseOnlyKnownPartialAcceptance() {
    var budget = new WirelessBudget();
    var parent = link(1);
    var child = link(2);
    budget.claim(List.of(parent), 2, ResourceKind.ITEM, 200).finish(200);
    var both = budget.claim(List.of(parent, child, parent), 2, ResourceKind.ITEM, 100);
    assertEquals(56, both.amount());
    both.finish(16);
    assertEquals(40, budget.available(List.of(parent), 2, ResourceKind.ITEM, 256));
    assertEquals(240, budget.available(List.of(child), 2, ResourceKind.ITEM, 256));
    // An uncompleted foreign call is uncertain and retains its entire possible mutation.
    budget.claim(List.of(child), 2, ResourceKind.ITEM, 100);
    assertEquals(140, budget.available(List.of(child), 2, ResourceKind.ITEM, 256));
    assertThrows(IllegalArgumentException.class, () -> both.finish(0));
    var pending = budget.claim(List.of(parent), 2, ResourceKind.ITEM, 40);
    assertThrows(IllegalArgumentException.class, () -> pending.finish(41));
    // A late old-tick completion must never refund this tick's independent accounting.
    budget.claim(List.of(parent), 3, ResourceKind.ITEM, 250).finish(250);
    pending.finish(0);
    assertEquals(6, budget.available(List.of(parent), 3, ResourceKind.ITEM, 256));
  }

  @Test
  void generatedMixedPathsPartialAndUncertainCallsConservePerHopCaps() {
    var random = new Random(0x571E1E55L);
    var budget = new WirelessBudget();
    var links = List.of(link(0), link(1), link(2), link(3));
    var spent = new long[4][3];
    long tick = 1;
    for (int operation = 0; operation < 2000; operation++) {
      if (random.nextInt(15) == 0) {
        tick++;
        spent = new long[4][3];
      }
      var kind = ResourceKind.values()[random.nextInt(3)];
      int first = random.nextInt(4), second = random.nextInt(4);
      long requested = random.nextInt(10000);
      long expected =
          Math.min(
              requested,
              Math.min(
                  WirelessBudget.limit(kind) - spent[first][kind.ordinal()],
                  WirelessBudget.limit(kind) - spent[second][kind.ordinal()]));
      var permit =
          budget.claim(List.of(links.get(first), links.get(second)), tick, kind, requested);
      assertEquals(expected, permit.amount(), "Replay seed0x571E1E55 operation" + operation);
      long actual = random.nextLong(expected + 1);
      boolean uncertain = random.nextInt(5) == 0;
      if (!uncertain) permit.finish(actual);
      long debit = uncertain ? expected : actual;
      spent[first][kind.ordinal()] += debit;
      if (first != second) spent[second][kind.ordinal()] += debit;
      for (int index = 0; index < links.size(); index++)
        assertEquals(
            WirelessBudget.limit(kind) - spent[index][kind.ordinal()],
            budget.available(List.of(links.get(index)), tick, kind, Long.MAX_VALUE));
    }
  }

  @Test
  void limitsAndThreadOwnershipFailClosedAndExpiredMetadataCanBeReclaimed()
      throws InterruptedException {
    var budget = new WirelessBudget();
    for (int index = 0; index < WirelessBudget.MAX_LINKS; index++)
      budget.claim(List.of(link(index)), 1, ResourceKind.ITEM, 1).finish(1);
    assertEquals(
        OperationResult.Reason.TECHNICAL_LIMIT,
        budget.claim(List.of(link(WirelessBudget.MAX_LINKS)), 1, ResourceKind.ITEM, 1).reason());
    assertEquals(
        1, budget.claim(List.of(link(WirelessBudget.MAX_LINKS)), 2, ResourceKind.ITEM, 1).amount());
    assertThrows(
        IllegalArgumentException.class,
        () -> budget.claim(List.of(link(0)), 2, ResourceKind.ITEM, -1));
    var oversized = new ArrayList<WirelessBudget.Link>();
    for (int index = 0; index <= WirelessBudget.MAX_PATH; index++) oversized.add(link(index));
    assertThrows(
        IllegalArgumentException.class, () -> budget.claim(oversized, 2, ResourceKind.ITEM, 1));
    var failure = new AtomicReference<Throwable>();
    var thread =
        new Thread(
            () -> {
              try {
                budget.available(List.of(link(0)), 2, ResourceKind.ITEM, 1);
              } catch (RuntimeException rejected) {
                failure.set(rejected);
              }
            });
    thread.start();
    thread.join();
    assertInstanceOf(IllegalStateException.class, failure.get());
  }
}
