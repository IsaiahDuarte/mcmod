package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.*;

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

  public MachinePresets(NodeRegistry nodes, List<Deployment> restored, Runnable changed) {
    this.nodes = Objects.requireNonNull(nodes);
    this.changed = Objects.requireNonNull(changed);
    if (restored.size() > MAX_DEPLOYMENTS)
      throw new IllegalArgumentException("Machine deployment limit");
    var records = new HashMap<UUID, NodeRegistry.Stored>();
    for (var record : nodes.snapshot()) records.put(record.id(), record);
    for (var deployment : restored) {
      var node = records.get(deployment.node());
      if (node == null
          || node.kind() != NodeRegistry.Kind.MACHINE_INTERFACE
          || deployment.leaseGeneration() > node.generation()
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

  public Deployment deploy(
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
    long updated = Math.addExact(bytes - (old == null ? 0 : old.bytes()), next.bytes());
    if (old == null && deployments.size() == MAX_DEPLOYMENTS || updated > MAX_BYTES)
      throw new IllegalStateException("Machine deployment metadata limit");
    deployments.put(node.id(), next);
    bytes = updated;
    changed.run();
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
    changed.run();
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
        && value.face() == face
        && value.authority().equals(current)
        && node.kind() == NodeRegistry.Kind.MACHINE_INTERFACE
        && nodes.validate(node) == NodeRegistry.Failure.OK
        && nodes.definition(node).position() != null;
  }

  public void remove(NodeRegistry.Handle node) {
    checkThread();
    validate(node);
    var old = deployments.remove(node.id());
    if (old != null) {
      bytes -= old.bytes();
      changed.run();
    }
  }
}
