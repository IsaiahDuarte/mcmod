package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoverySeedsTest {
  private static final NodeRegistry.Position ANCHOR =
      new NodeRegistry.Position("minecraft:overworld", 1);
  private static final NodeRegistry.Position LAST =
      new NodeRegistry.Position("minecraft:overworld", 2);

  @Test
  void emptyTransitionsChargeOneVisitAndKeepUnfinishedPositions() {
    var seeds = new DiscoverySeeds(ANCHOR);
    int[] polls = {0};
    seeds.appendPositions(
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            polls[0]++;
            return false;
          }

          @Override
          public NodeRegistry.Position next() {
            throw new AssertionError();
          }
        });
    seeds.appendPositions(List.of(LAST).iterator());
    assertEquals(ANCHOR, seeds.next());
    assertEquals(1, polls[0], "One transition does not synchronously scan the next source");
    var replacement = new DiscoverySeeds(ANCHOR);
    replacement.append(seeds);
    assertEquals(LAST, replacement.next());
    assertEquals(ANCHOR, replacement.next());
    assertEquals(ANCHOR, replacement.next());
    assertFalse(replacement.hasNext());
    assertThrows(java.util.NoSuchElementException.class, replacement::next);
  }

  @Test
  void repeatedInterruptionsKeepEverySeedWithoutRecursiveTraversal() {
    var seeds = new DiscoverySeeds(ANCHOR);
    seeds.appendPositions(List.of(LAST).iterator());
    for (int i = 0; i < 512; i++) {
      var replacement = new DiscoverySeeds(ANCHOR);
      replacement.append(seeds);
      assertFalse(seeds.hasNext(), "Transferred producer cannot share the remaining cursor");
      seeds = replacement;
    }
    assertEquals(LAST, seeds.next());
    for (int i = 0; i < 513; i++) assertEquals(ANCHOR, seeds.next());
    assertFalse(seeds.hasNext());
    var completed = seeds;
    assertThrows(IllegalArgumentException.class, () -> completed.append(completed));
  }

  @Test
  void combinedQueueAdmissionPreservesBothOwnersWithoutPollingOrDiscardingSeeds() {
    var old = new DiscoverySeeds(LAST);
    int[] polls = {0};
    old.appendPositions(
        new Iterator<>() {
          private boolean pending = true;

          @Override
          public boolean hasNext() {
            polls[0]++;
            return pending;
          }

          @Override
          public NodeRegistry.Position next() {
            pending = false;
            return LAST;
          }
        });
    var target = new DiscoverySeeds(ANCHOR);
    for (int i = 0; i < DiscoverySeeds.MAX_SOURCES - 1; i++)
      target.appendPositions(Collections.emptyIterator());
    assertThrows(IllegalStateException.class, () -> target.append(old));
    assertEquals(0, polls[0], "Admission never executes a retained iterator");
    assertEquals(ANCHOR, target.next());
    target.append(old);
    assertFalse(old.hasNext());
    assertEquals(0, polls[0], "Successful transfer only splices metadata");
    for (int i = 0; i < DiscoverySeeds.MAX_SOURCES - 2; i++) assertEquals(ANCHOR, target.next());
    assertEquals(LAST, target.next());
    assertEquals(LAST, target.next(), "Original source retains its own completion anchor");
    assertEquals(ANCHOR, target.next(), "Adoption retains its charged completion visit");
    assertFalse(target.hasNext());
  }

  @Test
  void borrowedSourcesCannotFormCyclesOrShareOneMutableCursor() {
    var original = new DiscoverySeeds(ANCHOR);
    original.appendPositions(List.of(LAST).iterator());
    var target = new DiscoverySeeds(ANCHOR);
    target.append(original);
    assertThrows(IllegalStateException.class, () -> original.append(target));
    assertThrows(
        IllegalStateException.class, () -> original.appendPositions(List.of(ANCHOR).iterator()));
    var second = new DiscoverySeeds(ANCHOR);
    assertThrows(IllegalStateException.class, () -> second.append(original));
    assertFalse(second.hasNext());
    assertEquals(LAST, target.next(), "One borrowed cursor keeps ownership of the remaining seed");
  }

  @Test
  void sourceQueueRejectsBeforeDroppingExistingSeeds() {
    var seeds = new DiscoverySeeds(ANCHOR);
    seeds.appendPositions(List.of(LAST).iterator());
    for (int i = 1; i < DiscoverySeeds.MAX_SOURCES; i++)
      seeds.appendPositions(Collections.emptyIterator());
    assertThrows(
        IllegalStateException.class, () -> seeds.appendPositions(Collections.emptyIterator()));
    assertEquals(LAST, seeds.next());
    for (int i = 0; i < DiscoverySeeds.MAX_SOURCES; i++) assertEquals(ANCHOR, seeds.next());
    assertFalse(seeds.hasNext());
  }
}
