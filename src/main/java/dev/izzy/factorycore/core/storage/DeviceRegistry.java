package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.LedgerState;
import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Single-authority device stock. World adapters supply validated locations and permission checks.
 */
public final class DeviceRegistry {
  public static final int MAX_DEVICES = 1024;
  public static final long MAX_LEDGER_BYTES = 64L * 1024 * 1024;
  public static final long MAX_WORLD_ENTRIES = 65536;
  private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

  public enum Failure {
    OK,
    OCCUPIED,
    UNKNOWN,
    FOREIGN_WORLD,
    STALE,
    CONFLICT,
    OFFLINE,
    LIMIT
  }

  public record Handle(UUID world, UUID backing, long generation) {
    public Handle {
      Objects.requireNonNull(world);
      Objects.requireNonNull(backing);
      if (generation <= 0) throw new IllegalArgumentException("Invalid ownership generation");
    }
  }

  public record Location(String dimension, long position, int slot) {
    public Location {
      if (dimension == null
          || dimension.length() > 256
          || !DIMENSION.matcher(dimension).matches()
          || slot < 0
          || slot >= 64) throw new IllegalArgumentException("Invalid device location");
    }
  }

  public record Stored(LedgerState ledger, long generation, Location location, boolean conflict) {
    public Stored {
      Objects.requireNonNull(ledger);
      if (generation <= 0 || (conflict && location == null))
        throw new IllegalArgumentException("Invalid owner state");
    }
  }

  public record State(UUID world, List<Stored> devices) {
    public State {
      Objects.requireNonNull(world);
      devices = List.copyOf(devices);
      if (devices.size() > MAX_DEVICES) throw new IllegalArgumentException("Device limit exceeded");
    }
  }

  public record LeaseResult(Failure failure, Handle handle) {}

  private static final class Device {
    final ResourceLedger ledger;
    long generation;
    Location location;
    boolean conflict;

    Device(Stored stored) {
      ledger = ResourceLedger.restore(stored.ledger());
      generation = stored.generation();
      location = stored.location();
      conflict = stored.conflict();
    }
  }

  private final Thread thread = Thread.currentThread();
  private final UUID world;
  private final Runnable changed;
  private final Supplier<UUID> identifiers;
  private final Map<UUID, Device> devices = new HashMap<>();
  private final Map<Location, UUID> placements = new HashMap<>();
  private long bytes;
  private long entries;

  public DeviceRegistry(UUID world, Runnable changed, Supplier<UUID> identifiers) {
    this.world = Objects.requireNonNull(world);
    this.changed = Objects.requireNonNull(changed);
    this.identifiers = Objects.requireNonNull(identifiers);
  }

  public static DeviceRegistry restore(State state, Runnable changed, Supplier<UUID> identifiers) {
    var registry = new DeviceRegistry(state.world(), changed, identifiers);
    var unique = new HashSet<UUID>();
    for (var stored : state.devices()) {
      if (!unique.add(stored.ledger().id()))
        throw new IllegalArgumentException("Duplicate backing owner");
      LedgerStateCodec.encode(stored.ledger()); // Validate component byte/configuration bounds.
      var device = new Device(stored);
      if (device.location != null
          && registry.placements.putIfAbsent(device.location, device.ledger.id()) != null) {
        throw new IllegalArgumentException("Duplicate placed owner");
      }
      registry.bytes += device.ledger.serializedBytes();
      registry.entries += device.ledger.entryCount();
      if (registry.bytes > MAX_LEDGER_BYTES || registry.entries > MAX_WORLD_ENTRIES)
        throw new IllegalArgumentException("World ledger byte/entry limit");
      registry.devices.put(device.ledger.id(), device);
    }
    return registry;
  }

  public LeaseResult create(
      ResourceKind kind, long capacity, boolean infinite, int catalogLimit, int reservationLimit) {
    checkThread();
    var initial =
        new LedgerState(
            new UUID(0, 0),
            kind,
            capacity,
            infinite,
            catalogLimit,
            reservationLimit,
            Map.of(),
            List.of());
    LedgerStateCodec.encode(initial);
    if (devices.size() >= MAX_DEVICES || bytes + 82 > MAX_LEDGER_BYTES)
      return result(Failure.LIMIT, null);
    UUID id = Objects.requireNonNull(identifiers.get());
    if (devices.containsKey(id)) throw new IllegalStateException("Backing identifier collision");
    var state =
        new LedgerState(
            id,
            initial.kind(),
            initial.capacity(),
            initial.infinite(),
            initial.catalogLimit(),
            initial.reservationLimit(),
            Map.of(),
            List.of());
    var device = new Device(new Stored(state, 1, null, false));
    devices.put(id, device);
    bytes += 82;
    changed.run();
    return result(Failure.OK, device);
  }

  public LeaseResult attach(Handle handle, Location location) {
    checkThread();
    Objects.requireNonNull(location);
    Failure invalid = validate(handle);
    if (invalid != Failure.OK) return result(invalid, null);
    Device device = devices.get(handle.backing());
    if (device.location != null) {
      if (!device.location.equals(location)) {
        device.conflict = true;
        device.ledger.setLoaded(false);
        changed.run();
        return result(Failure.CONFLICT, device);
      }
      return result(Failure.OK, device); // Reload is idempotent and never activates routing.
    }
    if (device.generation == Long.MAX_VALUE) return result(Failure.LIMIT, device);
    if (placements.containsKey(location)) return result(Failure.OCCUPIED, device);
    device.location = location;
    placements.put(location, handle.backing());
    device.generation++;
    changed.run();
    return result(Failure.OK, device);
  }

  public LeaseResult detach(Handle handle, Location location) {
    checkThread();
    Failure invalid = placed(handle, location);
    if (invalid != Failure.OK) return result(invalid, null);
    Device device = devices.get(handle.backing());
    if (device.generation == Long.MAX_VALUE) return result(Failure.LIMIT, device);
    placements.remove(device.location);
    device.location = null;
    device.generation++;
    device.ledger.setLoaded(false);
    changed.run();
    return result(Failure.OK, device);
  }

  public Failure availability(Handle handle, Location location, boolean active) {
    checkThread();
    Failure invalid = placed(handle, location);
    if (invalid == Failure.OK) devices.get(handle.backing()).ledger.setLoaded(active);
    return invalid;
  }

  public ResourceLedger.Stock stock(Handle handle, ResourceKey key) {
    checkThread();
    Failure invalid = validate(handle);
    if (invalid != Failure.OK) throw new IllegalStateException("Invalid device handle: " + invalid);
    return devices.get(handle.backing()).ledger.stock(key);
  }

  public OperationResult insert(Handle handle, ResourceKey key, long amount) {
    checkThread();
    OperationResult.requireQuantity(amount);
    Device device = available(handle);
    if (device == null) return denied(handle);
    if (key.kind() != device.ledger.kind())
      return new OperationResult(0, OperationResult.Reason.WRONG_RESOURCE);
    long extra =
        amount > 0 && device.ledger.stock(key).total() == 0
            ? ResourceLedger.stockEntryBytes(key)
            : 0;
    if (!fits(device, extra)) return new OperationResult(0, OperationResult.Reason.TECHNICAL_LIMIT);
    return account(device, () -> device.ledger.insert(key, amount));
  }

  public OperationResult reserve(Handle handle, UUID owner, ResourceKey key, long amount) {
    checkThread();
    OperationResult.requireQuantity(amount);
    Device device = available(handle);
    if (device == null) return denied(handle);
    if (key.kind() != device.ledger.kind())
      return new OperationResult(0, OperationResult.Reason.WRONG_RESOURCE);
    long extra =
        amount > 0 && device.ledger.reservation(owner, key) == 0
            ? ResourceLedger.claimEntryBytes(key)
            : 0;
    if (!fits(device, extra)) return new OperationResult(0, OperationResult.Reason.TECHNICAL_LIMIT);
    return account(device, () -> device.ledger.reserve(owner, key, amount));
  }

  public OperationResult extract(Handle handle, ResourceKey key, long amount, UUID owner) {
    checkThread();
    OperationResult.requireQuantity(amount);
    Device device = available(handle);
    if (device == null) return denied(handle);
    return account(device, () -> device.ledger.extract(key, amount, owner));
  }

  public OperationResult release(Handle handle, UUID owner, ResourceKey key) {
    checkThread();
    if (validate(handle) != Failure.OK) return denied(handle);
    Device device = devices.get(handle.backing());
    return account(
        device,
        () -> new OperationResult(device.ledger.release(owner, key), OperationResult.Reason.OK));
  }

  public OperationResult resize(Handle handle, long capacity, boolean infinite) {
    checkThread();
    if (validate(handle) != Failure.OK) return denied(handle);
    var result = devices.get(handle.backing()).ledger.resize(capacity, infinite);
    if (result.reason() == OperationResult.Reason.OK) changed.run();
    return result;
  }

  public State snapshot() {
    checkThread();
    var snapshots = new ArrayList<Stored>(devices.size());
    for (var device : devices.values()) {
      snapshots.add(
          new Stored(
              device.ledger.persistentState(),
              device.generation,
              device.location,
              device.conflict));
    }
    snapshots.sort(java.util.Comparator.comparing(stored -> stored.ledger().id()));
    return new State(world, snapshots);
  }

  public Failure validate(Handle handle) {
    checkThread();
    if (!world.equals(handle.world())) return Failure.FOREIGN_WORLD;
    Device device = devices.get(handle.backing());
    if (device == null) return Failure.UNKNOWN;
    if (device.generation != handle.generation()) return Failure.STALE;
    return device.conflict ? Failure.CONFLICT : Failure.OK;
  }

  private Failure placed(Handle handle, Location location) {
    Failure invalid = validate(handle);
    if (invalid != Failure.OK) return invalid;
    return Objects.equals(devices.get(handle.backing()).location, location) && location != null
        ? Failure.OK
        : Failure.OFFLINE;
  }

  private Device available(Handle handle) {
    if (validate(handle) != Failure.OK) return null;
    Device device = devices.get(handle.backing());
    return device.location != null && device.ledger.loaded() ? device : null;
  }

  private OperationResult denied(Handle handle) {
    return new OperationResult(
        0,
        validate(handle) == Failure.OK
            ? OperationResult.Reason.OFFLINE
            : OperationResult.Reason.DENIED);
  }

  private boolean fits(Device device, long extra) {
    return device.ledger.serializedBytes() + extra <= LedgerStateCodec.MAX_BYTES
        && bytes + extra <= MAX_LEDGER_BYTES
        && entries + (extra > 0 ? 1 : 0) <= MAX_WORLD_ENTRIES;
  }

  private OperationResult account(Device device, Supplier<OperationResult> operation) {
    long before = device.ledger.serializedBytes();
    long beforeEntries = device.ledger.entryCount();
    var result = operation.get();
    bytes += device.ledger.serializedBytes() - before;
    entries += device.ledger.entryCount() - beforeEntries;
    if (result.amount() != 0) changed.run();
    return result;
  }

  private LeaseResult result(Failure failure, Device device) {
    return new LeaseResult(
        failure, device == null ? null : new Handle(world, device.ledger.id(), device.generation));
  }

  private void checkThread() {
    if (thread != Thread.currentThread())
      throw new IllegalStateException("Registry access requires its owning server thread");
  }
}
