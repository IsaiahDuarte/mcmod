package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Persisted distribution preferences, never resource ownership or authority. */
public final class RouteCursors {
  public static final int MAX_CURSORS = 1024;

  public record Scope(
      UUID network,
      UUID segment,
      UUID principal,
      UUID deployment,
      UUID fixed,
      ResourceKind kind,
      boolean toStorage) {
    public Scope {
      Objects.requireNonNull(network);
      Objects.requireNonNull(segment);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(deployment);
      Objects.requireNonNull(fixed);
      Objects.requireNonNull(kind);
    }
  }

  public record Cursor(Scope scope, UUID last) {
    public Cursor {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(last);
    }
  }

  private static final Comparator<Scope> ORDER =
      Comparator.comparing(Scope::network)
          .thenComparing(Scope::segment)
          .thenComparing(Scope::principal)
          .thenComparing(Scope::deployment)
          .thenComparing(Scope::fixed)
          .thenComparing(Scope::kind)
          .thenComparing(Scope::toStorage);

  private final Thread thread = Thread.currentThread();
  private final Map<Scope, UUID> cursors = new TreeMap<>(ORDER);
  private final Runnable changed;
  private boolean notifying;

  public RouteCursors(List<Cursor> restored, Runnable changed) {
    this.changed = Objects.requireNonNull(changed);
    if (restored.size() > MAX_CURSORS) throw new IllegalArgumentException("Route cursor limit");
    for (var cursor : restored)
      if (cursors.putIfAbsent(cursor.scope(), cursor.last()) != null)
        throw new IllegalArgumentException("Duplicate route cursor scope");
  }

  public UUID last(Scope scope) {
    checkThread();
    return cursors.get(Objects.requireNonNull(scope));
  }

  public boolean canTrack(Scope scope) {
    checkThread();
    return cursors.containsKey(Objects.requireNonNull(scope)) || cursors.size() < MAX_CURSORS;
  }

  /** Record selection before dispatch; unsuccessful/uncertain attempts still advance rotation. */
  public boolean advance(Scope scope, UUID backing) {
    checkMutation();
    Objects.requireNonNull(backing);
    if (!canTrack(scope)) return false;
    if (backing.equals(cursors.put(scope, backing))) return true;
    notifyChanged();
    return true;
  }

  /** Trusted deployment retirement only; callers must authenticate its lifecycle owner. */
  public boolean forget(Scope scope) {
    checkMutation();
    if (cursors.remove(Objects.requireNonNull(scope)) == null) return false;
    notifyChanged();
    return true;
  }

  public List<Cursor> snapshot() {
    checkThread();
    return cursors.entrySet().stream()
        .map(entry -> new Cursor(entry.getKey(), entry.getValue()))
        .toList();
  }

  private void notifyChanged() {
    notifying = true;
    try {
      changed.run();
    } finally {
      notifying = false;
    }
  }

  private void checkMutation() {
    checkThread();
    if (notifying) throw new IllegalStateException("Route cursor notification reentry");
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Route cursors require their owning thread");
  }
}
