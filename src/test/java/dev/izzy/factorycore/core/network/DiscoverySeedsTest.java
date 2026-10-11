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
  void repeatedInterruptionsRejectDepthBeforeLosingTheLastPosition() {
    var seeds = new DiscoverySeeds(ANCHOR);
    seeds.appendPositions(List.of(LAST).iterator());
    for (int i = 1; i < DiscoverySeeds.MAX_DEPTH; i++) {
      var replacement = new DiscoverySeeds(ANCHOR);
      replacement.append(seeds);
      seeds = replacement;
    }
    var rejected = new DiscoverySeeds(ANCHOR);
    var remaining = seeds;
    assertThrows(IllegalStateException.class, () -> rejected.append(remaining));
    assertFalse(rejected.hasNext(), "Rejected adoption changes no source ownership");
    assertEquals(LAST, seeds.next(), "Bounded retained stream preserves the unfinished position");
    assertThrows(IllegalArgumentException.class, () -> rejected.append(rejected));
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
