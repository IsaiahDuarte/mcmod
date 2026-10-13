package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * Live server-only all-key pages; no buffer enumeration, global snapshot or implicit chunk load.
 */
public final class WorldStorageCatalog {
  /**
   * Opaque to clients: accepted actor/scope and exact in-progress endpoint are retained
   * server-side.
   */
  public record Cursor(
      boolean connectors,
      UUID after,
      WorldStorageTransfers.Endpoint current,
      ResourceKey afterKey,
      int offset,
      NetworkTopology.Scope scope,
      long grantsGeneration,
      UUID principal,
      ResourceKind kind) {
    public Cursor {
      Objects.requireNonNull(scope);
      if (grantsGeneration <= 0) throw new IllegalArgumentException("Invalid grant generation");
      Objects.requireNonNull(principal);
      Objects.requireNonNull(kind);
      if (offset < 0
          || current instanceof WorldStorageTransfers.PlayerInventory
          || current instanceof WorldStorageTransfers.Buffered
          || current == null && (afterKey != null || offset != 0)
          || afterKey != null && afterKey.kind() != kind
          || !connectors && (offset != 0 || current instanceof WorldStorageTransfers.External)
          || connectors && (afterKey != null || current instanceof WorldStorageTransfers.Digital))
        throw new IllegalArgumentException("Invalid all-key catalog cursor");
    }
  }

  /**
   * Reserved is the current full backing/key hold, so it must not be summed across source pages.
   */
  public record Row(ResourceKey key, long total, long reserved, Long capacity) {
    public Row {
      Objects.requireNonNull(key);
      if (total < 0 || reserved < 0 || capacity != null && capacity < total)
        throw new IllegalArgumentException("Invalid catalog stock observation");
    }
  }

  public record Page(
      OperationResult.Reason reason,
      WorldStorageTransfers.Endpoint source,
      List<Row> rows,
      boolean sourceComplete,
      Cursor next) {
    public Page {
      rows = List.copyOf(rows);
      if (rows.size() > ResourceLedger.CATALOG_PAGE_SIZE)
        throw new IllegalArgumentException("Catalog page size limit");
    }
  }

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final WorldStorageTransfers transfers;
  private final DeviceSavedData saved;

  WorldStorageCatalog(
      MinecraftServer server, WorldNetworks networks, WorldStorageTransfers transfers) {
    this.server = server;
    this.networks = networks;
    this.transfers = transfers;
    saved = DeviceSavedData.get(server);
  }

  static ResourceKey identity(ResourceKind kind) {
    return kind == ResourceKind.ENERGY
        ? ResourceKey.ENERGY
        : new ResourceKey(kind, "factorycore:catalog", new byte[0]);
  }

  public WorldStorageTransfers.Submission page(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKind kind,
      Cursor cursor,
      Consumer<Page> completion) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(completion);
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return rejected(OperationResult.Reason.OFFLINE);
    var start =
        cursor == null
            ? new Cursor(
                false,
                null,
                null,
                null,
                0,
                member.scope(),
                saved.network(member.scope().network()).generation(),
                actor.id(),
                kind)
            : cursor;
    if (!valid(actor, origin, start) || start.kind() != kind)
      return rejected(OperationResult.Reason.DENIED);
    return transfers.query(
        id,
        actor,
        origin,
        kind,
        () -> {
          if (!valid(actor, origin, start)) {
            completion.accept(failed(OperationResult.Reason.DENIED));
            return;
          }
          Page result;
          try {
            result =
                start.connectors() ? foreign(actor, origin, start) : digital(actor, origin, start);
          } catch (IllegalArgumentException | IllegalStateException stale) {
            completion.accept(failed(OperationResult.Reason.OFFLINE));
            return;
          } catch (ArithmeticException overflow) {
            completion.accept(failed(OperationResult.Reason.TECHNICAL_LIMIT));
            return;
          }
          if (result.reason() != OperationResult.Reason.OK) {
            completion.accept(result);
            return;
          }
          if (!valid(actor, origin, start)
              || result.source() != null
                  && !transfers.routeAllowed(
                      actor,
                      origin,
                      result.source(),
                      identity(kind),
                      NetworkPermissions.Permission.VIEW))
            completion.accept(failed(OperationResult.Reason.DENIED));
          else completion.accept(result);
        });
  }

  private Page digital(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, Cursor cursor) {
    WorldStorageTransfers.Digital endpoint = (WorldStorageTransfers.Digital) cursor.current();
    if (endpoint == null) {
      var owners = saved.registry().handles(cursor.after(), 8);
      for (var handle : owners.handles()) {
        if (saved.registry().validate(handle)
                == dev.izzy.factorycore.core.storage.DeviceRegistry.Failure.OK
            && saved.registry().definition(handle).kind() == cursor.kind()
            && transfers.routeAllowed(
                actor,
                origin,
                new WorldStorageTransfers.Digital(handle),
                identity(cursor.kind()),
                NetworkPermissions.Permission.VIEW)) {
          endpoint = new WorldStorageTransfers.Digital(handle);
          break;
        }
      }
      if (endpoint == null)
        return new Page(
            OperationResult.Reason.OK,
            null,
            List.of(),
            true,
            owners.next() == null
                ? next(cursor, true, null, null, null, 0)
                : next(cursor, false, owners.next(), null, null, 0));
    }
    if (saved.registry().definition(endpoint.handle()).kind() != cursor.kind()
        || !transfers.routeAllowed(
            actor, origin, endpoint, identity(cursor.kind()), NetworkPermissions.Permission.VIEW))
      return failed(OperationResult.Reason.DENIED);
    var page =
        saved
            .registry()
            .catalog(endpoint.handle(), cursor.afterKey(), ResourceLedger.CATALOG_PAGE_SIZE);
    Long capacity =
        cursor.kind() == ResourceKind.ENERGY
            ? saved.registry().definition(endpoint.handle()).capacity()
            : null;
    var rows =
        page.entries().stream()
            .filter(entry -> entry.stock().loaded())
            .map(
                entry ->
                    new Row(entry.key(), entry.stock().total(), entry.stock().reserved(), capacity))
            .toList();
    var continuation =
        page.next() == null
            ? next(cursor, false, endpoint.handle().backing(), null, null, 0)
            : next(cursor, false, cursor.after(), endpoint, page.next(), 0);
    return new Page(OperationResult.Reason.OK, endpoint, rows, page.next() == null, continuation);
  }

  private Page foreign(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, Cursor cursor) {
    WorldStorageTransfers.External endpoint = (WorldStorageTransfers.External) cursor.current();
    if (endpoint == null) {
      var owners = saved.nodes().handles(cursor.after(), 8);
      for (var handle : owners.handles()) {
        if (handle.kind() != NodeRegistry.Kind.CONNECTOR) continue;
        var found = networks.connectors().discover(actor, origin, handle, identity(cursor.kind()));
        if (found.handle() != null) {
          endpoint = new WorldStorageTransfers.External(found.handle());
          break;
        }
      }
      if (endpoint == null)
        return new Page(
            OperationResult.Reason.OK,
            null,
            List.of(),
            true,
            owners.next() == null ? null : next(cursor, true, owners.next(), null, null, 0));
    }
    if (endpoint.handle().kind() != cursor.kind()) return failed(OperationResult.Reason.DENIED);
    if (cursor.offset() >= 64) return failed(OperationResult.Reason.UNSUPPORTED);
    var observed = networks.connectors().observe(actor, origin, endpoint.handle(), cursor.offset());
    if (observed.reason() != OperationResult.Reason.OK) return failed(observed.reason());
    var totals = new TreeMap<ResourceKey, Long>();
    var capacities = new TreeMap<ResourceKey, Long>();
    for (var entry : observed.page().entries()) {
      totals.merge(entry.key(), entry.stored(), Math::addExact);
      if (cursor.kind() == ResourceKind.ENERGY && entry.capacity() != null)
        capacities.merge(entry.key(), entry.capacity(), Math::addExact);
    }
    var rows = new ArrayList<Row>();
    for (var entry : totals.entrySet())
      rows.add(
          new Row(
              entry.getKey(),
              entry.getValue(),
              saved.externalClaims().reserved(endpoint.handle().backing(), entry.getKey()),
              capacities.get(entry.getKey())));
    var continuation =
        observed.page().next() < 0
            ? next(cursor, true, endpoint.handle().connector().id(), null, null, 0)
            : next(cursor, true, cursor.after(), endpoint, null, observed.page().next());
    return new Page(
        OperationResult.Reason.OK, endpoint, rows, observed.page().next() < 0, continuation);
  }

  boolean valid(NetworkPermissions.Actor actor, NodeRegistry.Position origin, Cursor cursor) {
    var member = networks.membership(origin);
    return member.status() == NetworkTopology.Status.VALID
        && cursor.principal().equals(actor.id())
        && cursor.scope().equals(member.scope())
        && cursor.grantsGeneration() == saved.network(cursor.scope().network()).generation()
        && networks.authorize(actor, origin, NetworkPermissions.Permission.VIEW)
            == NetworkAuthority.Decision.ALLOWED;
  }

  private static Cursor next(
      Cursor previous,
      boolean connectors,
      UUID after,
      WorldStorageTransfers.Endpoint current,
      ResourceKey afterKey,
      int offset) {
    return new Cursor(
        connectors,
        after,
        current,
        afterKey,
        offset,
        previous.scope(),
        previous.grantsGeneration(),
        previous.principal(),
        previous.kind());
  }

  private static Page failed(OperationResult.Reason reason) {
    return new Page(reason, null, List.of(), true, null);
  }

  private static WorldStorageTransfers.Submission rejected(OperationResult.Reason reason) {
    return new WorldStorageTransfers.Submission(null, reason);
  }

  private void checkThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("Storage catalogs require server thread");
  }
}
