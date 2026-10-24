package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.*;
import java.util.function.Consumer;

/** Canonical deployment metadata, never an inventory, grant or transfer owner. */
public final class MachinePresets {
  public static final int MAX_DEPLOYMENTS = 256;
  public static final long MAX_BYTES = 16L * 1024 * 1024;

  public enum Face {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST
  }

  public enum Mode {
    PAUSED,
    IMPORT,
    EXPORT,
    STOCK
  }

  public record Authority(UUID principal, UUID network, UUID segment, long permissionGeneration) {
    public Authority {
      Objects.requireNonNull(principal);
      Objects.requireNonNull(network);
      Objects.requireNonNull(segment);
      if (permissionGeneration <= 0)
        throw new IllegalArgumentException("Invalid preset grant generation");
    }
  }

  public record Deployment(
      UUID node,
      long leaseGeneration,
      long revision,
      long configuration,
      Face face,
      Authority authority,
      Mode mode,
      ResourceFilter filter,
      long target) {
    public Deployment {
      Objects.requireNonNull(node);
      Objects.requireNonNull(face);
      Objects.requireNonNull(authority);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(filter);
      if (leaseGeneration <= 0 || revision <= 0 || configuration <= 0 || target < 0)
        throw new IllegalArgumentException("Invalid machine deployment");
    }

    public long bytes() {
      return 512 + filter.bytes();
    }
  }

  private final Thread thread = Thread.currentThread();
  private final NodeRegistry nodes;
  private final Runnable changed;
  private final NavigableMap<UUID, Deployment> deployments = new TreeMap<>();
  private long bytes;
  private final NavigableMap<UUID, Long> configurations = new TreeMap<>();

  /**
   * Active modes that {@link #invalidate} paused, for a one-click resume. Runtime only: it is not
   * saved, grants nothing, and a resume is an ordinary authorized deployment by the resuming actor.
   */
  private final Map<UUID, Mode> interrupted = new HashMap<>();

  private Consumer<UUID> activity = node -> {};

  public void activity(Consumer<UUID> listener) {
    checkThread();
    activity = Objects.requireNonNull(listener);
  }

  private static Map<UUID, Long> initialConfigurations(List<Deployment> restored) {
    var values = new HashMap<UUID, Long>();
    for (var value : restored) values.merge(value.node(), value.configuration(), Math::max);
    return values;
  }

  public MachinePresets(NodeRegistry nodes, List<Deployment> restored, Runnable changed) {
    this(nodes, restored, initialConfigurations(restored), changed);
  }

  public MachinePresets(
      NodeRegistry nodes, List<Deployment> restored, Map<UUID, Long> fences, Runnable changed) {
    this.nodes = Objects.requireNonNull(nodes);
    this.changed = Objects.requireNonNull(changed);
    if (restored.size() > MAX_DEPLOYMENTS)
      throw new IllegalArgumentException("Machine deployment limit");
    var records = new HashMap<UUID, NodeRegistry.Stored>();
    for (var record : nodes.snapshot()) records.put(record.id(), record);
    if (fences.size() > NodeRegistry.MAX_NODES)
      throw new IllegalArgumentException("Machine configuration fence limit");
    for (var fence : fences.entrySet()) {
      var node = records.get(fence.getKey());
      if (node == null
          || node.kind() != NodeRegistry.Kind.MACHINE_INTERFACE
          || fence.getValue() == null
          || fence.getValue() <= 0)
        throw new IllegalArgumentException("Invalid machine configuration fence");
      configurations.put(fence.getKey(), fence.getValue());
    }
    for (var deployment : restored) {
      var node = records.get(deployment.node());
      if (node == null
          || node.kind() != NodeRegistry.Kind.MACHINE_INTERFACE
          || deployment.leaseGeneration() > node.generation()
          || configurations.getOrDefault(deployment.node(), 0L) < deployment.configuration()
          || deployments.putIfAbsent(deployment.node(), deployment) != null)
        throw new IllegalArgumentException("Invalid/duplicate machine deployment owner");
      bytes = Math.addExact(bytes, deployment.bytes());
      if (bytes > MAX_BYTES)
        throw new IllegalArgumentException("Machine deployment metadata limit");
    }
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Machine presets require owner thread");
  }

  public Map<UUID, Long> configurations() {
    checkThread();
    return Map.copyOf(configurations);
  }

  public long configuration(UUID node) {
    checkThread();
    return configurations.getOrDefault(node, 0L);
  }

  public long nextConfiguration(NodeRegistry.Handle node, long observed) {
    checkThread();
    validate(node);
    if (observed <= 0) throw new IllegalArgumentException("Invalid observed configuration");
    return Math.incrementExact(Math.max(observed, configuration(node.id())));
  }

  public void observeConfiguration(NodeRegistry.Handle node, long observed) {
    checkThread();
    validate(node);
    if (observed <= 0) throw new IllegalArgumentException("Invalid observed configuration");
    if (observed > configuration(node.id())) {
      configurations.put(node.id(), observed);
      changed.run();
    }
  }

  public Deployment get(UUID node) {
    checkThread();
    return deployments.get(Objects.requireNonNull(node));
  }

  public List<Deployment> snapshot() {
    checkThread();
    return List.copyOf(deployments.values());
  }

  public long bytes() {
    checkThread();
    return bytes;
  }

  public Deployment prepare(
      NodeRegistry.Handle node,
      long configuration,
      Face face,
      Authority authority,
      Mode mode,
      ResourceFilter filter,
      long target) {
    checkThread();
    validate(node);
    var old = deployments.get(node.id());
    var next =
        new Deployment(
            node.id(),
            node.generation(),
            old == null ? 1 : Math.incrementExact(old.revision()),
            configuration,
            face,
            authority,
            mode,
            filter,
            target);
    admission(old, next);
    return next;
  }

  private long admission(Deployment old, Deployment next) {
    long updated = Math.addExact(bytes - (old == null ? 0 : old.bytes()), next.bytes());
    if (old == null && deployments.size() == MAX_DEPLOYMENTS || updated > MAX_BYTES)
      throw new IllegalStateException("Machine deployment metadata limit");
    return updated;
  }

  /**
   * Commit only an owner-thread prepared successor; admission is checked again before publication.
   */
  public void commit(NodeRegistry.Handle node, Deployment next) {
    checkThread();
    validate(node);
    var old = deployments.get(node.id());
    if (!next.node().equals(node.id())
        || next.leaseGeneration() != node.generation()
        || next.revision() != (old == null ? 1 : Math.incrementExact(old.revision())))
      throw new IllegalStateException("Stale deployment preparation");
    long updated = admission(old, next);
    configurations.merge(node.id(), next.configuration(), Math::max);
    deployments.put(node.id(), next);
    interrupted.remove(node.id());
    bytes = updated;
    changed.run();
    activity.accept(next.node());
  }

  public Deployment deploy(
      NodeRegistry.Handle node,
      long configuration,
      Face face,
      Authority authority,
      Mode mode,
      ResourceFilter filter,
      long target) {
    var next = prepare(node, configuration, face, authority, mode, filter, target);
    commit(node, next);
    return next;
  }

  private void validate(NodeRegistry.Handle node) {
    if (node.kind() != NodeRegistry.Kind.MACHINE_INTERFACE
        || nodes.validate(node) != NodeRegistry.Failure.OK
        || nodes.definition(node).position() == null)
      throw new IllegalStateException("Machine deployment needs current placed lease");
  }

  /**
   * Checks overflow before a physical edit; owner-thread mutation cannot interleave a second edit.
   */
  /** The active mode a configuration change paused, or null; see {@link #interrupted}. */
  public Mode interrupted(UUID node) {
    checkThread();
    var current = deployments.get(node);
    return current == null || current.mode() != Mode.PAUSED ? null : interrupted.get(node);
  }

  public void prepareInvalidation(UUID node) {
    checkThread();
    var old = deployments.get(node);
    if (old != null && old.mode() != Mode.PAUSED) Math.incrementExact(old.revision());
  }

  public void invalidate(UUID node) {
    checkThread();
    var old = deployments.get(node);
    if (old == null || old.mode() == Mode.PAUSED) return;
    var next =
        new Deployment(
            old.node(),
            old.leaseGeneration(),
            Math.incrementExact(old.revision()),
            old.configuration(),
            old.face(),
            old.authority(),
            Mode.PAUSED,
            old.filter(),
            old.target());
    deployments.put(node, next);
    interrupted.put(node, old.mode());
    changed.run();
    activity.accept(node);
  }

  /**
   * Metadata freshness only. Platform callers must additionally recheck actual current permissions.
   */
  public boolean matches(
      NodeRegistry.Handle node, long configuration, Face face, Authority current, long revision) {
    checkThread();
    var value = deployments.get(node.id());
    return value != null
        && value.mode() != Mode.PAUSED
        && value.revision() == revision
        && value.leaseGeneration() == node.generation()
        && value.configuration() == configuration
        && value.configuration() == configuration(node.id())
        && value.face() == face
        && value.authority().equals(current)
        && node.kind() == NodeRegistry.Kind.MACHINE_INTERFACE
        && nodes.validate(node) == NodeRegistry.Failure.OK
        && nodes.definition(node).position() != null;
  }

  public void remove(NodeRegistry.Handle node) {
    checkThread();
    validate(node);
    interrupted.remove(node.id());
    var old = deployments.remove(node.id());
    if (old != null) {
      bytes -= old.bytes();
      changed.run();
      activity.accept(node.id());
    }
  }
}
