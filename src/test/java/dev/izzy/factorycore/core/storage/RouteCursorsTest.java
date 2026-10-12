package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouteCursorsTest {
  private static RouteCursors.Scope scope(long deployment, ResourceKind kind, boolean importing) {
    return new RouteCursors.Scope(
        new UUID(0, 1),
        new UUID(0, 2),
        new UUID(0, 3),
        new UUID(0, deployment),
        new UUID(0, 4),
        kind,
        importing);
  }

  @Test
  void rotationWrapsStableIdsAndSkipsAbsentCursorWithoutDuplicates() {
    var rotation = new StableRotation();
    var first = new UUID(0, 10);
    var second = new UUID(0, 30);
    var last = new UUID(0, 50);
    rotation.add(last);
    rotation.add(first);
    rotation.add(second);
    rotation.add(second);
    assertEquals(second, rotation.pollAfter(new UUID(0, 20)));
    assertEquals(last, rotation.pollAfter(second));
    assertEquals(first, rotation.pollAfter(last));
    assertNull(rotation.pollAfter(first));
    rotation.add(first);
    rotation.add(last);
    assertEquals(first, rotation.pollAfter(null));
    assertEquals(last, rotation.pollAfter(first));
  }

  @Test
  void scopeIsolationRestoreAndDirtyChangesDoNotCreateResourceOwnership() {
    var changes = new AtomicInteger();
    var cursors = new RouteCursors(List.of(), changes::incrementAndGet);
    var scope = scope(10, ResourceKind.ITEM, true);
    var otherDirection = scope(10, ResourceKind.ITEM, false);
    var otherKind = scope(10, ResourceKind.FLUID, true);
    var id = new UUID(0, 20);
    assertNull(cursors.last(scope));
    assertTrue(cursors.advance(scope, id));
    assertTrue(cursors.advance(scope, id));
    assertEquals(1, changes.get());
    assertNull(cursors.last(otherDirection));
    assertNull(cursors.last(otherKind));
    var snapshot = cursors.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    var restored = new RouteCursors(snapshot, changes::incrementAndGet);
    assertEquals(id, restored.last(scope));
    assertEquals(1, changes.get());
    assertTrue(restored.forget(scope));
    assertFalse(restored.forget(scope));
    assertEquals(2, changes.get());
    assertEquals(id, cursors.last(scope));
  }

  @Test
  void cursorAndCandidateLimitsRejectBeforeMutationAndReclaimSlots() {
    var changes = new AtomicInteger();
    var cursors = new RouteCursors(List.of(), changes::incrementAndGet);
    for (int index = 0; index < RouteCursors.MAX_CURSORS; index++)
      assertTrue(cursors.advance(scope(index, ResourceKind.ITEM, true), new UUID(0, index)));
    var extra = scope(2000, ResourceKind.ITEM, true);
    assertFalse(cursors.canTrack(extra));
    assertFalse(cursors.advance(extra, new UUID(0, 42)));
    assertEquals(RouteCursors.MAX_CURSORS, changes.get());
    assertNull(cursors.last(extra));
    assertTrue(cursors.forget(scope(0, ResourceKind.ITEM, true)));
    assertTrue(cursors.advance(extra, new UUID(0, 42)));
    var rotation = new StableRotation();
    for (int index = 0; index < PriorityOrder.MAX_CANDIDATES; index++)
      assertTrue(rotation.add(new UUID(0, index)));
    assertTrue(rotation.add(new UUID(0, 1)));
    assertFalse(rotation.add(new UUID(1, 1)));
    rotation.pollAfter(null);
    assertTrue(rotation.add(new UUID(1, 1)));
  }

  @Test
  void duplicateRestoreAndNotificationReentryRejectWithOriginalCursorIntact() {
    var scope = scope(10, ResourceKind.ITEM, true);
    var cursor = new RouteCursors.Cursor(scope, new UUID(0, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new RouteCursors(List.of(cursor, cursor), () -> {}));
    var oversized = new ArrayList<RouteCursors.Cursor>();
    for (int index = 0; index <= RouteCursors.MAX_CURSORS; index++)
      oversized.add(
          new RouteCursors.Cursor(scope(index, ResourceKind.ITEM, true), new UUID(0, index)));
    assertThrows(IllegalArgumentException.class, () -> new RouteCursors(oversized, () -> {}));
    RouteCursors[] current = {null};
    current[0] =
        new RouteCursors(
            List.of(),
            () -> assertThrows(IllegalStateException.class, () -> current[0].forget(scope)));
    current[0].advance(scope, cursor.last());
    assertEquals(cursor.last(), current[0].last(scope));
  }
}
