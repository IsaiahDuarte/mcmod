package dev.izzy.factorycore.platform.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class RegionReferenceTest {
  @Test
  void retainedOwnersFollowThousandsOfReplacementsWithoutLosingIndependentRegions() {
    var original = new RegionReference<>(0);
    var retained = new ArrayList<RegionReference<Integer>>();
    retained.add(original);
    var current = original;
    var independent = new RegionReference<>(-1);
    for (int generation = 1; generation <= 4096; generation++) {
      var next = new RegionReference<>(generation);
      current.replaceWith(next);
      retained.add(next);
      current = next;
      // Query the oldest handle every time: callers need not touch a position while it rebuilds.
      assertEquals(generation, original.current());
      assertEquals(-1, independent.current());
    }
    for (var reference : retained) assertEquals(4096, reference.current());
  }

  @Test
  void mergingEquallySizedGroupsKeepsTheRequestedOwnerForEveryRetainedReference() {
    var references = new ArrayList<RegionReference<Integer>>();
    for (int i = 0; i < 1024; i++) references.add(new RegionReference<>(i));
    for (int width = 1; width < references.size(); width *= 2)
      for (int start = 0; start < references.size(); start += width * 2)
        references.get(start).replaceWith(references.get(start + width));
    for (var reference : references) assertEquals(1023, reference.current());
    // Repeated notifications of an already merged owner do not reverse its current owner.
    references.get(0).replaceWith(references.get(1));
    assertEquals(1023, references.get(0).current());
  }
}
