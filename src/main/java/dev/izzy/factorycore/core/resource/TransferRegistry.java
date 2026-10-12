package dev.izzy.factorycore.core.resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Canonical staging ownership; adapters expose record IDs, never mutable transfer instances. */
public final class TransferRegistry {
  public static final int MAX_RECORDS = 1024;
  public static final long MAX_BYTES = 4L * 1024 * 1024;

  public record Stored(UUID id, StagedTransfer.Snapshot transfer, TransferContext context) {
    public Stored {
      Objects.requireNonNull(id);
      Objects.requireNonNull(transfer);
      if (context != null) context.validate(transfer);
    }

    public Stored(UUID id, StagedTransfer.Snapshot transfer) {
      this(id, transfer, null);
    }
  }

  private static final class Entry {
    StagedTransfer.Snapshot saved;
    StagedTransfer live;
    boolean unavailable;
    final TransferContext context;

    Entry(StagedTransfer.Snapshot saved, TransferContext context) {
      this.saved = saved;
      this.context = context;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final Map<UUID, Entry> entries = new HashMap<>();
  private final Map<UUID, java.util.Set<UUID>> byBacking = new HashMap<>();
  private final Runnable changed;
  private long bytes;
  private boolean executing;

  public TransferRegistry(List<Stored> saved, Runnable changed) {
    this.changed = Objects.requireNonNull(changed);
    if (saved.size() > MAX_RECORDS) throw new IllegalArgumentException("Transfer record limit");
    for (var record : saved) {
      if (entries.putIfAbsent(record.id(), new Entry(record.transfer(), record.context())) != null)
        throw new IllegalArgumentException("Duplicate transfer owner");
      bytes =
          Math.addExact(
              bytes, footprint(record.transfer().key()) + (record.context() == null ? 0 : 1024));
      if (bytes > MAX_BYTES) throw new IllegalArgumentException("Transfer byte limit");
    }
  }

  /** Side-effect-free record admission; execution must still recheck before mutation. */
  public boolean canStart(UUID id, ResourceKey key) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(key);
    return !entries.containsKey(id)
        && entries.size() < MAX_RECORDS
        && footprint(key) + 1024 <= MAX_BYTES - bytes;
  }

  /**
   * Bounded retained-record check; uncertain operations must not release potentially used holds.
   */
  public boolean uncertain(UUID backing, UUID owner, ResourceKey key) {
    checkThread();
    Objects.requireNonNull(backing);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(key);
    for (var entry : entries.values()) {
      var move = entry.saved;
      if (move.owner().equals(owner)
          && move.key().equals(key)
          && (move.source().equals(backing) || move.destination().equals(backing))
          && (move.pending() != StagedTransfer.PendingCall.NONE
              || move.state() == StagedTransfer.State.QUARANTINED)) return true;
    }
    return false;
  }

  public StagedTransfer.Snapshot start(
      UUID id,
      UUID owner,
      ResourceKey key,
      ResourcePort source,
      ResourcePort destination,
      long requested,
      long capacity,
      BooleanSupplier authorized) {
    return start(id, owner, key, source, destination, requested, capacity, authorized, null);
  }

  public StagedTransfer.Snapshot start(
      UUID id,
      UUID owner,
      ResourceKey key,
      ResourcePort source,
      ResourcePort destination,
      long requested,
      long capacity,
      BooleanSupplier authorized,
      TransferContext context) {
    checkMutation();
    Objects.requireNonNull(id);
    Objects.requireNonNull(authorized);
    OperationResult.requireQuantity(requested);
    OperationResult.requireQuantity(capacity);
    var initial =
        new StagedTransfer.Snapshot(
            owner,
            key,
            source.backingId(),
            destination.backingId(),
            0,
            0,
            0,
            StagedTransfer.State.COMPLETE,
            OperationResult.Reason.OK,
            "",
            StagedTransfer.PendingCall.NONE,
            0);
    if (context != null) context.validate(initial);
    long size = footprint(key) + (context == null ? 0 : 1024);
    if (entries.containsKey(id))
      throw new IllegalStateException("Transfer ID already owns a record");
    if (entries.size() == MAX_RECORDS || size > MAX_BYTES - bytes)
      throw new IllegalStateException("Transfer admission limit");
    var entry = new Entry(initial, context);
    entries.put(id, entry);
    bytes += size;
    index(id, entry.saved);
    executing = true;
    try {
      changed.run();
      var live =
          StagedTransfer.start(
              owner,
              key,
              source,
              destination,
              requested,
              capacity,
              authorized,
              next -> record(entry, next));
      record(entry, live.snapshot());
      entry.live = entry.unavailable ? null : live;
      return entry.saved;
    } finally {
      if (entry.live == null) removeIndex(id, entry.saved);
      executing = false;
    }
  }

  /** Loading never binds world handlers. Bind once after current endpoint validation. */
  public StagedTransfer.Snapshot bind(
      UUID id, ResourcePort source, ResourcePort destination, BooleanSupplier authorized) {
    checkMutation();
    var entry = entry(id);
    if (entry.live != null) throw new IllegalStateException("Transfer already has a live owner");
    entry.unavailable = false;
    index(id, entry.saved);
    executing = true;
    try {
      var live =
          StagedTransfer.restore(
              entry.saved, source, destination, authorized, next -> record(entry, next));
      record(entry, live.snapshot());
      entry.live = entry.unavailable ? null : live;
      return entry.saved;
    } finally {
      if (entry.live == null) removeIndex(id, entry.saved);
      executing = false;
    }
  }

  public OperationResult deliver(UUID id) {
    return mutate(id, false);
  }

  public OperationResult cancel(UUID id) {
    return mutate(id, true);
  }

  private OperationResult mutate(UUID id, boolean returning) {
    checkMutation();
    var entry = entry(id);
    if (entry.saved.state() == StagedTransfer.State.QUARANTINED)
      return new OperationResult(0, OperationResult.Reason.UNCERTAIN);
    if (entry.live == null) return new OperationResult(0, OperationResult.Reason.OFFLINE);
    var live = entry.live;
    executing = true;
    try {
      var result = returning ? live.cancel() : live.deliver();
      record(entry, live.snapshot());
      return result;
    } finally {
      executing = false;
    }
  }

  /** Keep the canonical record while forgetting unloaded world capabilities. */
  public void unbind(UUID id) {
    checkMutation();
    var entry = entry(id);
    entry.live = null;
    entry.unavailable = true;
    removeIndex(id, entry.saved);
  }

  /** Owner-thread invalidation may happen inside a handler; it changes no owned quantities. */
  public int unbindBacking(UUID backing) {
    checkThread();
    var ids = byBacking.get(Objects.requireNonNull(backing));
    if (ids == null) return 0;
    var affected = List.copyOf(ids);
    for (var id : affected) {
      var entry = entry(id);
      entry.live = null;
      entry.unavailable = true;
      removeIndex(id, entry.saved);
    }
    return affected.size();
  }

  private void index(UUID id, StagedTransfer.Snapshot snapshot) {
    byBacking.computeIfAbsent(snapshot.source(), unused -> new java.util.HashSet<>()).add(id);
    byBacking.computeIfAbsent(snapshot.destination(), unused -> new java.util.HashSet<>()).add(id);
  }

  private void removeIndex(UUID id, StagedTransfer.Snapshot snapshot) {
    for (var backing : List.of(snapshot.source(), snapshot.destination())) {
      var ids = byBacking.get(backing);
      if (ids != null) {
        ids.remove(id);
        if (ids.isEmpty()) byBacking.remove(backing);
      }
    }
  }

  /** Only known finished records can release admission; uncertain evidence is never discarded. */
  public void retire(UUID id) {
    checkMutation();
    var entry = entry(id);
    if (entry.saved.state() != StagedTransfer.State.COMPLETE
        || entry.saved.pending() != StagedTransfer.PendingCall.NONE)
      throw new IllegalStateException("Transfer still owns staging or recovery evidence");
    executing = true;
    try {
      removeIndex(id, entry.saved);
      entries.remove(id);
      bytes -= footprint(entry.saved.key()) + (entry.context == null ? 0 : 1024);
      changed.run();
    } finally {
      executing = false;
    }
  }

  public boolean contains(UUID id) {
    checkThread();
    return entries.containsKey(Objects.requireNonNull(id));
  }

  public TransferContext context(UUID id) {
    checkThread();
    return entry(id).context;
  }

  public StagedTransfer.Snapshot get(UUID id) {
    checkThread();
    return entry(id).saved;
  }

  public List<Stored> snapshot() {
    checkThread();
    return entries.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> new Stored(e.getKey(), e.getValue().saved, e.getValue().context))
        .toList();
  }

  private void record(Entry entry, StagedTransfer.Snapshot next) {
    if (!entry.saved.owner().equals(next.owner())
        || !entry.saved.key().equals(next.key())
        || !entry.saved.source().equals(next.source())
        || !entry.saved.destination().equals(next.destination()))
      throw new IllegalStateException("Transfer record identity changed");
    entry.saved = next;
    changed.run();
  }

  private Entry entry(UUID id) {
    var entry = entries.get(Objects.requireNonNull(id));
    if (entry == null) throw new IllegalArgumentException("Unknown transfer record");
    return entry;
  }

  private static long footprint(ResourceKey key) {
    // Reserve the maximum diagnostic size before external extraction; later states need no
    // admission.
    return 2048L + key.registryId().length() * 3L + key.componentByteCount();
  }

  private void checkMutation() {
    checkThread();
    if (executing) throw new IllegalStateException("Reentrant transfer registry mutation");
  }

  private void checkThread() {
    if (thread != Thread.currentThread())
      throw new IllegalStateException("Transfer registry requires owner thread");
  }
}
