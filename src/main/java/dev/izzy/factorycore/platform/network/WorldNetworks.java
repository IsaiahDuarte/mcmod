package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.DiscoverySeeds;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.network.WiredDiscovery;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.FactoryCoreConfig;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldBufferImports;
import dev.izzy.factorycore.platform.storage.WorldConnectors;
import dev.izzy.factorycore.platform.storage.WorldMachinePresets;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned indexed geometry; world edits invalidate access before bounded rebuilding. */
public final class WorldNetworks {
  public static final int MAX_POSITIONS = 65536;
  public static final int MAX_QUEUED = 8192;
  private static final int STEP = 32;
  private static final UUID WORK_NETWORK = new UUID(0xFAC7000, 1);
  private static final UUID WORK_PROGRAM = new UUID(0xFAC7000, 2);
  private static final Map<MinecraftServer, WorldNetworks> SERVERS = new HashMap<>();

  private record ChunkKey(String dimension, int x, int z) {}

  private record Structural(NodeRegistry.Handle handle, NodeRegistry.Position position) {}

  private final MinecraftServer server;
  private final int gatewayDepth;
  private final DeviceSavedData saved;
  private final FairScheduler scheduler;
  private WorldStorageTransfers transfers;
  private WorldBufferImports imports;
  private WorldMachinePresets machinePresets;
  private WorldConnectors connectors;
  private final Map<NodeRegistry.Position, Region> owners = new HashMap<>();
  private final Map<NodeRegistry.Position, WiredDiscovery.Wire> geometry = new HashMap<>();
  private final Map<ChunkKey, Map<Region, Integer>> chunks = new HashMap<>();
  private final Map<String, WorldWireReader> readers = new HashMap<>();
  private final ArrayDeque<Region> active = new ArrayDeque<>();
  private String diagnostic = "";
  private static final String SCHEDULING_LIMIT = "Network scheduling limit: ";
  private final WirelessBudget wirelessBudget = new WirelessBudget();

  private static final class Region {
    final UUID id = UUID.randomUUID();
    final NodeRegistry.Position anchor;
    final Set<NodeRegistry.Position> points = new LinkedHashSet<>();
    final DiscoverySeeds sources;
    final Map<UUID, Structural> structures = new HashMap<>();
    final Map<UUID, WirelessBudget.Hop> wireless = new HashMap<>();
    NetworkAuthority authority;
    final RegionReference<Region> reference = new RegionReference<>(this);
    WiredDiscovery discovery;
    Iterator<NodeRegistry.Position> preparing;
    boolean valid;
    boolean restart;
    boolean retired;
    String failure = "";

    Region(NodeRegistry.Position anchor) {
      this.anchor = anchor;
      sources = new DiscoverySeeds(anchor);
    }
  }

  private WorldNetworks(MinecraftServer server) {
    this.server = server;
    gatewayDepth = FactoryCoreConfig.gatewayDepth();
    DeviceSavedData opened = null;
    try {
      opened = DeviceSavedData.get(server);
      opened.registry().routing(this::available);
    } catch (IllegalStateException unavailable) {
      diagnostic = message(unavailable);
      opened = null;
    }
    saved = opened;
    scheduler =
        new FairScheduler(
            FairScheduler.Limits.defaults(),
            System::nanoTime,
            failure -> {
              diagnostic =
                  failure.diagnostic().substring(0, Math.min(512, failure.diagnostic().length()));
            });
  }

  public static WorldNetworks get(MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("World networking requires server thread");
    return SERVERS.computeIfAbsent(server, WorldNetworks::new);
  }

  public WorldStorageTransfers transfers() {
    checkThread();
    if (saved == null) throw new IllegalStateException("World storage unavailable: " + diagnostic);
    if (transfers == null) transfers = new WorldStorageTransfers(server, this);
    return transfers;
  }

  public WorldConnectors connectors() {
    checkThread();
    if (saved == null) throw new IllegalStateException("World storage unavailable: " + diagnostic);
    if (connectors == null) connectors = new WorldConnectors(server, this);
    return connectors;
  }

  public WorldBufferImports imports() {
    checkThread();
    if (saved == null) throw new IllegalStateException("World storage unavailable: " + diagnostic);
    if (imports == null) imports = new WorldBufferImports(server, this);
    return imports;
  }

  public WorldMachinePresets machinePresets() {
    checkThread();
    if (saved == null) throw new IllegalStateException("World storage unavailable");
    if (machinePresets == null) machinePresets = new WorldMachinePresets(server, this);
    return machinePresets;
  }

  public static void machineInvalidated(Level level, MachineInterfaceEntity owner) {
    if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread())
      return;
    var runtime = SERVERS.get(serverLevel.getServer());
    if (runtime != null && runtime.machinePresets != null) runtime.machinePresets.unregister(owner);
  }

  /** Teardown does not initialize services; stale copied holders cannot unregister replacements. */
  public static void bufferInvalidated(Level level, BufferedInterfaceEntity owner) {
    if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread())
      return;
    var runtime = SERVERS.get(serverLevel.getServer());
    if (runtime != null && runtime.imports != null) runtime.imports.unregister(owner);
  }

  /** Cleanup never constructs storage services during recovery or server shutdown. */
  public static void connectorInvalidated(Level level, ConnectorEntity owner) {
    if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread())
      return;
    var runtime = SERVERS.get(serverLevel.getServer());
    if (runtime != null && runtime.connectors != null) runtime.connectors.invalidate(owner);
  }

  public NetworkAuthority.Decision authorize(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      NetworkPermissions.Permission operation) {
    var membership = membership(origin);
    if (membership.status() != NetworkTopology.Status.VALID)
      return NetworkAuthority.Decision.INVALID_SCOPE;
    return resolve(owners.get(origin)).authority.authorize(actor, membership.scope(), operation);
  }

  /**
   * Replaces {@code principal}'s explicit grants on the network that owns {@code origin}. Manage is
   * checked at {@code origin}, so gateway branch policy above it applies; {@code INVALID_SCOPE}
   * origins return {@code DENIED} without changing anything.
   */
  public NetworkPermissions.Change setGrants(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      UUID principal,
      Set<NetworkPermissions.Permission> grants) {
    checkThread();
    var membership = membership(origin);
    if (membership.status() != NetworkTopology.Status.VALID)
      return NetworkPermissions.Change.DENIED;
    return resolve(owners.get(origin))
        .authority
        .setGrants(actor, membership.scope(), principal, grants);
  }

  /**
   * Explicit ownership transfer of the network that owns {@code origin} (ADR 0055). Only the
   * current owner or a server operator may transfer, and only with current Manage at {@code origin}
   * (so ancestor branch policy applies); delegated Manage holders cannot take or give away
   * ownership. The nil UUID is never a principal. Invalid scopes return {@code DENIED}.
   */
  public NetworkPermissions.Change transferOwnership(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, UUID nextOwner) {
    checkThread();
    if (nextOwner.equals(new UUID(0, 0))) return NetworkPermissions.Change.DENIED;
    var membership = membership(origin);
    if (membership.status() != NetworkTopology.Status.VALID)
      return NetworkPermissions.Change.DENIED;
    var current = saved.network(membership.scope().network()).snapshot();
    if (!actor.operator() && !actor.id().equals(current.owner()))
      return NetworkPermissions.Change.DENIED;
    return resolve(owners.get(origin))
        .authority
        .transferOwnership(actor, membership.scope(), nextOwner);
  }

  /** Current owner/grants of the network at {@code origin}, or null without Manage there. */
  public NetworkPermissions.State grants(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin) {
    checkThread();
    if (authorize(actor, origin, NetworkPermissions.Permission.MANAGE)
        != NetworkAuthority.Decision.ALLOWED) return null;
    return saved.network(membership(origin).scope().network()).snapshot();
  }

  public FairScheduler scheduler() {
    checkThread();
    return scheduler;
  }

  public String diagnostic() {
    checkThread();
    return diagnostic;
  }

  public static void postTick(ServerTickEvent.Post event) {
    var runtime = SERVERS.get(event.getServer());
    if (runtime != null) runtime.tick();
  }

  public void tick() {
    checkThread();
    if (imports != null) imports.tick();
    if (machinePresets != null) machinePresets.tick();
    if (!active.isEmpty() && !scheduler.isQueued(WORK_NETWORK, WORK_PROGRAM, "world-regions"))
      wake();
    scheduler.tick();
  }

  public static void stopped(ServerStoppedEvent event) {
    SERVERS.remove(event.getServer());
  }

  public static void tagsReloaded(OnDatapackSyncEvent event) {
    if (event.getPlayer() != null) return;
    var runtime = SERVERS.get(event.getPlayerList().getServer());
    if (runtime != null && runtime.imports != null) runtime.imports.tagsReloaded();
    if (runtime != null && runtime.machinePresets != null) runtime.machinePresets.tagsReloaded();
  }

  public static void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    var runtime = SERVERS.get(player.serverLevel().getServer());
    if (runtime != null && runtime.transfers != null) runtime.transfers.playerDisconnected(player);
  }

  // Minecraft owns the borrowed level; the coordinator never closes worlds or chunk caches.
  public static void changed(Level level, BlockPos position) {
    if (!(level instanceof ServerLevel serverLevel)) return;
    get(serverLevel.getServer())
        .changed(
            new NodeRegistry.Position(level.dimension().location().toString(), position.asLong()));
  }

  public void changed(NodeRegistry.Position position) {
    checkThread();
    if (saved == null) return;
    var affected = new HashSet<Region>();
    var owner = owners.get(position);
    if (owner != null) affected.add(resolve(owner));
    var block = BlockPos.of(position.block());
    for (var face : Direction.values()) {
      owner =
          owners.get(
              new NodeRegistry.Position(position.dimension(), block.relative(face).asLong()));
      if (owner != null) affected.add(resolve(owner));
    }
    for (var region : affected) if (region != null) invalidate(region);
    if (owners.get(position) == null && wired(position)) start(position, null);
    wake();
  }

  private void invalidate(Region current) {
    if (current == null || current.retired || current.restart) return;
    current.valid = false;
    current.discovery.cancel();
    current.restart = true;
    if (active.size() >= MAX_QUEUED) {
      diagnostic = "Network rebuild queue limit";
      return;
    }
    active.addLast(current);
  }

  private Region start(NodeRegistry.Position anchor, Region previous) {
    if (previous != null && previous.retired) return resolve(previous);
    if (active.size() >= MAX_QUEUED) {
      diagnostic = "Network rebuild queue limit";
      return null;
    }
    if (previous == null && owners.size() >= MAX_POSITIONS) {
      diagnostic = "Known network position limit";
      return null;
    }
    var region = new Region(anchor);
    region.sources.appendPositions(List.of(anchor).iterator());
    if (previous != null) absorb(region, previous);
    region.discovery =
        new WiredDiscovery(
            region.id,
            gatewayDepth,
            region.sources,
            position -> probe(region, position),
            () -> 0,
            (position, wire) -> capture(region, position, wire));
    if (previous == null) {
      region.points.add(anchor);
      assign(anchor, region);
    }
    active.addLast(region);
    return region;
  }

  private void absorb(Region target, Region previous) {
    if (previous == target || previous.retired) return;
    previous.valid = false;
    if (previous.discovery != null) previous.discovery.cancel();
    target.sources.appendPositions(previous.points.iterator());
    target.sources.append(previous.sources);
    previous.retired = true;
    previous.reference.replaceWith(target.reference);
  }

  private WiredDiscovery.Wire probe(Region region, NodeRegistry.Position position) {
    var old = owners.get(position);
    if (old != null && resolve(old) == region) region.points.add(position);
    return readers
        .computeIfAbsent(
            position.dimension(), dimension -> new WorldWireReader(level(dimension), geometry::get))
        .apply(position);
  }

  private void capture(Region region, NodeRegistry.Position position, WiredDiscovery.Wire wire) {
    var previous = owners.get(position);
    var current = previous == null ? null : resolve(previous);
    if (current != null && current != region) absorb(region, current);
    if (previous == null && owners.size() >= MAX_POSITIONS)
      throw new IllegalStateException("Known network position limit");
    region.points.add(position);
    geometry.put(position, wire);
    assign(position, region);
  }

  private void assign(NodeRegistry.Position position, Region next) {
    var previous = next == null ? owners.remove(position) : owners.put(position, next);
    if (previous == next) return;
    var key = chunk(position);
    var index = chunks.computeIfAbsent(key, k -> new HashMap<>());
    if (previous != null) {
      int count = index.getOrDefault(previous, 0) - 1;
      if (count <= 0) index.remove(previous);
      else index.put(previous, count);
    }
    if (next != null) index.merge(next, 1, Integer::sum);
    if (index.isEmpty()) chunks.remove(key);
  }

  /** True when discovery captured this endpoint's wireless port reciprocally at its partner. */
  private static boolean bridged(
      Region region,
      LinkEndpointEntity link,
      NodeRegistry.Position position,
      WiredDiscovery.Wire wire) {
    long back = BlockPos.of(position.block()).relative(link.back()).asLong();
    for (var port : wire.ports()) {
      if (port.neighbor().block() == back) continue;
      var other = region.discovery.captured(port.neighbor());
      if (other != null && other.ports().stream().anyMatch(p -> p.neighbor().equals(position)))
        return true;
    }
    return false;
  }

  private Region resolve(Region region) {
    return region.reference.current();
  }

  private void wake() {
    if (active.isEmpty()) return;
    // Topology work uses the scheduler's reserved maintenance slots, so saturated script/job
    // queues delay but never strand a rebuild; tick() retries a refused wake.
    var admission =
        scheduler.submitReserved(WORK_NETWORK, WORK_PROGRAM, "world-regions", STEP, this::step);
    if (admission != FairScheduler.Admission.ACCEPTED
        && admission != FairScheduler.Admission.REPLACED) {
      diagnostic = SCHEDULING_LIMIT + admission;
    } else if (diagnostic.startsWith(SCHEDULING_LIMIT)) diagnostic = "";
  }

  private void step() {
    int visits = 0;
    while (visits < STEP && !active.isEmpty()) {
      visits++;
      var region = active.removeFirst();
      if (region.retired) continue;
      try {
        if (region.restart) {
          start(region.anchor, region);
          continue;
        }
        if (region.preparing != null) {
          if (region.preparing.hasNext()) prepare(region, region.preparing.next());
          else {
            region.valid = true;
            region.preparing = null;
          }
        } else {
          var progress = region.discovery.advance(1);
          if (progress.state() == WiredDiscovery.State.PUBLISHED) {
            region.authority = new NetworkAuthority(region.discovery.publishedTopology());
            region.preparing = region.points.iterator();
          } else if (progress.state() == WiredDiscovery.State.FAILED
              || progress.state() == WiredDiscovery.State.CANCELLED) {
            region.failure =
                progress.diagnostic().isEmpty()
                    ? "Network rebuild cancelled"
                    : progress.diagnostic();
            diagnostic = region.failure;
            continue;
          }
        }
        if (!region.valid && !region.retired) active.addLast(region);
      } catch (IllegalArgumentException | IllegalStateException error) {
        region.failure = message(error);
        diagnostic = region.failure;
        region.discovery.cancel();
      }
    }
    wake();
  }

  // ServerLevel and chunk cache are borrowed from Minecraft.
  private void prepare(Region region, NodeRegistry.Position position) {
    var wire = region.discovery.captured(position);
    if (wire == null) {
      var previous = owners.get(position);
      if (previous != null && resolve(previous) == region) {
        assign(position, null);
        geometry.remove(position);
      }
      return;
    }
    var level = level(position.dimension());
    var block = BlockPos.of(position.block());
    var chunk = Loaded.chunk(level, block);
    assign(position, region);
    if (chunk == null) return;
    var entity = chunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK);
    if (entity instanceof NetworkNodeEntity node
        && node.handle() != null
        && node.diagnostic().isEmpty()) {
      var lease = node.handle();
      region.structures.put(lease.id(), new Structural(lease, position));
      if (lease.kind() == NodeRegistry.Kind.CONTROLLER
          && saved.nodes().validate(lease) == NodeRegistry.Failure.OK)
        region.authority.register(saved.network(lease.id()));
    }
    if (entity instanceof LinkEndpointEntity link) {
      var member = region.discovery.publishedTopology().membership(wire.nodes().getFirst().id());
      // Discovery joins only reciprocal captured ports, so an unpaired, unpowered or one-sided
      // endpoint is a dead-end node with no wireless route to meter.
      if (bridged(region, link, position, wire)
          && member.status() == NetworkTopology.Status.VALID) {
        var pair = link.pair();
        if (pair == null) {
          // The published bridge is stale; every activity change notifies both ends, so rebuild
          // rather than publish an unmetered route or strand the region as failed.
          invalidate(region);
          return;
        }
        var previous =
            region.wireless.putIfAbsent(
                member.scope().segment(), new WirelessBudget.Hop(pair, link.pairScale()));
        if (previous != null && !previous.link().equals(pair))
          throw new IllegalStateException("Ambiguous wireless bridge segment");
      }
    }
    if (entity instanceof EnergyBankEntity bank && bank.handle() != null) {
      boolean valid =
          region.discovery.publishedTopology().membership(wire.nodes().getFirst().id()).status()
              == NetworkTopology.Status.VALID;
      saved.registry().availability(bank.handle(), bank.location(), valid);
    }
    if (entity instanceof StorageDriveEntity drive) {
      UUID vertex = wire.nodes().getFirst().id();
      boolean valid =
          region.discovery.publishedTopology().membership(vertex).status()
              == NetworkTopology.Status.VALID;
      for (int slot = 0; slot < 4; slot++) {
        var stack = drive.cell(slot);
        if (stack.isEmpty()) continue;
        try {
          var handle = CellHandleData.read(stack);
          if (handle != null) saved.registry().availability(handle, drive.location(slot), valid);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
          diagnostic = message(invalid);
        }
      }
    }
  }

  public NetworkTopology.Membership membership(NodeRegistry.Position position) {
    checkThread();
    var region = owners.get(position);
    region = region == null ? null : resolve(region);
    if (region == null || !region.valid || region.retired)
      return new NetworkTopology.Membership(NetworkTopology.Status.REBUILDING, null);
    var wire = geometry.get(position);
    if (wire == null) return new NetworkTopology.Membership(NetworkTopology.Status.UNKNOWN, null);
    UUID vertex =
        wire.gateway() == null ? wire.nodes().getFirst().id() : wire.gateway().downstream();
    var result = region.discovery.publishedTopology().membership(vertex);
    if (result.status() != NetworkTopology.Status.VALID) return result;
    if (!structuralValid(region, result.scope()))
      return new NetworkTopology.Membership(NetworkTopology.Status.UNLOADED, null);
    return result;
  }

  private boolean structuralValid(Region region, NetworkTopology.Scope scope) {
    if (!current(region.structures.get(scope.network()))) return false;
    for (var ancestor : region.discovery.publishedTopology().ancestors(scope)) {
      // Ancestor descriptors contain the parent's segment; the child segment identifies its
      // gateway.
      // The chain is bounded by the configured depth, including the immediate child gateway.
      if (!ancestor.segment().equals(scope.network())
          && !current(region.structures.get(ancestor.segment()))) return false;
    }
    return scope.segment().equals(scope.network())
        || current(region.structures.get(scope.segment()));
  }

  private boolean current(Structural structural) {
    if (structural == null
        || saved.nodes().validate(structural.handle()) != NodeRegistry.Failure.OK) return false;
    return structural.position().equals(saved.nodes().definition(structural.handle()).position());
  }

  /** Bounded local label resolution; no provider access or inherited machine visibility. */
  public NetworkAuthority.MachineQuery machines(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      String label,
      UUID after,
      int limit) {
    checkThread();
    var member = membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new NetworkAuthority.MachineQuery(
          NetworkAuthority.Decision.INVALID_SCOPE, List.of(), false);
    return resolve(owners.get(origin))
        .authority
        .machines(actor, member.scope(), label, after, limit);
  }

  /** Bounded VIEW-authorized local label catalog; rebuilding regions fail closed. */
  public NetworkTopology.LabelPage labels(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, String after, int limit) {
    checkThread();
    var member = membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new NetworkTopology.LabelPage(List.of(), false);
    return resolve(owners.get(origin)).authority.labels(actor, member.scope(), after, limit);
  }

  /** Gateways above {@code origin}, nearest first, with their policies; empty when invalid. */
  public List<NetworkTopology.Ancestor> ancestors(NodeRegistry.Position origin) {
    checkThread();
    var membership = membership(origin);
    if (membership.status() != NetworkTopology.Status.VALID) return List.of();
    return resolve(owners.get(origin)).discovery.publishedTopology().ancestors(membership.scope());
  }

  public NetworkAuthority.StorageQuery storage(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKey resource,
      NetworkPermissions.Permission operation) {
    var membership = membership(origin);
    if (membership.status() != NetworkTopology.Status.VALID)
      return new NetworkAuthority.StorageQuery(NetworkAuthority.Decision.INVALID_SCOPE, List.of());
    return resolve(owners.get(origin))
        .authority
        .storage(actor, membership.scope(), resource, operation);
  }

  /**
   * Published unique segment path. A wireless segment is isolated between its two gateway ports;
   * collecting ancestor segments therefore identifies each crossed pair without scanning geometry.
   * Null means stale/unavailable; empty is a valid wired path. This grants no resource authority.
   */
  private List<WirelessBudget.Hop> wirelessPath(
      NodeRegistry.Position from, NodeRegistry.Position to) {
    var first = membership(from);
    var second = membership(to);
    if (first.status() != NetworkTopology.Status.VALID
        || second.status() != NetworkTopology.Status.VALID
        || !first.scope().network().equals(second.scope().network())) return null;
    var region = resolve(owners.get(from));
    if (region != resolve(owners.get(to))) return null;
    if (first.scope().segment().equals(second.scope().segment())) return List.of();
    var a = new ArrayList<UUID>();
    var b = new ArrayList<UUID>();
    a.add(first.scope().segment());
    b.add(second.scope().segment());
    for (var ancestor : ancestors(from)) a.add(ancestor.segment());
    for (var ancestor : ancestors(to)) b.add(ancestor.segment());
    var other = new HashSet<>(b);
    UUID common = null;
    for (var segment : a)
      if (other.contains(segment)) {
        common = segment;
        break;
      }
    if (common == null) return null;
    var path = new LinkedHashSet<WirelessBudget.Hop>();
    for (var chain : List.of(a, b))
      for (var segment : chain) {
        if (segment.equals(common)) break;
        var pair = region.wireless.get(segment);
        if (pair != null) path.add(pair);
      }
    return List.copyOf(path);
  }

  /** Advisory per-pair capacity, in whole item/mB/FE units; no quota debit or provider calls. */
  public long movementAvailable(
      NodeRegistry.Position from, NodeRegistry.Position to, ResourceKey key, long amount) {
    checkThread();
    var path = wirelessPath(from, to);
    return path == null
        ? 0
        : wirelessBudget.availableHops(path, server.getTickCount(), key.kind(), amount);
  }

  /**
   * Reserve throughput before a synchronous mutation; finish with actual units, never guessed
   * rollback.
   */
  public WirelessBudget.Permit movement(
      NodeRegistry.Position from, NodeRegistry.Position to, ResourceKey key, long amount) {
    checkThread();
    var path = wirelessPath(from, to);
    return path == null
        ? wirelessBudget.denied(OperationResult.Reason.OFFLINE)
        : wirelessBudget.claimHops(path, server.getTickCount(), key.kind(), amount);
  }

  private boolean available(DeviceRegistry.Location location) {
    return membership(new NodeRegistry.Position(location.dimension(), location.position())).status()
        == NetworkTopology.Status.VALID;
  }

  private boolean wired(NodeRegistry.Position position) {
    var block = BlockPos.of(position.block());
    var chunk =
        level(position.dimension())
            .getChunkSource()
            .getChunkNow(block.getX() >> 4, block.getZ() >> 4);
    if (chunk == null) return false;
    var state = chunk.getBlockState(block);
    for (var direction : Direction.values())
      if (NetworkCableBlock.linksTo(state, direction)) return true;
    return false;
  }

  private ServerLevel level(String dimension) {
    var key = Loaded.dimension(dimension);
    var level = server.getLevel(key);
    if (level == null) throw new IllegalStateException("Unknown networking dimension");
    return level;
  }

  private static ChunkKey chunk(NodeRegistry.Position position) {
    var block = BlockPos.of(position.block());
    return new ChunkKey(position.dimension(), block.getX() >> 4, block.getZ() >> 4);
  }

  public static void loaded(ChunkEvent.Load event) {
    chunkChanged(event);
  }

  public static void unloaded(ChunkEvent.Unload event) {
    chunkChanged(event);
  }

  // Minecraft owns the event's borrowed world.
  private static void chunkChanged(ChunkEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
      return;
    if (level.getServer().overworld() == null) return;
    var runtime = get(level.getServer());
    if (runtime.saved == null) return;
    var position = event.getChunk().getPos();
    runtime.invalidateChunk(level.dimension().location().toString(), position.x, position.z);
    // First loading an unindexed chunk can expose a connection to its indexed neighbors.
    runtime.invalidateChunk(level.dimension().location().toString(), position.x - 1, position.z);
    runtime.invalidateChunk(level.dimension().location().toString(), position.x + 1, position.z);
    runtime.invalidateChunk(level.dimension().location().toString(), position.x, position.z - 1);
    runtime.invalidateChunk(level.dimension().location().toString(), position.x, position.z + 1);
    runtime.wake();
  }

  private void invalidateChunk(String dimension, int x, int z) {
    var index = chunks.get(new ChunkKey(dimension, x, z));
    if (index != null) for (var region : index.keySet()) invalidate(resolve(region));
  }

  private static String message(RuntimeException error) {
    String value =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return value.substring(0, Math.min(512, value.length()));
  }

  private void checkThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("World networking requires server thread");
  }
}
