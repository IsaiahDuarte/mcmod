package dev.izzy.factorycore.core.storage;

import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded stable-ID selection after a persisted cursor, including absent cursor targets. */
public final class StableRotation {
  private final Thread thread = Thread.currentThread();
  private final TreeSet<UUID> candidates = new TreeSet<>();

  public boolean add(UUID backing) {
    checkThread();
    Objects.requireNonNull(backing);
    if (candidates.size() == PriorityOrder.MAX_CANDIDATES && !candidates.contains(backing))
      return false;
    candidates.add(backing);
    return true;
  }

  public UUID pollAfter(UUID last) {
    checkThread();
    if (candidates.isEmpty()) return null;
    UUID next = last == null ? candidates.first() : candidates.higher(last);
    if (next == null) next = candidates.first();
    candidates.remove(next);
    return next;
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Stable rotation requires its owning thread");
  }
}
