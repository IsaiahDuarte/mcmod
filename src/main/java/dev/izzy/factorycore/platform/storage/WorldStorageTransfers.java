package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

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
      OperationResult.Reason reason, java.util.List<CatalogRow> rows, CatalogCursor next) {
    public CatalogPage {
      rows = java.util.List.copyOf(rows);
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
    if (networks.authorize(actor, origin, NetworkPermissions.Permission.VIEW)
        != NetworkAuthority.Decision.ALLOWED)
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
              || networks.authorize(actor, origin, NetworkPermissions.Permission.VIEW)
                  != NetworkAuthority.Decision.ALLOWED) {
            completion.accept(
                new CatalogPage(OperationResult.Reason.DENIED, java.util.List.of(), null));
            return;
          }
          var rows = new java.util.ArrayList<CatalogRow>();
          var seen = new java.util.HashSet<UUID>();
          CatalogCursor next;
          if (!start.connectors()) {
            var page = saved.registry().handles(start.after(), 8);
            for (var handle : page.handles()) {
              if (!allowed(actor, origin, handle, key, NetworkPermissions.Permission.VIEW))
                continue;
              var stock = saved.registry().stock(handle, key);
              if (seen.add(handle.backing()))
                rows.add(
                    new CatalogRow(
                        handle.backing(),
                        new Digital(handle),
                        stock.total(),
                        stock.reserved(),
                        stock.available(),
                        0,
                        allowed(actor, origin, handle, key, NetworkPermissions.Permission.WITHDRAW),
                        allowed(
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
            completion.accept(
                new CatalogPage(OperationResult.Reason.DENIED, java.util.List.of(), null));
            return;
          }
          rows.removeIf(
              row -> {
                var segment = catalogSegment(row.endpoint());
                return segment == null || !finalView.segments().contains(segment);
              });
          rows.sort(
              java.util.Comparator.comparingInt(CatalogRow::priority)
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
    if (networks.authorize(actor, origin, NetworkPermissions.Permission.VIEW)
        != NetworkAuthority.Decision.ALLOWED)
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
    if (networks.authorize(actor, origin, NetworkPermissions.Permission.VIEW)
        != NetworkAuthority.Decision.ALLOWED)
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
    return allowed(actor, origin, endpoint, key, operation);
  }

  boolean routeTicketActive(UUID id) {
    checkThread();
    var ticket = queued.get(id);
    return id.equals(executing)
        || ticket != null
            && networks.scheduler().isQueued(ticket.network(), ticket.principal(), taskKey(id));
  }

  boolean inExecution() {
    checkThread();
    return executing != null;
  }

  public sealed interface Endpoint permits Digital, External, Buffered {}

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

  public record External(WorldConnectors.Handle handle) implements Endpoint {
    public External {
      Objects.requireNonNull(handle);
    }
  }

  private record Queued(UUID network, UUID segment, UUID principal, int bytes) {}

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final DeviceSavedData saved;
  private final Map<UUID, Queued> queued = new HashMap<>();
  private int queuedBytes;
  private UUID executing;

  public WorldStorageTransfers(MinecraftServer server, WorldNetworks networks) {
    this.server = Objects.requireNonNull(server);
    this.networks = Objects.requireNonNull(networks);
    saved = DeviceSavedData.get(server);
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
    long quantum = key.kind() == ResourceKind.ITEM ? 128 : 1000;
    if (amount > quantum)
      throw new IllegalArgumentException("World transfer exceeds bounded quantum");
    if (source instanceof Buffered || destination instanceof Buffered) {
      if (key.kind() != ResourceKind.ITEM)
        return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
    }
    if (source instanceof External
        || destination instanceof External
        || source instanceof Buffered
        || destination instanceof Buffered) {
      try {
        var codec =
            new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                server.registryAccess());
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
      Consumer<dev.izzy.factorycore.core.resource.OperationResult> completion) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(endpoint);
    Objects.requireNonNull(key);
    Objects.requireNonNull(completion);
    OperationResult.requireQuantity(amount);
    if (amount > (key.kind() == ResourceKind.ITEM ? 128 : 1000))
      throw new IllegalArgumentException("Claim exceeds bounded quantum");
    if (endpoint.kind() != key.kind()
        || !endpoint.connector().world().equals(saved.registry().world()))
      return new Submission(null, OperationResult.Reason.WRONG_RESOURCE);
    if (!releasing) {
      try {
        var codec =
            new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                server.registryAccess());
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
    pruneCancelled();
    int bytes = 1536 + key.componentByteCount() + key.registryId().length();
    if (queued.containsKey(id) || queued.size() >= 256 || queuedBytes > 2 * 1024 * 1024 - bytes)
      return new Submission(
          FairScheduler.Admission.QUEUE_FULL, OperationResult.Reason.TECHNICAL_LIMIT);
    var ticket = new Queued(network, segment, actor.id(), bytes);
    queued.put(id, ticket);
    queuedBytes += bytes;
    var admission =
        networks
            .scheduler()
            .submit(
                network,
                actor.id(),
                taskKey(id),
                16,
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
      dev.izzy.factorycore.core.resource.TransferContext expected,
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
        && (networks.authorize(actor, context.origin(), NetworkPermissions.Permission.WITHDRAW)
                != NetworkAuthority.Decision.ALLOWED
            || networks.authorize(actor, context.origin(), NetworkPermissions.Permission.DEPOSIT)
                != NetworkAuthority.Decision.ALLOWED)) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    Endpoint source =
        returning && context.source().type() == TransferContext.Type.BUFFER
            ? recoverOwnedBufferSource(context.world(), context.source())
            : recover(context.world(), context.source());
    Endpoint destination = returning ? null : recover(context.world(), context.destination());
    if (source == null || !returning && destination == null) {
      completion.accept(new Result(id, OperationResult.Reason.OFFLINE, snapshot));
      return;
    }
    if (!compatible(source, snapshot.key())
        || !returning && !compatible(destination, snapshot.key())) {
      completion.accept(new Result(id, OperationResult.Reason.WRONG_RESOURCE, snapshot));
      return;
    }
    if (!returning
        && (!allowed(
                actor,
                context.origin(),
                source,
                snapshot.key(),
                NetworkPermissions.Permission.WITHDRAW)
            || !allowed(
                actor,
                context.origin(),
                destination,
                snapshot.key(),
                NetworkPermissions.Permission.DEPOSIT))) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, snapshot));
      return;
    }
    var input = bounded(port(source), snapshot.key());
    var output =
        returning
            ? unavailable(snapshot.destination())
            : bounded(port(destination), snapshot.key());
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
                    && allowed(
                        actor,
                        context.origin(),
                        source,
                        snapshot.key(),
                        NetworkPermissions.Permission.WITHDRAW)
                    && allowed(
                        actor,
                        context.origin(),
                        destination,
                        snapshot.key(),
                        NetworkPermissions.Permission.DEPOSIT));
    var outcome = returning ? saved.transfers().cancel(id) : saved.transfers().deliver(id);
    completion.accept(new Result(id, outcome.reason(), saved.transfers().get(id)));
  }

  private boolean compatible(Endpoint endpoint, ResourceKey key) {
    if (endpoint instanceof Buffered) {
      if (key.kind() != ResourceKind.ITEM) return false;
      try {
        new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(server.registryAccess())
            .itemStack(key, 1);
        return true;
      } catch (RuntimeException unsupported) {
        return false;
      }
    }
    return endpoint instanceof Digital digital
        ? saved.registry().definition(digital.handle()).kind() == key.kind()
        : ((External) endpoint).handle().kind() == key.kind();
  }

  private Endpoint recover(
      UUID world, dev.izzy.factorycore.core.resource.TransferContext.Endpoint reference) {
    try {
      if (reference.type() == dev.izzy.factorycore.core.resource.TransferContext.Type.DIGITAL) {
        var handle = new DeviceRegistry.Handle(world, reference.backing(), reference.generation());
        return physical(handle) == null ? null : new Digital(handle);
      }
      if (reference.type() == dev.izzy.factorycore.core.resource.TransferContext.Type.BUFFER) {
        var endpoint =
            new Buffered(
                new NodeRegistry.Handle(
                    world,
                    reference.device(),
                    reference.generation(),
                    NodeRegistry.Kind.BUFFERED_INTERFACE),
                reference.configuration());
        return physical(endpoint) == null ? null : endpoint;
      }
      var registration =
          networks
              .connectors()
              .recover(
                  new NodeRegistry.Handle(
                      world,
                      reference.device(),
                      reference.generation(),
                      NodeRegistry.Kind.CONNECTOR),
                  reference.configuration(),
                  reference.backing());
      return registration.handle() == null ? null : new External(registration.handle());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  /** Owned returns may survive preset replacement, but never lease/world/physical replacement. */
  private Endpoint recoverOwnedBufferSource(UUID world, TransferContext.Endpoint reference) {
    try {
      var handle =
          new NodeRegistry.Handle(
              world,
              reference.backing(),
              reference.generation(),
              NodeRegistry.Kind.BUFFERED_INTERFACE);
      var holder = physical(new Buffered(handle, reference.configuration()), true);
      return holder == null ? null : new Buffered(handle, holder.reference().configuration());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  private static ResourcePort bounded(ResourcePort delegate, ResourceKey key) {
    long quantum = key.kind() == ResourceKind.ITEM ? 128 : 1000;
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return delegate.backingId();
      }

      @Override
      public long simulateInsert(ResourceKey resource, long amount) {
        return delegate.simulateInsert(resource, Math.min(amount, quantum));
      }

      @Override
      public long insert(ResourceKey resource, long amount) {
        return delegate.insert(resource, Math.min(amount, quantum));
      }

      @Override
      public long extract(ResourceKey resource, long amount) {
        throw new IllegalStateException("Recovery never repeats extraction");
      }
    };
  }

  private static ResourcePort unavailable(UUID backing) {
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return backing;
      }

      @Override
      public long simulateInsert(ResourceKey key, long amount) {
        return 0;
      }

      @Override
      public long insert(ResourceKey key, long amount) {
        return 0;
      }

      @Override
      public long extract(ResourceKey key, long amount) {
        throw new IllegalStateException("Unavailable recovery endpoint cannot extract");
      }
    };
  }

  public boolean cancelQueued(UUID id, NetworkPermissions.Actor actor) {
    checkThread();
    var ticket = queued.get(id);
    if (ticket == null || !ticket.principal().equals(actor.id())) return false;
    networks.scheduler().cancelTask(ticket.network(), ticket.principal(), taskKey(id));
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
          && !networks
              .scheduler()
              .isQueued(ticket.network(), ticket.principal(), taskKey(entry.getKey()))) {
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
        || !allowed(actor, origin, source, key, NetworkPermissions.Permission.WITHDRAW)
        || !allowed(actor, origin, destination, key, NetworkPermissions.Permission.DEPOSIT)) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, null));
      return;
    }
    if (!saved.transfers().canStart(id, key)) {
      completion.accept(new Result(id, OperationResult.Reason.TECHNICAL_LIMIT, null));
      return;
    }
    var input =
        claimed
            ? networks.connectors().claimedPort(((External) source).handle(), actor.id())
            : port(source);
    var output = port(destination);
    if (input.backingId().equals(output.backingId())) {
      completion.accept(new Result(id, OperationResult.Reason.DENIED, null));
      return;
    }
    var admission =
        destination instanceof Digital digital
            ? new Port(digital.handle()).admit(key, amount)
            : destination instanceof Buffered buffer
                ? new BufferPort(buffer).admit(key, amount)
                : networks.connectors().limit(((External) destination).handle(), true, amount);
    if (source instanceof Buffered buffer) {
      var available = new BufferPort(buffer).available(key, amount);
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
                        && allowed(
                            actor, origin, source, key, NetworkPermissions.Permission.WITHDRAW)
                        && allowed(
                            actor, origin, destination, key, NetworkPermissions.Permission.DEPOSIT),
                context(origin, source, destination));
    completion.accept(new Result(id, snapshot.reason(), snapshot));
  }

  private dev.izzy.factorycore.core.resource.TransferContext context(
      NodeRegistry.Position origin, Endpoint source, Endpoint destination) {
    var scope = networks.membership(origin).scope();
    return new dev.izzy.factorycore.core.resource.TransferContext(
        saved.registry().world(),
        scope.network(),
        scope.segment(),
        origin,
        reference(source),
        reference(destination));
  }

  private dev.izzy.factorycore.core.resource.TransferContext.Endpoint reference(Endpoint endpoint) {
    if (endpoint instanceof Buffered buffer)
      return new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
          dev.izzy.factorycore.core.resource.TransferContext.Type.BUFFER,
          buffer.handle().id(),
          buffer.handle().id(),
          buffer.handle().generation(),
          buffer.configuration());
    if (endpoint instanceof Digital digital)
      return new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
          dev.izzy.factorycore.core.resource.TransferContext.Type.DIGITAL,
          digital.handle().backing(),
          digital.handle().backing(),
          digital.handle().generation(),
          0);
    var handle = ((External) endpoint).handle();
    return new dev.izzy.factorycore.core.resource.TransferContext.Endpoint(
        dev.izzy.factorycore.core.resource.TransferContext.Type.EXTERNAL,
        handle.connector().id(),
        handle.backing(),
        handle.connector().generation(),
        handle.generation());
  }

  private ResourcePort port(Endpoint endpoint) {
    if (endpoint instanceof Buffered buffer) return new BufferPort(buffer);
    return endpoint instanceof Digital digital
        ? new Port(digital.handle())
        : networks.connectors().port(((External) endpoint).handle());
  }

  private static final class BufferBudget {
    int tick;
    long inserted;
    long extracted;

    BufferBudget(int tick) {
      this.tick = tick;
    }
  }

  private final java.util.Map<UUID, BufferBudget> bufferBudgets = new java.util.HashMap<>();

  private BufferBudget bufferBudget(UUID backing) {
    int tick = server.getTickCount();
    var budget = bufferBudgets.get(backing);
    if (budget == null) {
      if (bufferBudgets.size() == dev.izzy.factorycore.core.storage.BufferRegistry.MAX_BUFFERS)
        throw new IllegalStateException("Buffer rate metadata limit");
      budget = new BufferBudget(tick);
      bufferBudgets.put(backing, budget);
    } else if (budget.tick != tick) {
      budget.tick = tick;
      budget.inserted = 0;
      budget.extracted = 0;
    }
    return budget;
  }

  private final class BufferPort implements ResourcePort {
    private final Buffered endpoint;
    private final BufferedInterfaceEntity owner;

    BufferPort(Buffered endpoint) {
      this.endpoint = endpoint;
      owner = physical(endpoint);
    }

    private boolean current() {
      try {
        return owner != null && physical(endpoint) == owner;
      } catch (IllegalArgumentException | IllegalStateException unavailable) {
        return false;
      }
    }

    OperationResult available(ResourceKey key, long amount) {
      if (!current()) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      long rate = 64 - bufferBudget(backingId()).extracted;
      if (rate == 0 && amount > 0) return new OperationResult(0, OperationResult.Reason.RATE_LIMIT);
      long available = Math.min(Math.min(rate, amount), owner.inventory().total(key));
      return new OperationResult(
          available,
          amount == 0 || available > 0
              ? OperationResult.Reason.OK
              : OperationResult.Reason.SHORTAGE);
    }

    OperationResult admit(ResourceKey key, long amount) {
      if (!current()) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      long rate = 64 - bufferBudget(backingId()).inserted;
      if (rate == 0 && amount > 0) return new OperationResult(0, OperationResult.Reason.RATE_LIMIT);
      return owner.inventory().admitInsert(key, Math.min(amount, rate));
    }

    @Override
    public UUID backingId() {
      return endpoint.handle().id();
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      return admit(key, amount).amount();
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      if (!current()) return 0;
      var budget = bufferBudget(backingId());
      long accepted = owner.inventory().insert(key, Math.min(amount, 64 - budget.inserted));
      budget.inserted += accepted;
      return accepted;
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      if (!current()) return 0;
      var budget = bufferBudget(backingId());
      long extracted = owner.inventory().extract(key, Math.min(amount, 64 - budget.extracted));
      budget.extracted += extracted;
      return extracted;
    }
  }

  private boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint endpoint,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    if (endpoint instanceof Buffered buffer) return allowed(actor, origin, buffer, key, operation);
    return endpoint instanceof Digital digital
        ? allowed(actor, origin, digital.handle(), key, operation)
        : networks
            .connectors()
            .allowed(actor, origin, ((External) endpoint).handle(), key, operation);
  }

  private boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Buffered endpoint,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    try {
      var holder = physical(endpoint);
      if (key.kind() != ResourceKind.ITEM || holder == null) return false;
      var target = networks.membership(holder.position());
      var from = networks.membership(origin);
      if (target.status() != NetworkTopology.Status.VALID
          || from.status() != NetworkTopology.Status.VALID
          || !target.scope().network().equals(from.scope().network())) return false;
      var view = networks.storage(actor, origin, key, operation);
      return view.decision() == NetworkAuthority.Decision.ALLOWED
          && view.segments().contains(target.scope().segment());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return false;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  private BufferedInterfaceEntity physical(Buffered endpoint) {
    return physical(endpoint, false);
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns this borrowed level.
  private BufferedInterfaceEntity physical(Buffered endpoint, boolean ownedReturn) {
    var location = saved.nodes().definition(endpoint.handle()).position();
    if (location == null) return null;
    var level =
        server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(location.dimension())));
    if (level == null) return null;
    var position = BlockPos.of(location.block());
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null
        || !(chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK)
            instanceof BufferedInterfaceEntity holder)
        || !endpoint.handle().equals(holder.handle())
        || !ownedReturn && holder.reference().configuration() != endpoint.configuration())
      return null;
    holder.validatePlaced();
    return holder;
  }

  private boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      DeviceRegistry.Handle handle,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    try {
      var definition = saved.registry().definition(handle);
      if (definition.kind() != key.kind()
          || definition.location() == null
          || physical(handle) == null) return false;
      var location = definition.location();
      var target =
          networks.membership(new NodeRegistry.Position(location.dimension(), location.position()));
      var from = networks.membership(origin);
      if (target.status() != NetworkTopology.Status.VALID
          || from.status() != NetworkTopology.Status.VALID
          || !target.scope().network().equals(from.scope().network())) return false;
      var view = networks.storage(actor, origin, key, operation);
      return view.decision() == NetworkAuthority.Decision.ALLOWED
          && view.segments().contains(target.scope().segment());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return false;
    }
  }

  @SuppressWarnings("PMD.CloseResource")
  private net.minecraft.world.level.block.entity.BlockEntity physical(
      DeviceRegistry.Handle handle) {
    var location = saved.registry().definition(handle).location();
    if (location == null) return null;
    var dimension =
        net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse(location.dimension()));
    ServerLevel level = server.getLevel(dimension);
    if (level == null) return null;
    var position = BlockPos.of(location.position());
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null) return null;
    var entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
    if (entity == null || entity.isRemoved()) return null;
    if (entity instanceof StorageDriveEntity drive
        && drive.diagnostic().isEmpty()
        && location.slot() < 4) {
      var stack = drive.cell(location.slot());
      return !stack.isEmpty() && handle.equals(CellHandleData.read(stack)) ? drive : null;
    }
    if (entity instanceof EnergyBankEntity bank
        && location.slot() == 0
        && handle.equals(bank.handle())) {
      bank.validatePlaced();
      return bank;
    }
    return null;
  }

  private final class Port implements ResourcePort {
    private final DeviceRegistry.Handle handle;

    Port(DeviceRegistry.Handle handle) {
      this.handle = handle;
    }

    @Override
    public UUID backingId() {
      return handle.backing();
    }

    OperationResult admit(ResourceKey key, long amount) {
      var entity = physical(handle);
      if (entity == null) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      return entity instanceof EnergyBankEntity bank
          ? saved.energyBanks().receive(handle, bank.tier(), amount, true)
          : saved.registry().simulateInsert(handle, key, amount);
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      return admit(key, amount).amount();
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      var entity = physical(handle);
      if (entity == null || entity instanceof EnergyBankEntity && !key.equals(ResourceKey.ENERGY))
        return 0;
      return (entity instanceof EnergyBankEntity bank
              ? saved.energyBanks().receive(handle, bank.tier(), amount, false)
              : saved.registry().insert(handle, key, amount))
          .amount();
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      var entity = physical(handle);
      if (entity == null || entity instanceof EnergyBankEntity && !key.equals(ResourceKey.ENERGY))
        return 0;
      return (entity instanceof EnergyBankEntity bank
              ? saved.energyBanks().extract(handle, bank.tier(), amount, false)
              : saved.registry().extract(handle, key, amount, null))
          .amount();
    }
  }

  private void checkThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("World transfers require server thread");
  }
}
