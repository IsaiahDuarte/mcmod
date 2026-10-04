package dev.izzy.factorycore.core.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical structural leases, independent of stock and permission generations. */
public final class NodeRegistry {
  public static final int MAX_NODES = 4096;
  private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

  public enum Kind {
    CONTROLLER,
    GATEWAY
  }

  public enum Failure {
    OK,
    UNKNOWN,
    FOREIGN_WORLD,
    STALE,
    CONFLICT,
    OCCUPIED,
    LIMIT
  }

  public record Position(String dimension, long block) {
    public Position {
      if (dimension == null || dimension.length() > 256 || !DIMENSION.matcher(dimension).matches())
        throw new IllegalArgumentException("Invalid structural position");
    }
  }

  public record Handle(UUID world, UUID id, long generation, Kind kind) {
    public Handle {
      Objects.requireNonNull(world);
      Objects.requireNonNull(id);
      Objects.requireNonNull(kind);
      if (generation <= 0) throw new IllegalArgumentException("Invalid structural generation");
    }
  }

  public record Stored(
      UUID id,
      Kind kind,
      long generation,
      Position position,
      boolean conflict,
      GatewayPolicy policy) {
    public Stored {
      Objects.requireNonNull(id);
      Objects.requireNonNull(kind);
      if (generation <= 0
          || (conflict && position == null)
          || (kind == Kind.GATEWAY) != (policy != null))
        throw new IllegalArgumentException("Invalid structural owner");
    }
  }

  public record Result(Failure failure, Handle handle) {}

  private final Thread thread = Thread.currentThread();
  private final UUID world;
  private final Runnable changed;
  private final Map<UUID, Stored> nodes = new HashMap<>();
  private final Map<Position, UUID> positions = new HashMap<>();

  public NodeRegistry(UUID world, List<Stored> records, Runnable changed) {
    this.world = Objects.requireNonNull(world);
    this.changed = Objects.requireNonNull(changed);
    if (records.size() > MAX_NODES) throw new IllegalArgumentException("Structural owner limit");
    for (var record : records) {
      if (nodes.putIfAbsent(record.id(), record) != null)
        throw new IllegalArgumentException("Duplicate structural owner");
      if (record.position() != null
          && positions.putIfAbsent(record.position(), record.id()) != null)
        throw new IllegalArgumentException("Duplicate structural position");
    }
  }

  public Failure admission(Position position) {
    checkThread();
    Objects.requireNonNull(position);
    if (positions.containsKey(position)) return Failure.OCCUPIED;
    return nodes.size() == MAX_NODES ? Failure.LIMIT : Failure.OK;
  }

  public boolean contains(UUID id) {
    checkThread();
    return nodes.containsKey(Objects.requireNonNull(id));
  }

  public Result create(UUID id, Kind kind, Position position) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    if (nodes.containsKey(id)) throw new IllegalArgumentException("Structural identity collision");
    Failure failure = admission(position);
    if (failure != Failure.OK) return new Result(failure, null);
    var record =
        new Stored(
            id, kind, 1, position, false, kind == Kind.GATEWAY ? GatewayPolicy.open() : null);
    put(record);
    changed.run();
    return new Result(Failure.OK, handle(record));
  }

  public Failure validate(Handle handle) {
    checkThread();
    Objects.requireNonNull(handle);
    if (!world.equals(handle.world())) return Failure.FOREIGN_WORLD;
    var node = nodes.get(handle.id());
    if (node == null) return Failure.UNKNOWN;
    if (node.generation() != handle.generation() || node.kind() != handle.kind())
      return Failure.STALE;
    return node.conflict() ? Failure.CONFLICT : Failure.OK;
  }

  public Stored definition(Handle handle) {
    Failure failure = validate(handle);
    if (failure != Failure.OK)
      throw new IllegalStateException("Invalid structural lease: " + failure);
    return nodes.get(handle.id());
  }

  public Result attach(Handle handle, Position position) {
    Failure failure = validate(handle);
    if (failure != Failure.OK) return new Result(failure, handle);
    Objects.requireNonNull(position);
    var node = nodes.get(handle.id());
    if (position.equals(node.position())) return new Result(Failure.OK, handle);
    if (node.position() != null) {
      put(
          new Stored(
              node.id(), node.kind(), node.generation(), node.position(), true, node.policy()));
      changed.run();
      return new Result(Failure.CONFLICT, handle);
    }
    if (positions.containsKey(position)) return new Result(Failure.OCCUPIED, handle);
    long generation = Math.incrementExact(node.generation());
    var placed = new Stored(node.id(), node.kind(), generation, position, false, node.policy());
    put(placed);
    changed.run();
    return new Result(Failure.OK, handle(placed));
  }

  public Result detach(Handle handle, Position position) {
    Failure failure = validate(handle);
    if (failure != Failure.OK) return new Result(failure, handle);
    var node = nodes.get(handle.id());
    if (!Objects.requireNonNull(position).equals(node.position()))
      return new Result(Failure.STALE, handle);
    long generation = Math.incrementExact(node.generation());
    var portable = new Stored(node.id(), node.kind(), generation, null, false, node.policy());
    positions.remove(position);
    put(portable);
    changed.run();
    return new Result(Failure.OK, handle(portable));
  }

  public List<Stored> snapshot() {
    checkThread();
    return nodes.values().stream().sorted(java.util.Comparator.comparing(Stored::id)).toList();
  }

  private Handle handle(Stored node) {
    return new Handle(world, node.id(), node.generation(), node.kind());
  }

  private void put(Stored node) {
    nodes.put(node.id(), node);
    if (node.position() != null) positions.put(node.position(), node.id());
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Structural leases require owner thread");
  }
}
