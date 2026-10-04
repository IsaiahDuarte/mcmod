package dev.izzy.factorycore.core.network;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Captures bounded reciprocal physical ports before publishing a complete current topology. */
public final class WiredDiscovery {
  public static final int MAX_SEEDS = 4096;
  public static final int MAX_PENDING = 24576;
  public static final int MAX_PROBES = 28672;

  public enum State {
    DISCOVERING,
    VALIDATING,
    PUBLISHED,
    CANCELLED,
    FAILED
  }

  public record Port(NodeRegistry.Position neighbor, UUID vertex) {
    public Port {
      Objects.requireNonNull(neighbor);
      Objects.requireNonNull(vertex);
    }
  }

  public record Wire(
      List<NetworkTopology.Node> nodes, List<Port> ports, NetworkTopology.Gateway gateway) {
    public Wire {
      nodes = List.copyOf(nodes);
      ports = List.copyOf(ports);
      if (nodes.isEmpty() || nodes.size() > 2 || ports.size() > 6)
        throw new IllegalArgumentException("Invalid physical descriptor bounds");
      var ids = new HashSet<UUID>();
      for (var node : nodes)
        if (!ids.add(node.id())) throw new IllegalArgumentException("Duplicate wire vertex");
      var neighbors = new HashSet<NodeRegistry.Position>();
      for (var port : ports)
        if (!ids.contains(port.vertex()) || !neighbors.add(port.neighbor()))
          throw new IllegalArgumentException("Invalid/duplicate physical port");
      if (gateway != null
          && (!ids.contains(gateway.upstream())
              || !ids.contains(gateway.downstream())
              || ids.contains(gateway.id())
              || gateway.upstream().equals(gateway.downstream())))
        throw new IllegalArgumentException("Invalid gateway vertices");
      if ((gateway == null) != (nodes.size() == 1))
        throw new IllegalArgumentException("Gateway must have two virtual vertices");
    }

    public Wire unloaded() {
      var offline =
          nodes.stream()
              .map(n -> new NetworkTopology.Node(n.id(), n.role(), n.label(), gateway != null))
              .toList();
      var gate =
          gateway == null
              ? null
              : new NetworkTopology.Gateway(
                  gateway.id(), gateway.upstream(), gateway.downstream(), gateway.policy(), false);
      return new Wire(offline, ports, gate);
    }

    private UUID portTo(NodeRegistry.Position position) {
      for (var port : ports) if (port.neighbor().equals(position)) return port.vertex();
      return null;
    }
  }

  public record Advance(int work, State state, int probes, int captured, String diagnostic) {}

  private record Request(NodeRegistry.Position position, NodeRegistry.Position parent, UUID from) {}

  private final Thread thread = Thread.currentThread();
  private final Iterator<NodeRegistry.Position> seeds;
  private final Function<NodeRegistry.Position, Wire> reader;
  private final LongSupplier sourceEpoch;
  private final long epoch;
  private final NetworkTopology topology;
  private final ArrayDeque<Request> pending = new ArrayDeque<>();
  private final Map<NodeRegistry.Position, Wire> probes = new HashMap<>();
  private final Map<NodeRegistry.Position, Wire> captured = new HashMap<>();
  private final Set<UUID> identities = new HashSet<>();
  private NetworkTopology.Validation validation;
  private State state = State.DISCOVERING;
  private int seedVisits;
  private String diagnostic = "";

  public WiredDiscovery(
      UUID region,
      int depth,
      Iterator<NodeRegistry.Position> seeds,
      Function<NodeRegistry.Position, Wire> reader,
      LongSupplier sourceEpoch) {
    this.seeds = Objects.requireNonNull(seeds);
    this.reader = Objects.requireNonNull(reader);
    this.sourceEpoch = Objects.requireNonNull(sourceEpoch);
    epoch = sourceEpoch.getAsLong();
    topology = new NetworkTopology(region, depth);
  }

  public Advance advance(int limit) {
    checkThread();
    if (limit <= 0 || limit > NetworkTopology.MAX_ADVANCE)
      throw new IllegalArgumentException("Invalid discovery visit budget");
    refresh();
    int work = 0;
    try {
      while (work < limit && (state == State.DISCOVERING || state == State.VALIDATING)) {
        work++;
        if (state == State.VALIDATING) {
          var result = validation.advance(1);
          if (result.done()) state = result.published() ? State.PUBLISHED : State.CANCELLED;
        } else if (!pending.isEmpty()) {
          capture(pending.removeFirst());
        } else if (seeds.hasNext()) {
          if (++seedVisits > MAX_SEEDS) throw new IllegalStateException("Discovery seed limit");
          pending.addLast(new Request(Objects.requireNonNull(seeds.next()), null, null));
        } else {
          validation = topology.beginValidation();
          state = State.VALIDATING;
        }
        refresh();
      }
    } catch (RuntimeException error) {
      state = State.FAILED;
      topology.invalidate();
      String message =
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      diagnostic = message.substring(0, Math.min(message.length(), 512));
    }
    return new Advance(work, state, probes.size(), captured.size(), diagnostic);
  }

  private void capture(Request request) {
    Wire wire;
    if (probes.containsKey(request.position())) wire = probes.get(request.position());
    else {
      if (probes.size() == MAX_PROBES) throw new IllegalStateException("Discovery probe limit");
      wire = reader.apply(request.position());
      probes.put(request.position(), wire);
    }
    if (wire == null) return;
    UUID reciprocal = request.parent() == null ? null : wire.portTo(request.parent());
    if (request.parent() != null && reciprocal == null) return;
    if (!captured.containsKey(request.position())) {
      for (var node : wire.nodes()) {
        if (!identities.add(node.id()))
          throw new IllegalStateException("Physical graph identity collision");
        topology.putNode(node);
      }
      if (wire.gateway() != null) {
        if (!identities.add(wire.gateway().id()))
          throw new IllegalStateException("Physical graph identity collision");
        topology.putGateway(wire.gateway());
      }
      captured.put(request.position(), wire);
      for (var port : wire.ports()) {
        if (pending.size() == MAX_PENDING)
          throw new IllegalStateException("Discovery neighbor queue limit");
        pending.addLast(new Request(port.neighbor(), request.position(), port.vertex()));
      }
    }
    if (reciprocal != null) topology.connect(request.from(), reciprocal);
  }

  /** Runtime coordination must call this immediately before a world/owner/chunk edit. */
  public void cancel() {
    checkThread();
    if (state != State.CANCELLED) {
      state = State.CANCELLED;
      topology.invalidate();
    }
  }

  public NetworkTopology publishedTopology() {
    checkThread();
    refresh();
    if (state != State.PUBLISHED)
      throw new IllegalStateException("Discovery is not currently published: " + state);
    return topology;
  }

  /** One immutable captured descriptor; avoids copying an entire region in tick work. */
  public Wire captured(NodeRegistry.Position position) {
    checkThread();
    return captured.get(Objects.requireNonNull(position));
  }

  private void refresh() {
    if (state != State.CANCELLED && sourceEpoch.getAsLong() != epoch) cancel();
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Discovery requires owner thread");
  }
}
