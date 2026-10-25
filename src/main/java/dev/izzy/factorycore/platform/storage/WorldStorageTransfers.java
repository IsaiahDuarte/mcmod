package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.LegacySettlement;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared scheduled digital/registered-foreign moves; client authentication stays at the boundary.
 */
public final class WorldStorageTransfers {
  public record Submission(FairScheduler.Admission admission, OperationResult.Reason rejection) {}

  public record Result(UUID id, OperationResult.Reason reason, StagedTransfer.Snapshot transfer) {}

  /** Server-only cursor over registry metadata; callers must not expose raw cursors to clients. */
  public record CatalogCursor(
      boolean connectors, UUID after, UUID network, UUID segment, UUID principal, ResourceKey key) {
    public CatalogCursor {
      Objects.requireNonNull(network);
      Objects.requireNonNull(segment);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(key);
    }
  }

  public record CatalogRow(
      UUID backing,
      Endpoint endpoint,
      long total,
      long reserved,
      long available,
      int priority,
      boolean withdrawable,
      boolean depositable) {}

  public record CatalogPage(
      OperationResult.Reason reason, List<CatalogRow> rows, CatalogCursor next) {
    public CatalogPage {
      rows = List.copyOf(rows);
    }
  }

  /** Discover one bounded live exact-key storage page through current VIEW authority. */
  public Submission catalog(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKey key,
      CatalogCursor cursor,
      Consumer<CatalogPage> completion) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(key);
    Objects.requireNonNull(completion);
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    if (!networks.allows(actor, origin, NetworkPermissions.Permission.VIEW))
      return new Submission(null, OperationResult.Reason.DENIED);
    var start =
        cursor == null
            ? new CatalogCursor(
                false, null, member.scope().network(), member.scope().segment(), actor.id(), key)
            : cursor;
    if (!start.network().equals(member.scope().network())
        || !start.segment().equals(member.scope().segment())
        || !start.principal().equals(actor.id())
        || !start.key().equals(key)) return new Submission(null, OperationResult.Reason.DENIED);
    return enqueue(
        id,
        actor,
        member.scope().network(),
        member.scope().segment(),
        key,
        ticket -> {
          var current = networks.membership(origin);
          if (current.status() != NetworkTopology.Status.VALID
              || !current.scope().network().equals(ticket.network())
              || !current.scope().segment().equals(ticket.segment())
              || !networks.allows(actor, origin, NetworkPermissions.Permission.VIEW)) {
            completion.accept(new CatalogPage(OperationResult.Reason.DENIED, List.of(), null));
            return;
          }
          var rows = new ArrayList<CatalogRow>();
          var seen = new HashSet<UUID>();
          CatalogCursor next;
          if (!start.connectors()) {
            var page = saved.registry().handles(start.after(), 8);
            for (var handle : page.handles()) {
              if (!endpoints.allowed(
                  actor, origin, handle, key, NetworkPermissions.Permission.VIEW)) continue;
              var stock = saved.registry().stock(handle, key);
              if (seen.add(handle.backing()))
                rows.add(
                    new CatalogRow(
                        handle.backing(),
                        new Digital(handle),
                        stock.total(),
                        stock.reserved(),
                        stock.available(),
                        endpoints.priority(handle),
                        endpoints.allowed(
                            actor, origin, handle, key, NetworkPermissions.Permission.WITHDRAW),
                        endpoints.allowed(
                            actor, origin, handle, key, NetworkPermissions.Permission.DEPOSIT)));
            }
            next =
                page.next() == null
                    ? new CatalogCursor(
                        true,
                        null,
                        start.network(),
                        start.segment(),
                        start.principal(),
                        start.key())
                    : new CatalogCursor(
                        false,
                        page.next(),
                        start.network(),
                        start.segment(),
                        start.principal(),
                        start.key());
          } else {
            var page = saved.nodes().handles(start.after(), 8);
            for (var node : page.handles()) {
              if (node.kind() != NodeRegistry.Kind.CONNECTOR) continue;
              var registration = networks.connectors().discover(actor, origin, node, key);
              var handle = registration.handle();
              if (handle == null || !seen.add(handle.backing())) continue;
              int priority;
              try {
                priority = networks.connectors().priority(handle);
              } catch (IllegalStateException stale) {
                continue;
              }
              var stock = networks.connectors().stock(handle, key);
              if (stock.reason() != OperationResult.Reason.OK
                  || !networks
                      .connectors()
                      .allowed(actor, origin, handle, key, NetworkPermissions.Permission.VIEW))
                continue;
              long reserved = saved.externalClaims().reserved(handle.backing(), key);
              long available =
                  saved.externalClaims().available(handle.backing(), null, key, stock.amount());
              rows.add(
                  new CatalogRow(
                      handle.backing(),
                      new External(handle),
                      stock.amount(),
                      reserved,
                      available,
                      priority,
                      networks
                          .connectors()
                          .allowed(
                              actor, origin, handle, key, NetworkPermissions.Permission.WITHDRAW),
                      networks
                          .connectors()
                          .allowed(
                              actor, origin, handle, key, NetworkPermissions.Permission.DEPOSIT)));
            }
            next =
                page.next() == null
                    ? null
                    : new CatalogCursor(
                        true,
                        page.next(),
                        start.network(),
                        start.segment(),
                        start.principal(),
                        start.key());
          }
          var finalMember = networks.membership(origin);
          var finalView = networks.storage(actor, origin, key, NetworkPermissions.Permission.VIEW);
          if (finalMember.status() != NetworkTopology.Status.VALID
              || !finalMember.scope().network().equals(ticket.network())
              || !finalMember.scope().segment().equals(ticket.segment())
              || finalView.decision() != NetworkAuthority.Decision.ALLOWED) {
            completion.accept(new CatalogPage(OperationResult.Reason.DENIED, List.of(), null));
            return;
          }
          rows.removeIf(
              row -> {
                var segment = catalogSegment(row.endpoint());
                return segment == null || !finalView.segments().contains(segment);
              });
          rows.sort(
              Comparator.comparingInt(CatalogRow::priority)
                  .reversed()
                  .thenComparing(CatalogRow::backing));
          completion.accept(new CatalogPage(OperationResult.Reason.OK, rows, next));
        });
  }

  private UUID catalogSegment(Endpoint endpoint) {
    try {
      NodeRegistry.Position position;
      if (endpoint instanceof Digital digital) {
        var location = saved.registry().definition(digital.handle()).location();
        if (location == null) return null;
        position = new NodeRegistry.Position(location.dimension(), location.position());
      } else
        position = saved.nodes().definition(((External) endpoint).handle().connector()).position();
      if (position == null) return null;
      var member = networks.membership(position);
      return member.status() == NetworkTopology.Status.VALID ? member.scope().segment() : null;
    } catch (IllegalArgumentException | IllegalStateException stale) {
      return null;
    }
  }

  /** Internal read-only queries share transfer admission/credits and authenticated scope. */
  Submission query(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKind kind,
      Runnable action) {
    checkThread();
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    if (!networks.allows(actor, origin, NetworkPermissions.Permission.VIEW))
      return new Submission(null, OperationResult.Reason.DENIED);
    return enqueue(
        id,
        actor,
        member.scope().network(),
        member.scope().segment(),
        WorldStorageCatalog.identity(kind),
        ticket -> action.run());
  }

  /** Queue one advisory foreign stock page using the shared authority and credit budget. */
  public Submission observe(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldConnectors.Handle endpoint,
      int cursor,
      Consumer<WorldConnectors.Observation> completion) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(endpoint);
    Objects.requireNonNull(completion);
    if (cursor < 0) throw new IllegalArgumentException("Negative stock cursor");
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    if (!networks.allows(actor, origin, NetworkPermissions.Permission.VIEW))
      return new Submission(null, OperationResult.Reason.DENIED);
    return enqueue(
        id,
        actor,
        member.scope().network(),
        member.scope().segment(),
        ResourceKey.ENERGY,
        ticket -> {
          var current = networks.membership(origin);
          if (current.status() != NetworkTopology.Status.VALID
              || !current.scope().network().equals(ticket.network())
              || !current.scope().segment().equals(ticket.segment())) {
            completion.accept(new WorldConnectors.Observation(OperationResult.Reason.DENIED, null));
            return;
          }
          completion.accept(networks.connectors().observe(actor, origin, endpoint, cursor));
        });
  }

  private WorldStorageRoutes routes;
  private WorldStorageCatalog catalog;
  private WorldStorageSearch search;

  public WorldStorageSearch search() {
    checkThread();
    if (search == null) search = new WorldStorageSearch(server, networks, this);
    return search;
  }

  public WorldStorageCatalog allKeys() {
    checkThread();
    if (catalog == null) catalog = new WorldStorageCatalog(server, networks, this);
    return catalog;
  }

  public WorldStorageRoutes routes() {
    checkThread();
    if (routes == null) routes = new WorldStorageRoutes(server, networks, this);
    return routes;
  }

  boolean routeAllowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint endpoint,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    return endpoints.allowed(actor, origin, endpoint, key, operation);
  }

  boolean routeTicketActive(UUID id) {
    checkThread();
    var ticket = queued.get(id);
    return id.equals(executing)
        || ticket != null
            && networks.scheduler().isQueued(ticket.network(), ticket.principal(), ticket.key());
  }

  boolean inExecution() {
    checkThread();
    return executing != null;
  }

  public sealed interface Endpoint permits Digital, External, Buffered, PlayerInventory {}

  /** Own slots 0–35, whole-inventory destination -1, or cursor 36 bound to menuSession. */
  public record PlayerInventory(
      UUID principal, UUID backing, long connection, int slot, UUID menuSession)
      implements Endpoint {
    public PlayerInventory(UUID principal, UUID backing, long connection, int slot) {
      this(principal, backing, connection, slot, null);
    }

    public PlayerInventory {
      Objects.requireNonNull(principal);
      Objects.requireNonNull(backing);
      if (connection <= 0 || slot < -1 || slot > 36 || (slot == 36) != (menuSession != null))
        throw new IllegalArgumentException("Invalid player inventory endpoint");
    }
  }

  /** Registers (or clears, for 8 blocks or less) a wireless session's reach to {@code origin}. */
  void playerReach(ServerPlayer player, NodeRegistry.Position origin, double squared) {
    players().reach(player, origin, squared);
  }

  private WorldPlayerInventories players() {
    return endpoints.players();
  }

  public PlayerInventory inventory(ServerPlayer player, int slot) {
    checkThread();
    return players().endpoint(player, slot);
  }

  public void playerDisconnected(ServerPlayer player) {
    checkThread();
    endpoints.disconnected(player);
  }

  public record Buffered(NodeRegistry.Handle handle, long configuration) implements Endpoint {
    public Buffered {
      Objects.requireNonNull(handle);
      if (handle.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE || configuration <= 0)
        throw new IllegalArgumentException("Invalid buffer endpoint");
    }
  }

  public record Digital(DeviceRegistry.Handle handle) implements Endpoint {
    public Digital {
      Objects.requireNonNull(handle);
    }
  }

  /**
   * A bound machine; {@code view} (D3, ADR 0062) optionally narrows the face and slots for this
   * transfer. A transfer resumed from its saved context uses the Interface's own binding.
   */
  public record External(WorldConnectors.Handle handle, WorldConnectors.View view)
      implements Endpoint {
    public External(WorldConnectors.Handle handle) {
      this(handle, null);
    }

    public External {
      Objects.requireNonNull(handle);
    }
  }

  /** {@code key} is the scheduler task key, built once per queued transfer. */
  private record Queued(UUID network, UUID segment, UUID principal, int bytes, String key) {}

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final DeviceSavedData saved;
  private final TransferEndpoints endpoints;
  private final Map<UUID, Queued> queued = new HashMap<>();
  private int queuedBytes;
  private UUID executing;

  public WorldStorageTransfers(MinecraftServer server, WorldNetworks networks) {
    this.server = Objects.requireNonNull(server);
    this.networks = Objects.requireNonNull(networks);
    saved = DeviceSavedData.get(server);
    endpoints = new TransferEndpoints(server, networks, saved);
  }

  public Submission submit(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      DeviceRegistry.Handle source,
      DeviceRegistry.Handle destination,
      ResourceKey key,
      long amount,
      Consumer<Result> completion) {
    return submit(
        id, actor, origin, new Digital(source), new Digital(destination), key, amount, completion);
  }

  public Submission submit(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint source,
      Endpoint destination,
      ResourceKey key,
      long amount,
      Consumer<Result> completion) {
    return submit(
        id,
        actor,
        origin,
        source,
        destination,
        key,
        amount,
        completion,
        false,
        () -> true,
        () -> true);
  }

  public Submission submitClaimed(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldConnectors.Handle source,
      Endpoint destination,
      ResourceKey key,
      long amount,
      Consumer<Result> completion) {
    return submit(
        id,
        actor,
        origin,
        new External(source),
        destination,
        key,
        amount,
        completion,
        true,
        () -> true,
        () -> true);
  }

  Submission submitGuarded(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint source,
      Endpoint destination,
      ResourceKey key,
      long amount,
      Consumer<Result> completion,
      BooleanSupplier authorized,
      BooleanSupplier canStart) {
    return submit(
        id,
        actor,
        origin,
        source,
        destination,
        key,
        amount,
        completion,
        false,
        authorized,
        canStart);
  }

  private Submission submit(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint source,
      Endpoint destination,
      ResourceKey key,
      long amount,
      Consumer<Result> completion,
      boolean claimed,
      BooleanSupplier authorized,
      BooleanSupplier canStart) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(source);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(key);
    Objects.requireNonNull(completion);
    Objects.requireNonNull(authorized);
    if (!authorized.getAsBoolean()) return new Submission(null, OperationResult.Reason.DENIED);
    OperationResult.requireQuantity(amount);
    if (amount > ThroughputTier.maxQuantum(key.kind()))
      throw new IllegalArgumentException("World transfer exceeds bounded quantum");
    if (source instanceof Buffered
        || destination instanceof Buffered
        || source instanceof PlayerInventory
        || destination instanceof PlayerInventory) {
      if (key.kind() != ResourceKind.ITEM)
        return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
    }
    if (source instanceof External
        || destination instanceof External
        || source instanceof Buffered
        || destination instanceof Buffered
        || source instanceof PlayerInventory
        || destination instanceof PlayerInventory) {
      try {
        var codec = new PlatformResourceCodec(server.registryAccess());
        if (key.kind() == ResourceKind.ITEM) codec.itemStack(key, 1);
        else if (key.kind() == ResourceKind.FLUID) codec.fluidStack(key, 1);
      } catch (RuntimeException invalidIdentity) {
        return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
      }
    }
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    return enqueue(
        id,
        actor,
        member.scope().network(),
        member.scope().segment(),
        key,
        ThroughputTier.credits(key.kind(), amount),
        ticket ->
            execute(
                id,
                actor,
                origin,
                source,
                destination,
                key,
                amount,
                completion,
                ticket,
                claimed,
                authorized,
                canStart));
  }

  /** Reserve or release only this authenticated principal's foreign holds. */
  public Submission claim(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldConnectors.Handle endpoint,
      ResourceKey key,
      long amount,
      boolean releasing,
      Consumer<OperationResult> completion) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(endpoint);
    Objects.requireNonNull(key);
    Objects.requireNonNull(completion);
    OperationResult.requireQuantity(amount);
    if (amount > ThroughputTier.maxQuantum(key.kind()))
      throw new IllegalArgumentException("Claim exceeds bounded quantum");
    if (endpoint.kind() != key.kind()
        || !endpoint.connector().world().equals(saved.registry().world()))
      return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
    if (!releasing) {
      try {
        var codec = new PlatformResourceCodec(server.registryAccess());
        if (key.kind() == ResourceKind.ITEM) codec.itemStack(key, 1);
        else if (key.kind() == ResourceKind.FLUID) codec.fluidStack(key, 1);
      } catch (RuntimeException invalid) {
        return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
      }
    }
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    return enqueue(
        id,
        actor,
        member.scope().network(),
        member.scope().segment(),
        key,
        ticket -> {
          var current = networks.membership(origin);
          if (current.status() != NetworkTopology.Status.VALID
              || !current.scope().network().equals(ticket.network())
              || !current.scope().segment().equals(ticket.segment())
              || networks.connectors().inProviderCall()) {
            completion.accept(new OperationResult(0, OperationResult.Reason.DENIED));
            return;
          }
          if (releasing) {
            if (saved.transfers().uncertain(endpoint.backing(), actor.id(), key)) {
              completion.accept(new OperationResult(0, OperationResult.Reason.UNCERTAIN));
              return;
            }
            long held = saved.externalClaims().held(endpoint.backing(), actor.id(), key);
            long released = Math.min(amount, held);
            saved.externalClaims().consume(endpoint.backing(), actor.id(), key, released);
            completion.accept(new OperationResult(released, OperationResult.Reason.OK));
            return;
          }
          if (!networks
              .connectors()
              .allowed(actor, origin, endpoint, key, NetworkPermissions.Permission.WITHDRAW)) {
            completion.accept(new OperationResult(0, OperationResult.Reason.DENIED));
            return;
          }
          var observed = networks.connectors().stock(endpoint, key);
          if (observed.reason() != OperationResult.Reason.OK) {
            completion.accept(observed);
            return;
          }
          if (!networks
              .connectors()
              .allowed(actor, origin, endpoint, key, NetworkPermissions.Permission.WITHDRAW)) {
            completion.accept(new OperationResult(0, OperationResult.Reason.DENIED));
            return;
          }
          completion.accept(
              saved
                  .externalClaims()
                  .reserve(endpoint.backing(), actor.id(), key, amount, observed.amount()));
        });
  }

  private Submission enqueue(
      UUID id,
      NetworkPermissions.Actor actor,
      UUID network,
      UUID segment,
      ResourceKey key,
      Consumer<Queued> action) {
    return enqueue(id, actor, network, segment, key, ThroughputTier.CREDITS_PER_QUANTUM, action);
  }

  /**
   * Upgraded requests larger than one base quantum pay proportionally more shared credits (ADR
   * 0054); the scheduler's per-principal, per-network and global limits never change.
   */
  private Submission enqueue(
      UUID id,
      NetworkPermissions.Actor actor,
      UUID network,
      UUID segment,
      ResourceKey key,
      int cost,
      Consumer<Queued> action) {
    pruneCancelled();
    int bytes = 1536 + key.componentByteCount() + key.registryId().length();
    if (queued.containsKey(id) || queued.size() >= 256 || queuedBytes > 2 * 1024 * 1024 - bytes)
      return new Submission(
          FairScheduler.Admission.QUEUE_FULL, OperationResult.Reason.TECHNICAL_LIMIT);
    var ticket = new Queued(network, segment, actor.id(), bytes, taskKey(id));
    queued.put(id, ticket);
    queuedBytes += bytes;
    var admission =
        networks
            .scheduler()
            .submit(
                network,
                actor.id(),
                ticket.key(),
                cost,
                () -> {
                  executing = id;
                  try {
                    action.accept(ticket);
                  } finally {
                    release(id);
                    executing = null;
                  }
                });
    boolean accepted =
        admission == FairScheduler.Admission.ACCEPTED
            || admission == FairScheduler.Admission.REPLACED;
    if (!accepted) release(id);
    return new Submission(
        admission, accepted ? OperationResult.Reason.OK : OperationResult.Reason.TECHNICAL_LIMIT);
  }

  /** Progress only existing owned staging. Caller supplies a currently authenticated actor. */
  public Submission progress(
      UUID id, NetworkPermissions.Actor actor, boolean returning, Consumer<Result> completion) {
    return progressGuarded(id, actor, returning, completion, () -> true);
  }

  Submission progressGuarded(
      UUID id,
      NetworkPermissions.Actor actor,
      boolean returning,
      Consumer<Result> completion,
      BooleanSupplier authorized) {
    checkThread();
    Objects.requireNonNull(authorized);
    if (!authorized.getAsBoolean()) return new Submission(null, OperationResult.Reason.DENIED);
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(completion);
    if (!saved.transfers().contains(id) || !saved.transfers().get(id).owner().equals(actor.id()))
      return new Submission(null, OperationResult.Reason.DENIED);
    var snapshot = saved.transfers().get(id);
    if (snapshot.state() == StagedTransfer.State.QUARANTINED
        || snapshot.pending() != StagedTransfer.PendingCall.NONE)
      return new Submission(null, OperationResult.Reason.UNCERTAIN);
    var context = saved.transfers().context(id);
    if (context == null) return new Submission(null, OperationResult.Reason.UNSUPPORTED);
    var member = networks.membership(context.origin());
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    if (!member.scope().network().equals(context.network())
        || !member.scope().segment().equals(context.segment()))
      return new Submission(null, OperationResult.Reason.DENIED);
    return enqueue(
        id,
        actor,
        context.network(),
        context.segment(),
        snapshot.key(),
        ticket -> executeProgress(id, actor, context, returning, completion, authorized));
  }

  private void executeProgress(
      UUID id,
      NetworkPermissions.Actor actor,
      TransferContext expected,
      boolean returning,
      Consumer<Result> completion,
      BooleanSupplier authorized) {
    if (!authorized.getAsBoolean()
        || !saved.transfers().contains(id)
        || !expected.equals(saved.transfers().context(id))) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, null));
      return;
    }
    var snapshot = saved.transfers().get(id);
    var context = saved.transfers().context(id);
    var current = networks.membership(context.origin());
    if (!snapshot.owner().equals(actor.id())
        || current.status() != NetworkTopology.Status.VALID
        || !current.scope().network().equals(context.network())
        || !current.scope().segment().equals(context.segment())) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    if (snapshot.state() == StagedTransfer.State.QUARANTINED
        || snapshot.pending() != StagedTransfer.PendingCall.NONE) {
      completion.accept(new Result(id, OperationResult.Reason.UNCERTAIN, snapshot));
      return;
    }
    if (snapshot.state() == StagedTransfer.State.COMPLETE) {
      completion.accept(new Result(id, OperationResult.Reason.OK, snapshot));
      return;
    }
    if (!returning
        && (!networks.allows(actor, context.origin(), NetworkPermissions.Permission.WITHDRAW)
            || !networks.allows(actor, context.origin(), NetworkPermissions.Permission.DEPOSIT))) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    Endpoint source =
        context.source().type() == TransferContext.Type.PLAYER
            ? players().recover(context.source(), actor.id())
            : returning && context.source().type() == TransferContext.Type.BUFFER
                ? endpoints.recoverOwnedBufferSource(context.world(), context.source())
                : endpoints.recover(context.world(), context.source());
    Endpoint destination =
        returning
            ? null
            : context.destination().type() == TransferContext.Type.PLAYER
                ? players().recover(context.destination(), actor.id())
                : endpoints.recover(context.world(), context.destination());
    if (source == null || !returning && destination == null) {
      completion.accept(new Result(id, OperationResult.Reason.OFFLINE, snapshot));
      return;
    }
    if (!endpoints.compatible(source, snapshot.key())
        || !returning && !endpoints.compatible(destination, snapshot.key())) {
      completion.accept(new Result(id, OperationResult.Reason.WRONG_RESOURCE, snapshot));
      return;
    }
    if (returning
        && source instanceof PlayerInventory player
        && !players()
            .allowed(actor, context.origin(), player, NetworkPermissions.Permission.DEPOSIT)) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    if (!returning
        && (!endpoints.allowed(
                actor,
                context.origin(),
                source,
                snapshot.key(),
                NetworkPermissions.Permission.WITHDRAW)
            || !endpoints.allowed(
                actor,
                context.origin(),
                destination,
                snapshot.key(),
                NetworkPermissions.Permission.DEPOSIT))) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    var input =
        TransferEndpoints.bounded(
            returning && source instanceof Buffered buffer
                ? endpoints.routedPort(source, context.origin(), endpoints.bufferPort(buffer, true))
                : returning && source instanceof External external
                    ? endpoints.routedPort(
                        source,
                        context.origin(),
                        networks.connectors().returnPort(external.handle()))
                    : endpoints.port(source, context.origin()),
            snapshot.key());
    var output =
        returning
            ? TransferEndpoints.unavailable(snapshot.destination())
            : TransferEndpoints.bounded(
                endpoints.port(destination, context.origin()), snapshot.key());
    saved.transfers().unbind(id);
    saved
        .transfers()
        .bind(
            id,
            input,
            output,
            () ->
                !returning
                    && authorized.getAsBoolean()
                    && endpoints.allowed(
                        actor,
                        context.origin(),
                        source,
                        snapshot.key(),
                        NetworkPermissions.Permission.WITHDRAW)
                    && endpoints.allowed(
                        actor,
                        context.origin(),
                        destination,
                        snapshot.key(),
                        NetworkPermissions.Permission.DEPOSIT));
    var outcome = returning ? saved.transfers().cancel(id) : saved.transfers().deliver(id);
    completion.accept(new Result(id, outcome.reason(), saved.transfers().get(id)));
  }

  /**
   * Legacy settlement (ADR 0047): finishes the known staging of a record saved without endpoint
   * context, for its authenticated original owner only. If the source backing is a canonical
   * Factory Core identity (a cell/bank ledger or a Buffer-mode Interface), staging returns there
   * under the existing owned-cleanup exemption; otherwise, if the destination backing is a
   * canonical cell or bank, staging is delivered there only while the owner currently has DEPOSIT
   * access to it. Foreign/unknown backings are never guessed (UNSUPPORTED). It never extracts;
   * pending or quarantined records reject UNCERTAIN; steps are bounded by the recovery quantum and
   * scheduled on the target holder's current network.
   */
  public Submission settleLegacy(
      UUID id, NetworkPermissions.Actor actor, Consumer<Result> completion) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(completion);
    var rejection = legacyAdmission(id, actor);
    if (rejection != null) return new Submission(null, rejection);
    var snapshot = saved.transfers().get(id);
    var target = legacyTarget(snapshot);
    if (target == null) return new Submission(null, OperationResult.Reason.UNSUPPORTED);
    var endpoint = legacyEndpoint(target.backing());
    if (endpoint == null) return new Submission(null, OperationResult.Reason.OFFLINE);
    if (!endpoints.compatible(endpoint, snapshot.key()))
      return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
    var position = holderPosition(endpoint);
    var member = networks.membership(position);
    if (member.status() != NetworkTopology.Status.VALID)
      return new Submission(null, OperationResult.Reason.OFFLINE);
    if (!target.returning()
        && !endpoints.allowed(
            actor, position, endpoint, snapshot.key(), NetworkPermissions.Permission.DEPOSIT))
      return new Submission(null, OperationResult.Reason.DENIED);
    return enqueue(
        id,
        actor,
        member.scope().network(),
        member.scope().segment(),
        snapshot.key(),
        ticket -> executeLegacy(id, actor, target, ticket, completion));
  }

  private OperationResult.Reason legacyAdmission(UUID id, NetworkPermissions.Actor actor) {
    if (!saved.transfers().contains(id) || !saved.transfers().get(id).owner().equals(actor.id()))
      return OperationResult.Reason.DENIED;
    var snapshot = saved.transfers().get(id);
    if (snapshot.state() == StagedTransfer.State.QUARANTINED
        || snapshot.pending() != StagedTransfer.PendingCall.NONE)
      return OperationResult.Reason.UNCERTAIN;
    if (saved.transfers().context(id) != null || snapshot.state() == StagedTransfer.State.COMPLETE)
      return OperationResult.Reason.UNSUPPORTED;
    return null;
  }

  private LegacySettlement legacyTarget(StagedTransfer.Snapshot snapshot) {
    return LegacySettlement.of(
        snapshot,
        backing -> {
          if (saved.registry().containsBacking(backing)) return true;
          var node = saved.nodes().evidence(backing);
          return node != null && node.kind() == NodeRegistry.Kind.BUFFERED_INTERFACE;
        },
        saved.registry()::containsBacking);
  }

  /** Current loaded, unconflicted holder of a canonical backing, or null while unavailable. */
  private Endpoint legacyEndpoint(UUID backing) {
    try {
      var device = saved.registry().ownership(backing);
      if (device != null)
        return device.conflict() || endpoints.physical(device.handle()) == null
            ? null
            : new Digital(device.handle());
      var node = saved.nodes().evidence(backing);
      if (node == null || node.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE || node.conflict())
        return null;
      var handle =
          new NodeRegistry.Handle(
              saved.registry().world(), backing, node.generation(), node.kind());
      var holder = endpoints.physical(new Buffered(handle, 1), true);
      return holder == null ? null : new Buffered(handle, holder.reference().configuration());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  private NodeRegistry.Position holderPosition(Endpoint endpoint) {
    if (endpoint instanceof Buffered buffer)
      return saved.nodes().definition(buffer.handle()).position();
    var location = saved.registry().definition(((Digital) endpoint).handle()).location();
    return new NodeRegistry.Position(location.dimension(), location.position());
  }

  private void executeLegacy(
      UUID id,
      NetworkPermissions.Actor actor,
      LegacySettlement target,
      Queued ticket,
      Consumer<Result> completion) {
    var rejection = legacyAdmission(id, actor);
    var snapshot = saved.transfers().contains(id) ? saved.transfers().get(id) : null;
    if (rejection == null && !target.equals(legacyTarget(snapshot)))
      rejection = OperationResult.Reason.DENIED;
    if (rejection != null) {
      completion.accept(new Result(id, rejection, snapshot));
      return;
    }
    var endpoint = legacyEndpoint(target.backing());
    var position = endpoint == null ? null : holderPosition(endpoint);
    if (endpoint == null
        || networks.membership(position).status() != NetworkTopology.Status.VALID) {
      completion.accept(new Result(id, OperationResult.Reason.OFFLINE, snapshot));
      return;
    }
    var current = networks.membership(position).scope();
    if (!current.network().equals(ticket.network())
        || !current.segment().equals(ticket.segment())) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    var key = snapshot.key();
    if (!endpoints.compatible(endpoint, key)) {
      completion.accept(new Result(id, OperationResult.Reason.WRONG_RESOURCE, snapshot));
      return;
    }
    if (!target.returning()
        && !endpoints.allowed(
            actor, position, endpoint, key, NetworkPermissions.Permission.DEPOSIT)) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    var port =
        TransferEndpoints.bounded(
            endpoint instanceof Buffered buffer
                ? endpoints.bufferPort(buffer, true)
                : endpoints.digitalPort(((Digital) endpoint).handle()),
            key);
    saved.transfers().unbind(id);
    saved
        .transfers()
        .bind(
            id,
            target.returning() ? port : TransferEndpoints.unavailable(snapshot.source()),
            target.returning() ? TransferEndpoints.unavailable(snapshot.destination()) : port,
            () ->
                !target.returning()
                    && endpoints.allowed(
                        actor, position, endpoint, key, NetworkPermissions.Permission.DEPOSIT));
    var outcome = target.returning() ? saved.transfers().cancel(id) : saved.transfers().deliver(id);
    completion.accept(new Result(id, outcome.reason(), saved.transfers().get(id)));
  }

  public boolean cancelQueued(UUID id, NetworkPermissions.Actor actor) {
    checkThread();
    var ticket = queued.get(id);
    if (ticket == null || !ticket.principal().equals(actor.id())) return false;
    networks.scheduler().cancelTask(ticket.network(), ticket.principal(), ticket.key());
    release(id);
    return true;
  }

  private void pruneCancelled() {
    // At most 256 tickets; direct scheduler revocation must not leak admission forever.
    var iterator = queued.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var ticket = entry.getValue();
      if (!entry.getKey().equals(executing)
          && !networks.scheduler().isQueued(ticket.network(), ticket.principal(), ticket.key())) {
        queuedBytes -= ticket.bytes();
        iterator.remove();
      }
    }
  }

  private static String taskKey(UUID id) {
    return "storage-transfer:" + id;
  }

  private void release(UUID id) {
    var ticket = queued.remove(id);
    if (ticket != null) queuedBytes -= ticket.bytes();
  }

  private void execute(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint source,
      Endpoint destination,
      ResourceKey key,
      long amount,
      Consumer<Result> completion,
      Queued ticket,
      boolean claimed,
      BooleanSupplier authorized,
      BooleanSupplier canStart) {
    var current = networks.membership(origin);
    if (current.status() != NetworkTopology.Status.VALID
        || !current.scope().network().equals(ticket.network())
        || !current.scope().segment().equals(ticket.segment())) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, null));
      return;
    }
    if (!authorized.getAsBoolean()
        || !canStart.getAsBoolean()
        || saved.transfers().contains(id)
        || !endpoints.allowed(actor, origin, source, key, NetworkPermissions.Permission.WITHDRAW)
        || !endpoints.allowed(
            actor, origin, destination, key, NetworkPermissions.Permission.DEPOSIT)) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, null));
      return;
    }
    if (!saved.transfers().canStart(id, key)) {
      completion.accept(new Result(id, OperationResult.Reason.TECHNICAL_LIMIT, null));
      return;
    }
    var input =
        claimed
            ? endpoints.routedPort(
                source,
                origin,
                networks.connectors().claimedPort(((External) source).handle(), actor.id()))
            : endpoints.port(source, origin);
    var output = endpoints.port(destination, origin);
    if (input.backingId().equals(output.backingId())) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, null));
      return;
    }
    for (var endpoint : new Endpoint[] {source, destination})
      if (endpoint instanceof PlayerInventory player
          && saved.transfers().unresolved(player.backing()) != null) {
        completion.accept(new Result(id, OperationResult.Reason.RESERVED, null));
        return;
      }
    var admission =
        destination instanceof PlayerInventory player
            ? players().port(player, origin).admit(key, amount)
            : destination instanceof Digital digital
                ? endpoints.digitalPort(digital.handle()).admit(key, amount)
                : destination instanceof Buffered buffer
                    ? endpoints.bufferPort(buffer).admit(key, amount)
                    : networks.connectors().limit(((External) destination).handle(), true, amount);
    if (source instanceof PlayerInventory player) {
      var available = players().port(player, origin).available(key, amount);
      if (available.amount() == 0 && amount > 0) {
        completion.accept(new Result(id, available.reason(), null));
        return;
      }
    }
    if (source instanceof Buffered buffer) {
      var available = endpoints.bufferPort(buffer).available(key, amount);
      if (available.amount() == 0 && amount > 0) {
        completion.accept(new Result(id, available.reason(), null));
        return;
      }
    }
    if (source instanceof External external) {
      var held = saved.externalClaims().reserved(external.handle().backing(), key);
      if (amount > 0 && (claimed || held > 0)) {
        var observed = networks.connectors().stock(external.handle(), key);
        if (observed.reason() != OperationResult.Reason.OK) {
          completion.accept(new Result(id, observed.reason(), null));
          return;
        }
        if (saved
                .externalClaims()
                .available(
                    external.handle().backing(),
                    claimed ? actor.id() : null,
                    key,
                    observed.amount())
            == 0) {
          completion.accept(
              new Result(
                  id,
                  held > 0 ? OperationResult.Reason.RESERVED : OperationResult.Reason.SHORTAGE,
                  null));
          return;
        }
      }
      var available = networks.connectors().limit(external.handle(), false, amount);
      if (available.amount() == 0 && amount > 0) {
        completion.accept(new Result(id, available.reason(), null));
        return;
      }
    }
    if (admission.amount() == 0 && amount > 0) {
      completion.accept(new Result(id, admission.reason(), null));
      return;
    }
    var sourcePosition = endpoints.endpointPosition(source, origin);
    var targetPosition = endpoints.endpointPosition(destination, origin);
    if (sourcePosition == null || targetPosition == null) {
      completion.accept(new Result(id, OperationResult.Reason.OFFLINE, null));
      return;
    }
    if (amount > 0
        && (networks.movementAvailable(sourcePosition, origin, key, amount) == 0
            || networks.movementAvailable(origin, targetPosition, key, amount) == 0)) {
      completion.accept(new Result(id, OperationResult.Reason.RATE_LIMIT, null));
      return;
    }
    var snapshot =
        saved
            .transfers()
            .start(
                id,
                actor.id(),
                key,
                input,
                output,
                amount,
                amount,
                () ->
                    authorized.getAsBoolean()
                        && endpoints.allowed(
                            actor, origin, source, key, NetworkPermissions.Permission.WITHDRAW)
                        && endpoints.allowed(
                            actor, origin, destination, key, NetworkPermissions.Permission.DEPOSIT),
                endpoints.context(origin, source, destination));
    completion.accept(new Result(id, snapshot.reason(), snapshot));
  }

  /** Trusted scope check for a digital cell; grants nothing beyond the named operation. */
  public boolean digitalAllowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      DeviceRegistry.Handle handle,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    checkThread();
    return endpoints.allowed(actor, origin, handle, key, operation);
  }

  private void checkThread() {
    ServerThread.require(server, "World transfers");
  }
}
