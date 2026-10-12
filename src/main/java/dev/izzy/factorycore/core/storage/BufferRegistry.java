package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Single canonical slot owner per structural buffer lease; restored inventories are unbound. */
public final class BufferRegistry {
  public static final int MAX_BUFFERS = 256;
  public static final long MAX_BYTES = 16L * 1024 * 1024;

  public enum Mode {
    PAUSED,
    IMPORT
  }

  /** Server-owned deployment identity; authority must be rechecked before every queued mutation. */
  public record Deployment(
      UUID backing,
      long revision,
      long leaseGeneration,
      UUID principal,
      UUID network,
      UUID segment,
      Mode mode) {
    public Deployment {
      Objects.requireNonNull(backing);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(network);
      Objects.requireNonNull(segment);
      Objects.requireNonNull(mode);
      if (revision <= 0 || leaseGeneration <= 0)
        throw new IllegalArgumentException("Invalid buffer deployment generation");
    }
  }

  private record Binding(
      NodeRegistry.Handle handle, NodeRegistry.Position position, Object owner) {}

  private final Thread thread = Thread.currentThread();
  private final NodeRegistry nodes;
  private final Runnable changed;
  private final Map<UUID, ItemBuffer> buffers = new TreeMap<>();
  private final Map<UUID, Long> charges = new HashMap<>();
  private final Map<UUID, Binding> bindings = new HashMap<>();
  private final Map<UUID, Deployment> deployments = new TreeMap<>();
  private ToIntFunction<ResourceKey> stackLimit =
      key -> {
        throw new IllegalStateException("Buffer stack resolver is not installed");
      };
  private long bytes;

  public BufferRegistry(NodeRegistry nodes, List<ItemBuffer.State> restored, Runnable changed) {
    this(nodes, restored, List.of(), changed);
  }

  public BufferRegistry(
      NodeRegistry nodes,
      List<ItemBuffer.State> restored,
      List<Deployment> restoredDeployments,
      Runnable changed) {
    this.nodes = Objects.requireNonNull(nodes);
    this.changed = Objects.requireNonNull(changed);
    if (restored.size() > MAX_BUFFERS) throw new IllegalArgumentException("Buffer count limit");
    var leases = new HashMap<UUID, NodeRegistry.Stored>();
    for (var node : nodes.snapshot())
      if (node.kind() == NodeRegistry.Kind.BUFFERED_INTERFACE) leases.put(node.id(), node);
    for (var state : restored) {
      if (leases.remove(state.backing()) == null || buffers.containsKey(state.backing()))
        throw new IllegalArgumentException("Buffer has no unique structural owner");
      long charge = charge(state);
      if (bytes > MAX_BYTES - charge)
        throw new IllegalArgumentException("Buffer metadata byte limit");
      install(state, charge);
    }
    if (!leases.isEmpty())
      throw new IllegalArgumentException("Buffer lease has no owned inventory");
    var generations = new HashMap<UUID, Long>();
    for (var node : nodes.snapshot()) generations.put(node.id(), node.generation());
    for (var deployment : restoredDeployments)
      if (!buffers.containsKey(deployment.backing())
          || generations.getOrDefault(deployment.backing(), 0L) < deployment.leaseGeneration()
          || deployments.putIfAbsent(deployment.backing(), deployment) != null)
        throw new IllegalArgumentException("Deployment has no unique buffer owner");
  }

  public void stackLimit(ToIntFunction<ResourceKey> resolver) {
    checkThread();
    stackLimit = Objects.requireNonNull(resolver);
  }

  public boolean canCreate() {
    checkThread();
    return buffers.size() < MAX_BUFFERS && bytes <= MAX_BYTES - 256;
  }

  /** Caller preflights registry and structural placement together before creating a lease. */
  public void create(NodeRegistry.Handle handle) {
    checkThread();
    var node = nodes.definition(handle);
    if (node.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE || buffers.containsKey(handle.id()))
      throw new IllegalArgumentException("Invalid new buffer owner");
    if (!canCreate()) throw new IllegalStateException("Buffer admission limit");
    install(ItemBuffer.empty(handle.id()), 256);
    changed.run();
  }

  public boolean bind(NodeRegistry.Handle handle, NodeRegistry.Position position, Object owner) {
    checkThread();
    Objects.requireNonNull(position);
    Objects.requireNonNull(owner);
    if (!valid(handle, position)) return false;
    var existing = bindings.get(handle.id());
    if (existing != null && existing.owner() != owner) return false;
    bindings.put(handle.id(), new Binding(handle, position, owner));
    return true;
  }

  public void unbind(NodeRegistry.Handle handle, Object owner) {
    checkThread();
    var binding = bindings.get(handle.id());
    if (binding != null && binding.handle().equals(handle) && binding.owner() == owner)
      bindings.remove(handle.id());
  }

  public boolean usable(NodeRegistry.Handle handle, Object owner) {
    checkThread();
    var binding = bindings.get(handle.id());
    return binding != null
        && binding.handle().equals(handle)
        && binding.owner() == owner
        && valid(handle, binding.position());
  }

  /** Binding ownership only, including a newly conflicted lease awaiting physical teardown. */
  public boolean boundTo(NodeRegistry.Handle handle, Object owner) {
    checkThread();
    var binding = bindings.get(handle.id());
    return binding != null && binding.handle().equals(handle) && binding.owner() == owner;
  }

  public ItemBuffer inventory(NodeRegistry.Handle handle, Object owner) {
    if (!usable(handle, owner))
      throw new IllegalStateException("Buffer lease is not currently bound");
    return buffers.get(handle.id());
  }

  public List<ItemBuffer.State> snapshot() {
    checkThread();
    return buffers.values().stream().map(ItemBuffer::snapshot).toList();
  }

  public List<Deployment> deployments() {
    checkThread();
    return List.copyOf(deployments.values());
  }

  public Deployment deployment(UUID backing) {
    checkThread();
    return deployments.get(Objects.requireNonNull(backing));
  }

  /** Trusted configuration path must authenticate EDIT/DEPLOY and current scope before calling. */
  public Deployment configure(
      NodeRegistry.Handle handle,
      Object owner,
      long referenceRevision,
      UUID principal,
      UUID network,
      UUID segment,
      Mode mode) {
    checkThread();
    if (!usable(handle, owner)) throw new IllegalStateException("Buffer is not currently bound");
    if (referenceRevision <= 0) throw new IllegalArgumentException("Invalid reference revision");
    var previous = deployments.get(handle.id());
    long revision =
        Math.incrementExact(
            Math.max(referenceRevision, previous == null ? 0 : previous.revision()));
    var deployment =
        new Deployment(
            handle.id(), revision, handle.generation(), principal, network, segment, mode);
    deployments.put(handle.id(), deployment);
    changed.run();
    return deployment;
  }

  public long bytes() {
    checkThread();
    return bytes;
  }

  public static long charge(ItemBuffer.State state) {
    long charge = 256;
    for (var slot : state.slots())
      if (slot.key() != null)
        charge += 256L + 3L * slot.key().registryId().length() + slot.key().componentByteCount();
    return charge;
  }

  private void install(ItemBuffer.State state, long charge) {
    UUID id = state.backing();
    var buffer =
        new ItemBuffer(
            state,
            key -> stackLimit.applyAsInt(key),
            () -> {
              long current = charge(buffers.get(id).snapshot());
              bytes += current - charges.put(id, current);
              changed.run();
            },
            proposed -> bytes <= MAX_BYTES - (charge(proposed) - charges.get(id)));
    buffers.put(id, buffer);
    charges.put(id, charge);
    bytes += charge;
  }

  private boolean valid(NodeRegistry.Handle handle, NodeRegistry.Position position) {
    return handle.kind() == NodeRegistry.Kind.BUFFERED_INTERFACE
        && buffers.containsKey(handle.id())
        && nodes.validate(handle) == NodeRegistry.Failure.OK
        && position.equals(nodes.definition(handle).position());
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Buffer registry requires its owning thread");
  }
}
