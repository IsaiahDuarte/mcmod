package dev.izzy.factorycore.core.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Server-owned affected region. No partially rebuilt graph can issue usable scope handles. */
public final class NetworkTopology {
  public static final int MAX_NODES = 4096;
  public static final int MAX_DEGREE = 8;
  public static final int MAX_GATEWAYS = 4096;
  public static final int MAX_ADVANCE = 256;
  public static final int MAX_PAGE = 64;
  public static final int MAX_DEPTH = 32;

  public enum Role {
    ROOT,
    CABLE,
    STORAGE,
    INTERFACE,
    PROGRAM,
    TERMINAL
  }

  public enum Status {
    VALID,
    REBUILDING,
    UNKNOWN,
    FOREIGN,
    STALE,
    UNLOADED,
    NO_ROOT,
    MULTIPLE_ROOTS,
    ROOT_HAS_PARENT,
    DEPTH_LIMIT,
    MULTIPLE_PARENTS,
    GATEWAY_CYCLE,
    GATEWAY_BYPASS
  }

  public record Node(UUID id, Role role, String label, boolean loaded) {
    public Node {
      Objects.requireNonNull(id);
      Objects.requireNonNull(role);
      Objects.requireNonNull(label);
      if (label.length() > 64
          || label.chars().anyMatch(Character::isISOControl)
          || (role != Role.INTERFACE && !label.isEmpty()))
        throw new IllegalArgumentException("Invalid local machine label");
    }
  }

  public record Gateway(
      UUID id, UUID upstream, UUID downstream, GatewayPolicy policy, boolean loaded) {
    public Gateway {
      Objects.requireNonNull(id);
      Objects.requireNonNull(upstream);
      Objects.requireNonNull(downstream);
      Objects.requireNonNull(policy);
    }
  }

  public record Scope(UUID region, long generation, UUID network, UUID segment, UUID node) {
    public Scope {
      Objects.requireNonNull(region);
      Objects.requireNonNull(network);
      Objects.requireNonNull(segment);
      Objects.requireNonNull(node);
      if (generation <= 0) throw new IllegalArgumentException("Invalid scope generation");
    }
  }

  public record Membership(Status status, Scope scope) {}

  public record Advance(int work, boolean done, boolean published) {}

  public record MachinePage(List<Scope> machines, boolean more) {
    public MachinePage {
      machines = List.copyOf(machines);
    }
  }

  public record Ancestor(UUID segment, GatewayPolicy policy) {}

  private record Link(UUID first, UUID second) {}

  private static final class Segment {
    final int representative;
    final Map<String, TreeMap<UUID, Scope>> machines = new HashMap<>();
    final List<Gateway> children = new ArrayList<>();
    UUID minimum;
    UUID root;
    Gateway parent;
    int parents;
    int remainingParents;
    int depth;
    int component;
    boolean visited;
    boolean suspended;

    Segment(int representative) {
      this.representative = representative;
    }

    UUID id() {
      return parent != null ? parent.id() : root != null ? root : minimum;
    }
  }

  private static final class Component {
    UUID root;
    int roots;
    Status error = Status.VALID;

    void fail(Status status) {
      if (status.ordinal() > error.ordinal()) error = status;
    }

    Status status() {
      if (error != Status.VALID) return error;
      if (roots == 0) return Status.NO_ROOT;
      return roots > 1 ? Status.MULTIPLE_ROOTS : Status.VALID;
    }
  }

  private record Entry(Membership membership, Segment segment) {}

  private record Publication(
      long generation,
      Map<UUID, Entry> entries,
      Map<Integer, Segment> segments,
      Map<UUID, Integer> indexes,
      int[] local) {}

  private final Thread thread = Thread.currentThread();
  private final UUID region;
  private final int depthLimit;
  private final TreeMap<UUID, Node> nodes = new TreeMap<>();
  private final Set<Link> links = new HashSet<>();
  private final TreeMap<UUID, Gateway> gateways = new TreeMap<>();
  private final Map<UUID, Set<Link>> localIncidence = new HashMap<>();
  private final Map<UUID, Set<UUID>> gatewayIncidence = new HashMap<>();
  private long generation = 1;
  private Publication publication;

  public NetworkTopology(UUID region, int depthLimit) {
    this.region = Objects.requireNonNull(region);
    if (depthLimit < 1 || depthLimit > MAX_DEPTH)
      throw new IllegalArgumentException("Invalid gateway depth limit");
    this.depthLimit = depthLimit;
  }

  public void putNode(Node node) {
    checkThread();
    Objects.requireNonNull(node);
    if (gateways.containsKey(node.id()))
      throw new IllegalArgumentException("Gateway/vertex IDs must be distinct");
    if (!nodes.containsKey(node.id()) && nodes.size() == MAX_NODES)
      throw new IllegalStateException("Topology vertex limit");
    if (node.equals(nodes.get(node.id()))) return;
    bump();
    nodes.put(node.id(), node);
    localIncidence.computeIfAbsent(node.id(), ignored -> new HashSet<>());
    gatewayIncidence.computeIfAbsent(node.id(), ignored -> new HashSet<>());
  }

  public void removeNode(UUID id) {
    checkThread();
    if (!nodes.containsKey(id)) return;
    bump();
    for (var link : List.copyOf(localIncidence.get(id))) unlink(link);
    for (var gate : List.copyOf(gatewayIncidence.get(id))) removeGatewayInternal(gate);
    nodes.remove(id);
    localIncidence.remove(id);
    gatewayIncidence.remove(id);
  }

  public void connect(UUID first, UUID second) {
    checkThread();
    requireNode(first);
    requireNode(second);
    if (first.equals(second)) throw new IllegalArgumentException("Local self-link");
    var link = link(first, second);
    if (links.contains(link)) return;
    requireDegree(first, null);
    requireDegree(second, null);
    bump();
    links.add(link);
    localIncidence.get(first).add(link);
    localIncidence.get(second).add(link);
  }

  public void disconnect(UUID first, UUID second) {
    checkThread();
    var link = link(Objects.requireNonNull(first), Objects.requireNonNull(second));
    if (!links.contains(link)) return;
    bump();
    unlink(link);
  }

  public void putGateway(Gateway gateway) {
    checkThread();
    Objects.requireNonNull(gateway);
    requireNode(gateway.upstream());
    requireNode(gateway.downstream());
    if (nodes.containsKey(gateway.id()))
      throw new IllegalArgumentException("Gateway/vertex IDs must be distinct");
    var previous = gateways.get(gateway.id());
    if (gateway.equals(previous)) return;
    if (previous == null && gateways.size() == MAX_GATEWAYS)
      throw new IllegalStateException("Gateway limit");
    requireDegree(gateway.upstream(), gateway.id());
    requireDegree(gateway.downstream(), gateway.id());
    bump();
    if (previous != null) removeGatewayInternal(previous.id());
    gateways.put(gateway.id(), gateway);
    gatewayIncidence.get(gateway.upstream()).add(gateway.id());
    gatewayIncidence.get(gateway.downstream()).add(gateway.id());
  }

  public void removeGateway(UUID id) {
    checkThread();
    if (!gateways.containsKey(id)) return;
    bump();
    removeGatewayInternal(id);
  }

  public Validation beginValidation() {
    checkThread();
    return new Validation();
  }

  /** Invalidate before budgeted world discovery knows the concrete edge changes. */
  public void invalidate() {
    checkThread();
    bump();
  }

  public Membership membership(UUID node) {
    checkThread();
    if (!nodes.containsKey(node)) return new Membership(Status.UNKNOWN, null);
    if (publication == null || publication.generation() != generation)
      return new Membership(Status.REBUILDING, null);
    return publication.entries().get(node).membership();
  }

  public Status validate(Scope scope) {
    checkThread();
    Objects.requireNonNull(scope);
    if (!region.equals(scope.region())) return Status.FOREIGN;
    if (generation != scope.generation()) return Status.STALE;
    var current = membership(scope.node());
    if (current.status() != Status.VALID) return current.status();
    return scope.equals(current.scope()) ? Status.VALID : Status.STALE;
  }

  public boolean isInterface(Scope scope) {
    checkThread();
    requireScope(scope);
    return nodes.get(scope.node()).role() == Role.INTERFACE;
  }

  public MachinePage machines(Scope origin, String label, UUID after, int limit) {
    checkThread();
    Objects.requireNonNull(label);
    if (limit < 1 || limit > MAX_PAGE || label.length() > 64)
      throw new IllegalArgumentException("Invalid machine page");
    requireScope(origin);
    var matches = publication.entries().get(origin.node()).segment().machines.get(label);
    if (matches == null || label.isEmpty()) return new MachinePage(List.of(), false);
    var candidates = after == null ? matches : matches.tailMap(after, false);
    var page = new ArrayList<Scope>();
    var iterator = candidates.values().iterator();
    while (page.size() < limit && iterator.hasNext()) page.add(iterator.next());
    return new MachinePage(page, iterator.hasNext());
  }

  /**
   * Nearest parent first; no child segments are returned. Trusted core use, not a client endpoint.
   */
  public List<Ancestor> ancestors(Scope origin) {
    checkThread();
    requireScope(origin);
    var path = new ArrayList<Ancestor>();
    var segment = publication.entries().get(origin.node()).segment();
    while (segment.parent != null) {
      if (path.size() == MAX_DEPTH) throw new IllegalStateException("Invalid published ancestry");
      var gate = segment.parent;
      int upstream = publication.indexes().get(gate.upstream());
      segment = publication.segments().get(publication.local()[upstream]);
      path.add(new Ancestor(segment.id(), gate.policy()));
    }
    return List.copyOf(path);
  }

  private void requireScope(Scope scope) {
    var result = validate(scope);
    if (result != Status.VALID)
      throw new IllegalStateException("Invalid topology scope: " + result);
  }

  private void requireNode(UUID id) {
    if (!nodes.containsKey(Objects.requireNonNull(id)))
      throw new IllegalArgumentException("Unknown vertex");
  }

  private void requireDegree(UUID id, UUID replacing) {
    int degree = localIncidence.get(id).size() + gatewayIncidence.get(id).size();
    if (replacing != null && gatewayIncidence.get(id).contains(replacing)) degree--;
    if (degree >= MAX_DEGREE) throw new IllegalStateException("Topology vertex degree limit");
  }

  private static Link link(UUID first, UUID second) {
    return first.compareTo(second) < 0 ? new Link(first, second) : new Link(second, first);
  }

  private void unlink(Link link) {
    links.remove(link);
    localIncidence.get(link.first()).remove(link);
    localIncidence.get(link.second()).remove(link);
  }

  private void removeGatewayInternal(UUID id) {
    var gate = gateways.remove(id);
    gatewayIncidence.get(gate.upstream()).remove(id);
    gatewayIncidence.get(gate.downstream()).remove(id);
  }

  private void bump() {
    generation = Math.incrementExact(generation);
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Topology requires owner thread");
  }

  /**
   * Each phase visit is charged; publication is O(1) and never copies the entire result in a tick.
   */
  public final class Validation {
    private final long capturedGeneration = generation;
    private final List<Node> capturedNodes = new ArrayList<>();
    private final Map<UUID, Integer> indexes = new HashMap<>();
    private final int[] local = new int[nodes.size()];
    private final int[] weak = new int[nodes.size()];
    private final int[] localRank = new int[nodes.size()];
    private final int[] weakRank = new int[nodes.size()];
    private final Map<Integer, Segment> segments = new HashMap<>();
    private final Map<Integer, Component> components = new HashMap<>();
    private final List<Gateway> capturedGateways = new ArrayList<>();
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Iterator<Node> nodeIterator = nodes.values().iterator();
    private final Iterator<Link> linkIterator = links.iterator();
    private final Iterator<Gateway> gatewayIterator = gateways.values().iterator();
    private Iterator<Segment> segmentIterator;
    private final ArrayDeque<Segment> queue = new ArrayDeque<>();
    private Segment visiting;
    private int child;
    private int phase;
    private int index;
    private boolean done;
    private boolean published;

    private Validation() {}

    public Advance advance(int limit) {
      checkThread();
      if (limit < 1 || limit > MAX_ADVANCE)
        throw new IllegalArgumentException("Invalid validation budget");
      if (capturedGeneration != generation) {
        done = true;
        published = false;
      }
      int work = 0;
      while (!done && work < limit) {
        visit();
        work++;
      }
      return new Advance(work, done, published);
    }

    private void visit() {
      switch (phase) {
        case 0 -> captureNode();
        case 1 -> collapseLink();
        case 2 -> captureGateway();
        case 3 -> collectSegment();
        case 4 -> connectGateway();
        case 5 -> initializeWalk();
        case 6 -> walkGateway();
        case 7 -> checkSegment();
        case 8 -> publishNode();
        case 9 -> {
          publication = new Publication(capturedGeneration, entries, segments, indexes, local);
          published = true;
          done = true;
        }
        default -> throw new IllegalStateException("Invalid validation phase");
      }
    }

    private void captureNode() {
      if (!nodeIterator.hasNext()) {
        phase++;
        return;
      }
      var node = nodeIterator.next();
      int next = capturedNodes.size();
      indexes.put(node.id(), next);
      capturedNodes.add(node);
      local[next] = next;
      weak[next] = next;
    }

    private void collapseLink() {
      if (!linkIterator.hasNext()) {
        phase++;
        return;
      }
      var edge = linkIterator.next();
      int first = indexes.get(edge.first());
      int second = indexes.get(edge.second());
      union(local, localRank, first, second);
      union(weak, weakRank, first, second);
    }

    private void captureGateway() {
      if (!gatewayIterator.hasNext()) {
        phase++;
        index = 0;
        return;
      }
      var gate = gatewayIterator.next();
      capturedGateways.add(gate);
      union(weak, weakRank, indexes.get(gate.upstream()), indexes.get(gate.downstream()));
    }

    private void collectSegment() {
      if (index == capturedNodes.size()) {
        phase++;
        index = 0;
        return;
      }
      int next = index++;
      var node = capturedNodes.get(next);
      local[next] = find(local, next);
      int componentId = find(weak, next);
      var component = components.computeIfAbsent(componentId, ignored -> new Component());
      var segment = segments.computeIfAbsent(local[next], Segment::new);
      segment.component = componentId;
      if (segment.minimum == null || node.id().compareTo(segment.minimum) < 0)
        segment.minimum = node.id();
      if (node.role() == Role.ROOT) {
        component.roots++;
        component.root = node.id();
        segment.root = node.id();
      }
      if (!node.loaded() && (node.role() == Role.ROOT || node.role() == Role.CABLE))
        segment.suspended = true;
    }

    private void connectGateway() {
      if (index == capturedGateways.size()) {
        phase++;
        segmentIterator = segments.values().iterator();
        return;
      }
      var gate = capturedGateways.get(index++);
      var upstream = segment(gate.upstream());
      var downstream = segment(gate.downstream());
      var component = components.get(upstream.component);
      if (upstream == downstream) component.fail(Status.GATEWAY_BYPASS);
      upstream.children.add(gate);
      downstream.parents++;
      downstream.parent = gate;
      if (downstream.parents > 1) component.fail(Status.MULTIPLE_PARENTS);
      if (!gate.loaded()) downstream.suspended = true;
    }

    private void initializeWalk() {
      if (!segmentIterator.hasNext()) {
        phase++;
        return;
      }
      var segment = segmentIterator.next();
      segment.remainingParents = segment.parents;
      if (segment.parents == 0) queue.add(segment);
      if (segment.root != null && segment.parents != 0)
        components.get(segment.component).fail(Status.ROOT_HAS_PARENT);
    }

    private void walkGateway() {
      if (visiting == null) {
        if (queue.isEmpty()) {
          phase++;
          segmentIterator = segments.values().iterator();
          return;
        }
        visiting = queue.remove();
        child = 0;
        visiting.visited = true;
        return;
      }
      if (child == visiting.children.size()) {
        visiting = null;
        return;
      }
      var downstream = segment(visiting.children.get(child++).downstream());
      downstream.suspended |= visiting.suspended;
      downstream.depth = Math.min(MAX_DEPTH + 1, Math.max(downstream.depth, visiting.depth + 1));
      if (--downstream.remainingParents == 0) queue.add(downstream);
    }

    private void checkSegment() {
      if (!segmentIterator.hasNext()) {
        phase++;
        index = 0;
        return;
      }
      var segment = segmentIterator.next();
      var component = components.get(segment.component);
      if (!segment.visited) component.fail(Status.GATEWAY_CYCLE);
      if (segment.depth > depthLimit) component.fail(Status.DEPTH_LIMIT);
    }

    private void publishNode() {
      if (index == capturedNodes.size()) {
        phase++;
        return;
      }
      var node = capturedNodes.get(index++);
      var segment = segment(node.id());
      var component = components.get(segment.component);
      var status = component.status();
      if (status == Status.VALID && (segment.suspended || !node.loaded())) status = Status.UNLOADED;
      Scope scope =
          status == Status.VALID
              ? new Scope(region, capturedGeneration, component.root, segment.id(), node.id())
              : null;
      entries.put(node.id(), new Entry(new Membership(status, scope), segment));
      if (scope != null && node.role() == Role.INTERFACE && !node.label().isEmpty())
        segment
            .machines
            .computeIfAbsent(node.label(), ignored -> new TreeMap<>())
            .put(node.id(), scope);
    }

    private Segment segment(UUID node) {
      return segments.get(local[indexes.get(node)]);
    }

    private static int find(int[] parents, int node) {
      while (parents[node] != node) node = parents[node];
      return node;
    }

    private static void union(int[] parents, int[] ranks, int first, int second) {
      int left = find(parents, first);
      int right = find(parents, second);
      if (left == right) return;
      if (ranks[left] < ranks[right]) parents[left] = right;
      else {
        parents[right] = left;
        if (ranks[left] == ranks[right]) ranks[left]++;
      }
    }
  }
}
