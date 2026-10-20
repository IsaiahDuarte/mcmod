package dev.izzy.factorycore.core.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

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
    final UUID id;
    StagedTransfer.Snapshot saved;
    StagedTransfer live;
    boolean unavailable;
    final TransferContext context;

    Entry(UUID id, StagedTransfer.Snapshot saved, TransferContext context) {
      this.id = id;
      this.saved = saved;
      this.context = context;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final NavigableMap<UUID, Entry> entries = new TreeMap<>();
  private final Map<UUID, Set<UUID>> byBacking = new HashMap<>();
  private final Map<UUID, SortedSet<UUID>> unresolved = new HashMap<>();
  private final Map<UUID, SortedSet<UUID>> unresolvedDevices = new HashMap<>();
  private final Runnable changed;
  private long bytes;
  private boolean executing;

  public TransferRegistry(List<Stored> saved, Runnable changed) {
    this.changed = Objects.requireNonNull(changed);
    if (saved.size() > MAX_RECORDS) throw new IllegalArgumentException("Transfer record limit");
    for (var record : saved) {
      if (entries.putIfAbsent(
              record.id(), new Entry(record.id(), record.transfer(), record.context()))
          != null) throw new IllegalArgumentException("Duplicate transfer owner");
      bytes =
          Math.addExact(
              bytes, footprint(record.transfer().key()) + (record.context() == null ? 0 : 1024));
      if (bytes > MAX_BYTES) throw new IllegalArgumentException("Transfer byte limit");
      indexUnresolved(record.id(), record.transfer());
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
    var entry = new Entry(id, initial, context);
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
    byBacking.computeIfAbsent(snapshot.source(), unused -> new HashSet<>()).add(id);
    byBacking.computeIfAbsent(snapshot.destination(), unused -> new HashSet<>()).add(id);
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

  /** First canonical unresolved owner for a backing, including restored/unbound records. */
  public Stored unresolved(UUID backing) {
    checkThread();
    var ids = unresolved.get(Objects.requireNonNull(backing));
    if (ids == null) return null;
    var id = ids.first();
    var entry = entry(id);
    return new Stored(id, entry.saved, entry.context);
  }

  /** First unresolved operation mentioning a device; no inventory scan or resource authority. */
  public Stored unresolvedDevice(UUID device) {
    checkThread();
    var ids = unresolvedDevices.get(Objects.requireNonNull(device));
    if (ids == null) return null;
    var id = ids.first();
    var value = entry(id);
    return new Stored(id, value.saved, value.context);
  }

  /**
   * Explicit recovery preflight only: at most1024 metadata records, no provider calls or copies.
   */
  public boolean hasUnscopedUnresolved() {
    checkThread();
    for (var entry : entries.values()) {
      var value = entry.saved;
      if (entry.context == null
          && (value.state() != StagedTransfer.State.COMPLETE
              || value.pending() != StagedTransfer.PendingCall.NONE
              || value.staged() != 0)) return true;
    }
    return false;
  }

  /**
   * Port-recovery preflight (ADR 0047): true when a record without endpoint context has an
   * uncertain outcome (a pending call or quarantine) and names a backing that {@code canonical}
   * does not recognize, so it may involve any foreign provider. Known legacy staging never blocks,
   * because it settles only into canonical holders. At most1024 records; read-only, no provider
   * calls or copies.
   */
  public boolean hasUnscopedUncertain(Predicate<UUID> canonical) {
    checkThread();
    Objects.requireNonNull(canonical);
    for (var entry : entries.values()) {
      var value = entry.saved;
      if (entry.context == null
          && (value.state() == StagedTransfer.State.QUARANTINED
              || value.pending() != StagedTransfer.PendingCall.NONE)
          && (!canonical.test(value.source()) || !canonical.test(value.destination()))) return true;
    }
    return false;
  }

  public TransferContext context(UUID id) {
    checkThread();
    return entry(id).context;
  }

  public StagedTransfer.Snapshot get(UUID id) {
    checkThread();
    return entry(id).saved;
  }

  public record Page(List<Stored> records, UUID after) {
    public Page {
      records = List.copyOf(records);
    }
  }

  /** Bounded UUID-ordered metadata page, excluding after; no provider reads or authority grant. */
  public Page page(UUID after, int limit) {
    checkThread();
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("Transfer page limit");
    var values = (after == null ? entries : entries.tailMap(after, false)).entrySet().iterator();
    var records = new ArrayList<Stored>();
    UUID last = null;
    while (records.size() < limit && values.hasNext()) {
      var value = values.next();
      last = value.getKey();
      records.add(new Stored(last, value.getValue().saved, value.getValue().context));
    }
    return new Page(records, values.hasNext() ? last : null);
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
    removeUnresolved(entry.id, next);
    indexUnresolved(entry.id, next);
    changed.run();
  }

  private void indexUnresolved(UUID id, StagedTransfer.Snapshot snapshot) {
    if (snapshot.state() == StagedTransfer.State.COMPLETE
        && snapshot.pending() == StagedTransfer.PendingCall.NONE
        && snapshot.staged() == 0) return;
    for (var backing : List.of(snapshot.source(), snapshot.destination()))
      unresolved.computeIfAbsent(backing, ignored -> new TreeSet<>()).add(id);
    var context = entry(id).context;
    if (context != null)
      for (var device : List.of(context.source().device(), context.destination().device()))
        unresolvedDevices.computeIfAbsent(device, ignored -> new TreeSet<>()).add(id);
  }

  private void removeUnresolved(UUID id, StagedTransfer.Snapshot snapshot) {
    for (var backing : List.of(snapshot.source(), snapshot.destination())) {
      var ids = unresolved.get(backing);
      if (ids != null) {
        ids.remove(id);
        if (ids.isEmpty()) unresolved.remove(backing);
      }
    }
    var context = entry(id).context;
    if (context != null)
      for (var device : List.of(context.source().device(), context.destination().device())) {
        var ids = unresolvedDevices.get(device);
        if (ids != null) {
          ids.remove(id);
          if (ids.isEmpty()) unresolvedDevices.remove(device);
        }
      }
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
