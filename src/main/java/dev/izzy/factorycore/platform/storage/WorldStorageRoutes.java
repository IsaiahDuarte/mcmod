package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.storage.PriorityOrder;
import dev.izzy.factorycore.core.storage.RouteCursors;
import dev.izzy.factorycore.core.storage.StableRotation;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * Server-owned bounded priority routes; every discovery/move uses the shared transfer scheduler.
 */
public final class WorldStorageRoutes {
  public enum Direction {
    TO_STORAGE,
    FROM_STORAGE
  }

  public record Result(UUID request, WorldStorageTransfers.Result move) {}

  public static final int MAX_REQUESTS = 32;
  public static final long MAX_BYTES = 2 * 1024 * 1024;
  private static final long CANDIDATE_BYTES = 384;

  private static final class Request {
    final UUID id;
    final NetworkPermissions.Actor actor;
    final NodeRegistry.Position origin;
    final WorldStorageTransfers.Endpoint fixed;
    final ResourceKey key;
    final long amount;
    final Direction direction;
    final Consumer<Result> completion;
    final BooleanSupplier authorized;
    final BooleanSupplier canStart;
    final UUID network;
    final UUID segment;
    final RouteCursors.Scope cursorScope;
    final PriorityOrder order = new PriorityOrder();
    final StableRotation rotation = new StableRotation();
    final Map<UUID, WorldStorageTransfers.CatalogRow> rows = new HashMap<>();
    UUID ticket;
    int pages;
    long bytes;
    OperationResult.Reason last = OperationResult.Reason.SHORTAGE;

    Request(
        UUID id,
        NetworkPermissions.Actor actor,
        NodeRegistry.Position origin,
        WorldStorageTransfers.Endpoint fixed,
        ResourceKey key,
        long amount,
        Direction direction,
        Consumer<Result> completion,
        UUID network,
        UUID segment,
        UUID deployment,
        BooleanSupplier authorized,
        BooleanSupplier canStart) {
      this.authorized = authorized;
      this.canStart = canStart;
      this.id = id;
      this.actor = actor;
      this.origin = origin;
      this.fixed = fixed;
      this.key = key;
      this.amount = amount;
      this.direction = direction;
      this.completion = completion;
      this.network = network;
      this.segment = segment;
      cursorScope =
          deployment == null
              ? null
              : new RouteCursors.Scope(
                  network,
                  segment,
                  actor.id(),
                  deployment,
                  backing(fixed),
                  key.kind(),
                  direction == Direction.TO_STORAGE);
      bytes = 2048 + key.componentByteCount() + 3L * key.registryId().length();
    }
  }

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final WorldStorageTransfers transfers;
  private final DeviceSavedData saved;
  private final Map<UUID, Request> requests = new HashMap<>();
  private long bytes;

  WorldStorageRoutes(
      MinecraftServer server, WorldNetworks networks, WorldStorageTransfers transfers) {
    this.server = server;
    this.networks = networks;
    this.transfers = transfers;
    saved = DeviceSavedData.get(server);
  }

  /** Explicit priority mode. Request IDs are ephemeral; successful move IDs are durable records. */
  public WorldStorageTransfers.Submission priority(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldStorageTransfers.Endpoint fixed,
      ResourceKey key,
      long amount,
      Direction direction,
      Consumer<Result> completion) {
    return route(
        id, actor, origin, fixed, key, amount, direction, completion, null, () -> true, () -> true);
  }

  /** Explicit rotating mode; deployment IDs must come from authenticated server-owned state. */
  public WorldStorageTransfers.Submission roundRobin(
      UUID id,
      UUID deployment,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldStorageTransfers.Endpoint fixed,
      ResourceKey key,
      long amount,
      Direction direction,
      Consumer<Result> completion) {
    return route(
        id,
        actor,
        origin,
        fixed,
        key,
        amount,
        direction,
        completion,
        Objects.requireNonNull(deployment),
        () -> true,
        () -> true);
  }

  WorldStorageTransfers.Submission priorityGuarded(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldStorageTransfers.Endpoint fixed,
      ResourceKey key,
      long amount,
      Direction direction,
      Consumer<Result> completion,
      BooleanSupplier authorized,
      BooleanSupplier canStart) {
    return route(
        id, actor, origin, fixed, key, amount, direction, completion, null, authorized, canStart);
  }

  boolean active(UUID id) {
    checkThread();
    prune();
    return requests.containsKey(id);
  }

  private WorldStorageTransfers.Submission route(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldStorageTransfers.Endpoint fixed,
      ResourceKey key,
      long amount,
      Direction direction,
      Consumer<Result> completion,
      UUID deployment,
      BooleanSupplier authorized,
      BooleanSupplier canStart) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(fixed);
    Objects.requireNonNull(key);
    Objects.requireNonNull(direction);
    Objects.requireNonNull(completion);
    Objects.requireNonNull(authorized);
    OperationResult.requireQuantity(amount);
    if (amount > (key.kind() == ResourceKind.ITEM ? 128 : 1000))
      throw new IllegalArgumentException("Route exceeds bounded quantum");
    prune();
    try {
      var codec = new PlatformResourceCodec(server.registryAccess());
      if (key.kind() == ResourceKind.ITEM) codec.itemStack(key, 1);
      else if (key.kind() == ResourceKind.FLUID) codec.fluidStack(key, 1);
    } catch (RuntimeException invalid) {
      return rejected(OperationResult.Reason.WRONG_RESOURCE);
    }
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return rejected(OperationResult.Reason.OFFLINE);
    var request =
        new Request(
            id,
            actor,
            origin,
            fixed,
            key,
            amount,
            direction,
            completion,
            member.scope().network(),
            member.scope().segment(),
            deployment,
            authorized,
            canStart);
    if (requests.containsKey(id)
        || requests.size() == MAX_REQUESTS
        || bytes > MAX_BYTES - request.bytes)
      return rejected(OperationResult.Reason.TECHNICAL_LIMIT);
    if (request.cursorScope != null && !saved.routeCursors().canTrack(request.cursorScope))
      return rejected(OperationResult.Reason.TECHNICAL_LIMIT);
    if (!scopeValid(request) || !fixedAllowed(request))
      return rejected(OperationResult.Reason.DENIED);
    requests.put(id, request);
    bytes += request.bytes;
    var admission = page(request, null);
    if (admission.rejection() != OperationResult.Reason.OK) drop(request);
    return admission;
  }

  private WorldStorageTransfers.Submission page(
      Request request, WorldStorageTransfers.CatalogCursor cursor) {
    request.ticket = UUID.randomUUID();
    return transfers.catalog(
        request.ticket,
        request.actor,
        request.origin,
        request.key,
        cursor,
        result -> {
          if (!requests.containsKey(request.id)) return;
          if (!scopeValid(request) || !fixedAllowed(request)) {
            finish(request, OperationResult.Reason.DENIED);
            return;
          }
          if (result.reason() != OperationResult.Reason.OK) {
            finish(request, result.reason());
            return;
          }
          if (request.amount == 0) {
            finish(request, OperationResult.Reason.OK);
            return;
          }
          if (++request.pages > 640) {
            finish(request, OperationResult.Reason.TECHNICAL_LIMIT);
            return;
          }
          for (var row : result.rows()) {
            if (row.backing().equals(backing(request.fixed))) continue;
            if (request.direction == Direction.TO_STORAGE
                ? !row.depositable()
                : !row.withdrawable() || row.available() == 0) continue;
            if (!request.rows.containsKey(row.backing())) {
              if (bytes > MAX_BYTES - CANDIDATE_BYTES) {
                finish(request, OperationResult.Reason.TECHNICAL_LIMIT);
                return;
              }
              request.bytes += CANDIDATE_BYTES;
              bytes += CANDIDATE_BYTES;
            }
            boolean admitted =
                request.cursorScope == null
                    ? request.order.add(row.backing(), row.priority())
                    : request.rotation.add(row.backing());
            if (!admitted) {
              finish(request, OperationResult.Reason.TECHNICAL_LIMIT);
              return;
            }
            request.rows.put(row.backing(), row);
          }
          if (result.next() == null) attempt(request);
          else {
            var admission = page(request, result.next());
            if (admission.rejection() != OperationResult.Reason.OK)
              finish(request, admission.rejection());
          }
        });
  }

  private void attempt(Request request) {
    if (!scopeValid(request) || !fixedAllowed(request)) {
      finish(request, OperationResult.Reason.DENIED);
      return;
    }
    UUID next =
        request.cursorScope == null
            ? request.order.poll()
            : request.rotation.pollAfter(saved.routeCursors().last(request.cursorScope));
    if (next == null) {
      finish(request, request.last);
      return;
    }
    if (request.cursorScope != null && !saved.routeCursors().advance(request.cursorScope, next)) {
      finish(request, OperationResult.Reason.TECHNICAL_LIMIT);
      return;
    }
    var row = request.rows.remove(next);
    request.bytes -= CANDIDATE_BYTES;
    bytes -= CANDIDATE_BYTES;
    var source = request.direction == Direction.TO_STORAGE ? request.fixed : row.endpoint();
    var target = request.direction == Direction.TO_STORAGE ? row.endpoint() : request.fixed;
    request.ticket = UUID.randomUUID();
    var admission =
        transfers.submitGuarded(
            request.ticket,
            request.actor,
            request.origin,
            source,
            target,
            request.key,
            request.amount,
            result -> {
              if (!requests.containsKey(request.id)) return;
              var move = result.transfer();
              boolean untouched =
                  move == null
                      || move.state() == StagedTransfer.State.COMPLETE
                          && move.pending() == StagedTransfer.PendingCall.NONE
                          && move.staged() == 0
                          && move.delivered() == 0
                          && move.returned() == 0;
              boolean retry =
                  untouched
                      && switch (result.reason()) {
                        case DENIED, OFFLINE, RATE_LIMIT -> true;
                        case FULL, CATALOG_LIMIT -> request.direction == Direction.TO_STORAGE;
                        case SHORTAGE, RESERVED -> request.direction == Direction.FROM_STORAGE;
                        default -> false;
                      };
              if (retry && scopeValid(request) && fixedAllowed(request)) {
                if (move != null) saved.transfers().retire(result.id());
                request.last = result.reason();
                attempt(request);
              } else {
                drop(request);
                request.completion.accept(new Result(request.id, result));
              }
            },
            request.authorized,
            request.canStart);
    if (admission.rejection() != OperationResult.Reason.OK) finish(request, admission.rejection());
  }

  public boolean cancel(UUID id, NetworkPermissions.Actor actor) {
    checkThread();
    var request = requests.get(id);
    if (request == null || !request.actor.id().equals(actor.id()) || transfers.inExecution())
      return false;
    if (!transfers.cancelQueued(request.ticket, actor)) return false;
    drop(request);
    return true;
  }

  private boolean scopeValid(Request request) {
    var member = networks.membership(request.origin);
    return request.authorized.getAsBoolean()
        && member.status() == NetworkTopology.Status.VALID
        && member.scope().network().equals(request.network)
        && member.scope().segment().equals(request.segment)
        && networks.authorize(request.actor, request.origin, NetworkPermissions.Permission.VIEW)
            == NetworkAuthority.Decision.ALLOWED
        && networks.authorize(
                request.actor,
                request.origin,
                request.direction == Direction.TO_STORAGE
                    ? NetworkPermissions.Permission.DEPOSIT
                    : NetworkPermissions.Permission.WITHDRAW)
            == NetworkAuthority.Decision.ALLOWED;
  }

  private boolean fixedAllowed(Request request) {
    return transfers.routeAllowed(
        request.actor,
        request.origin,
        request.fixed,
        request.key,
        request.direction == Direction.TO_STORAGE
            ? NetworkPermissions.Permission.WITHDRAW
            : NetworkPermissions.Permission.DEPOSIT);
  }

  private void finish(Request request, OperationResult.Reason reason) {
    drop(request);
    request.completion.accept(
        new Result(request.id, new WorldStorageTransfers.Result(request.id, reason, null)));
  }

  private void drop(Request request) {
    if (requests.remove(request.id, request)) bytes -= request.bytes;
  }

  private void prune() {
    var iterator = requests.values().iterator();
    while (iterator.hasNext()) {
      var request = iterator.next();
      if (!transfers.routeTicketActive(request.ticket)) {
        bytes -= request.bytes;
        iterator.remove();
      }
    }
  }

  private static UUID backing(WorldStorageTransfers.Endpoint endpoint) {
    if (endpoint instanceof WorldStorageTransfers.Buffered buffer) return buffer.handle().id();
    return endpoint instanceof WorldStorageTransfers.Digital digital
        ? digital.handle().backing()
        : ((WorldStorageTransfers.External) endpoint).handle().backing();
  }

  private static WorldStorageTransfers.Submission rejected(OperationResult.Reason reason) {
    return new WorldStorageTransfers.Submission(null, reason);
  }

  private void checkThread() {
    if (!server.isSameThread()) throw new IllegalStateException("Routes require the server thread");
  }
}
