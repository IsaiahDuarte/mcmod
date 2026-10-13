package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.CatalogCounts;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Bounded transient search sessions; no client receives raw topology/provider/ledger cursors. */
public final class WorldStorageSearch {
  public static final int MAX_SEARCHES = 16;
  public static final int MAX_PAGES = 32768;
  public static final long MAX_BYTES = 16L * 1024 * 1024;
  public static final int IDLE_TICKS = 200;

  public record Result(
      OperationResult.Reason reason,
      boolean complete,
      int observedPages,
      List<CatalogCounts.Row> rows,
      ResourceKey next) {
    public Result {
      rows = List.copyOf(rows);
    }
  }

  private static final class Search {
    final UUID id;
    final NetworkPermissions.Actor actor;
    final NodeRegistry.Position origin;
    final ResourceKind kind;
    final String text;
    final NetworkTopology.Scope scope;
    final long grantsGeneration;
    CatalogCounts counts = new CatalogCounts();
    final Map<UUID, WorldStorageTransfers.Endpoint> sources = new HashMap<>();
    UUID ticket;
    OperationResult.Reason reason = OperationResult.Reason.OK;
    boolean complete;
    int pages;
    int touched;
    long charge = 2048;

    Search(
        UUID id,
        NetworkPermissions.Actor actor,
        NodeRegistry.Position origin,
        ResourceKind kind,
        String text,
        NetworkTopology.Scope scope,
        long grantsGeneration,
        int tick) {
      this.id = id;
      this.actor = actor;
      this.origin = origin;
      this.kind = kind;
      this.text = text;
      this.scope = scope;
      this.grantsGeneration = grantsGeneration;
      touched = tick;
    }
  }

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final WorldStorageTransfers transfers;
  private final DeviceSavedData saved;
  private final Map<UUID, Search> searches = new HashMap<>();
  private long bytes;

  WorldStorageSearch(
      MinecraftServer server, WorldNetworks networks, WorldStorageTransfers transfers) {
    this.server = server;
    this.networks = networks;
    this.transfers = transfers;
    saved = DeviceSavedData.get(server);
  }

  public WorldStorageTransfers.Submission start(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKind kind,
      String text) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(origin);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(text);
    if (text.length() > 128)
      throw new IllegalArgumentException("Search text exceeds 128 characters");
    prune();
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID)
      return rejected(OperationResult.Reason.OFFLINE);
    var search =
        new Search(
            id,
            actor,
            origin,
            kind,
            text.toLowerCase(Locale.ROOT),
            member.scope(),
            saved.network(member.scope().network()).generation(),
            server.getTickCount());
    if (!valid(search, actor)) return rejected(OperationResult.Reason.DENIED);
    if (searches.containsKey(id)
        || searches.size() == MAX_SEARCHES
        || bytes > MAX_BYTES - search.charge)
      return rejected(OperationResult.Reason.TECHNICAL_LIMIT);
    searches.put(id, search);
    bytes += search.charge;
    var admission = observe(search, null);
    if (admission.rejection() != OperationResult.Reason.OK) drop(search);
    return admission;
  }

  public Result result(UUID id, NetworkPermissions.Actor actor, ResourceKey after, int limit) {
    checkThread();
    Objects.requireNonNull(id);
    Objects.requireNonNull(actor);
    if (limit < 1 || limit > 32) throw new IllegalArgumentException("Invalid search result limit");
    prune();
    var search = searches.get(id);
    if (search == null || !search.actor.id().equals(actor.id()))
      return failed(OperationResult.Reason.DENIED);
    search.touched = server.getTickCount();
    if (search.reason != OperationResult.Reason.OK) return failed(search.reason);
    if (!valid(search, actor)) {
      drop(search);
      return failed(OperationResult.Reason.DENIED);
    }
    if (!search.complete)
      return new Result(OperationResult.Reason.OK, false, search.pages, List.of(), null);
    if (after != null && after.kind() != search.kind) return failed(OperationResult.Reason.DENIED);
    var page = search.counts.page(after, limit);
    return new Result(OperationResult.Reason.OK, true, search.pages, page.rows(), page.next());
  }

  public boolean close(UUID id, NetworkPermissions.Actor actor) {
    checkThread();
    var search = searches.get(Objects.requireNonNull(id));
    if (search == null || !search.actor.id().equals(actor.id())) return false;
    if (search.ticket != null
        && transfers.routeTicketActive(search.ticket)
        && !transfers.inExecution()) transfers.cancelQueued(search.ticket, actor);
    drop(search);
    return true;
  }

  private WorldStorageTransfers.Submission observe(
      Search search, WorldStorageCatalog.Cursor cursor) {
    search.ticket = UUID.randomUUID();
    return transfers
        .allKeys()
        .page(
            search.ticket,
            search.actor,
            search.origin,
            search.kind,
            cursor,
            page -> {
              if (searches.get(search.id) != search) return;
              search.touched = server.getTickCount();
              if (page.reason() != OperationResult.Reason.OK) {
                fail(search, page.reason());
                return;
              }
              if (!valid(search, search.actor)) {
                fail(search, OperationResult.Reason.DENIED);
                return;
              }
              if (++search.pages > MAX_PAGES) {
                fail(search, OperationResult.Reason.TECHNICAL_LIMIT);
                return;
              }
              if (page.source() != null) {
                var source = backing(page.source());
                var previous = search.sources.get(source);
                if (previous != null && !previous.equals(page.source())) {
                  fail(search, OperationResult.Reason.DENIED);
                  return;
                }
                if (previous == null) {
                  if (bytes > MAX_BYTES - 384 || search.sources.size() == 5120) {
                    fail(search, OperationResult.Reason.TECHNICAL_LIMIT);
                    return;
                  }
                  search.sources.put(source, page.source());
                  search.charge += 384;
                  bytes += 384;
                }
                for (var row : page.rows()) {
                  if (!row.key().registryId().contains(search.text)) continue;
                  long before = search.counts.bytes();
                  long worst = search.counts.additionalBytes(source, row.key());
                  if (bytes > MAX_BYTES - worst) {
                    fail(search, OperationResult.Reason.TECHNICAL_LIMIT);
                    return;
                  }
                  var reason =
                      search.counts.observe(
                          source, row.key(), row.total(), row.reserved(), row.capacity());
                  if (reason != OperationResult.Reason.OK) {
                    fail(search, reason);
                    return;
                  }
                  long delta = search.counts.bytes() - before;
                  bytes += delta;
                  search.charge += delta;
                }
              }
              if (page.next() == null) {
                search.ticket = null;
                search.complete = true;
              } else {
                var admission = observe(search, page.next());
                if (admission.rejection() != OperationResult.Reason.OK)
                  fail(search, admission.rejection());
              }
            });
  }

  private boolean valid(Search search, NetworkPermissions.Actor actor) {
    var member = networks.membership(search.origin);
    return search.actor.id().equals(actor.id())
        && member.status() == NetworkTopology.Status.VALID
        && search.scope.equals(member.scope())
        && search.grantsGeneration == saved.network(search.scope.network()).generation()
        && networks.authorize(actor, search.origin, NetworkPermissions.Permission.VIEW)
            == dev.izzy.factorycore.core.network.NetworkAuthority.Decision.ALLOWED;
  }

  private void fail(Search search, OperationResult.Reason reason) {
    bytes -= search.charge - 2048;
    search.charge = 2048;
    search.sources.clear();
    search.ticket = null;
    search.reason = reason;
    search.complete = true;
    search.counts = null;
  }

  private void prune() {
    var iterator = searches.values().iterator();
    while (iterator.hasNext()) {
      var search = iterator.next();
      if (server.getTickCount() - search.touched >= IDLE_TICKS) {
        bytes -= search.charge;
        search.counts = null;
        search.sources.clear();
        iterator.remove();
      } else if (!search.complete
          && search.ticket != null
          && !transfers.routeTicketActive(search.ticket)) {
        bytes -= search.charge;
        search.counts = null;
        search.sources.clear();
        iterator.remove();
      }
    }
  }

  private void drop(Search search) {
    if (searches.remove(search.id, search)) {
      bytes -= search.charge;
      search.counts = null;
      search.sources.clear();
    }
  }

  private static UUID backing(WorldStorageTransfers.Endpoint endpoint) {
    return endpoint instanceof WorldStorageTransfers.Digital digital
        ? digital.handle().backing()
        : ((WorldStorageTransfers.External) endpoint).handle().backing();
  }

  private static Result failed(OperationResult.Reason reason) {
    return new Result(reason, true, 0, List.of(), null);
  }

  private static WorldStorageTransfers.Submission rejected(OperationResult.Reason reason) {
    return new WorldStorageTransfers.Submission(null, reason);
  }

  private void checkThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("Storage search requires server thread");
  }
}
