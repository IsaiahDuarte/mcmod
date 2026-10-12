package dev.izzy.factorycore.core.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded global priority order collected across pages; identity aliases never add candidates. */
public final class PriorityOrder {
  public static final int MAX_CANDIDATES = 5120;

  private record Candidate(UUID backing, int priority) implements Comparable<Candidate> {
    @Override
    public int compareTo(Candidate other) {
      int order = Integer.compare(other.priority, priority);
      return order == 0 ? backing.compareTo(other.backing) : order;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final Map<UUID, Candidate> identities = new HashMap<>();
  private final TreeSet<Candidate> ordered = new TreeSet<>();

  public boolean add(UUID backing, int priority) {
    checkThread();
    Objects.requireNonNull(backing);
    var old = identities.get(backing);
    if (old == null && identities.size() == MAX_CANDIDATES) return false;
    if (old != null) ordered.remove(old);
    var candidate = new Candidate(backing, priority);
    identities.put(backing, candidate);
    ordered.add(candidate);
    return true;
  }

  public UUID poll() {
    checkThread();
    var candidate = ordered.pollFirst();
    if (candidate == null) return null;
    identities.remove(candidate.backing());
    return candidate.backing();
  }

  public int size() {
    checkThread();
    return identities.size();
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Priority order requires its owning thread");
  }
}
