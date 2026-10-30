package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.CatalogCounts;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.core.resource.SearchQuery;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Bounded transient search sessions; no client receives raw topology/provider/ledger cursors. */
public final class WorldStorageSearch {
  public static final int MAX_SEARCHES = 16;
  public static final int MAX_PAGES = 32768;
  public static final long MAX_BYTES = 16L * 1024 * 1024;
  public static final int IDLE_TICKS = 200;

  /** Craftable keys one search may mark or add as zero-count rows (A3). */
  public static final int MAX_CRAFTABLE = 4096;

  /** Q109: at most this many recently crafted keys are ordered first. */
  public static final int MAX_PINNED = 9;

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

  /**
   * A bounded display window; offsets address the completed sorted observation, never a live key.
   */
  public record Window(
      OperationResult.Reason reason,
      boolean complete,
      List<CatalogCounts.Row> rows,
      List<String> names,
      List<Boolean> craftable,
      int offset,
      int total,
      BigInteger used,
      BigInteger capacity,
      boolean infinite,
      boolean external) {
    public Window {
      rows = List.copyOf(rows);
      names = List.copyOf(names);
      craftable = List.copyOf(craftable);
    }
  }

  private static final class Search {
    final UUID id;
    final NetworkPermissions.Actor actor;
    final NodeRegistry.Position origin;
    final ResourceKind kind;
    final String text;
    final SearchQuery query;
    final Map<ResourceKey, String> names = new HashMap<>();
    final TreeSet<CatalogCounts.Row> ordered;
    final NetworkTopology.Scope scope;
    final long grantsGeneration;
    final java.util.Set<ResourceKey> craftable;
    final boolean ghosts;

    /** Q62: show only craftable keys. */
    final boolean craftableOnly;

    /** Q62: each key's latest change sequence over this search's own cells (RECENT sort). */
    final Map<ResourceKey, Long> changed = new HashMap<>();

    final Map<ResourceKey, CatalogCounts.Row> ghostRows = new HashMap<>();
    CatalogCounts counts = new CatalogCounts();
    final Map<UUID, WorldStorageTransfers.Endpoint> sources = new HashMap<>();
    final List<CatalogCounts.Row> index = new ArrayList<>();
    BigInteger used = BigInteger.ZERO, capacity = BigInteger.ZERO;
    boolean infinite, external;
    Iterator<CatalogCounts.Row> indexing;
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
        int sort,
        NetworkTopology.Scope scope,
        long grantsGeneration,
        java.util.Set<ResourceKey> craftable,
        boolean ghosts,
        boolean craftableOnly,
        List<ResourceKey> pinned,
        int tick) {
      this.id = id;
      this.actor = actor;
      this.origin = origin;
      this.kind = kind;
      this.text = text;
      query = SearchQuery.parse(text);
      // Q109: pinned rows lead in pin order, ahead of (and unaffected by reversing) the sort, so
      // the index stays one total order and paging is unchanged.
      var pins = new HashMap<ResourceKey, Integer>();
      for (int index = 0; index < pinned.size(); index++)
        pins.putIfAbsent(pinned.get(index), index);
      ordered =
          new TreeSet<>(
              java.util.Comparator.<CatalogCounts.Row>comparingInt(
                      row -> pins.getOrDefault(row.key(), Integer.MAX_VALUE))
                  .thenComparing(
                      TerminalSearchSupport.order(
                          sort, names::get, key -> changed.getOrDefault(key, 0L))));
      this.scope = scope;
      this.grantsGeneration = grantsGeneration;
      this.craftable = craftable;
      this.ghosts = ghosts;
      this.craftableOnly = craftableOnly;
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
    return start(id, actor, origin, kind, text, TerminalSearchSupport.NAME);
  }

  public WorldStorageTransfers.Submission start(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKind kind,
      String text,
      int sort) {
    return start(id, actor, origin, kind, text, sort, java.util.Set.of(), false);
  }

  public WorldStorageTransfers.Submission start(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKind kind,
      String text,
      int sort,
      java.util.Set<ResourceKey> craftable,
      boolean ghosts) {
    return start(id, actor, origin, kind, text, sort, craftable, ghosts, false, List.of());
  }

  /**
   * Starts a search whose rows carry a craftable flag for keys in {@code craftable} (A3). With
   * {@code ghosts}, craftable keys of this kind that match the text but have no stock become
   * zero-count rows, so they can be requested from the grid. The caller supplies only keys that
   * patterns visible to this segment produce (gateway CRAFT publishing already applied). Rows whose
   * key is in {@code pinned} come first, in that order (Q109). With {@code craftableOnly}, rows
   * whose key is not in {@code craftable} are left out (Q62).
   */
  public WorldStorageTransfers.Submission start(
      UUID id,
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      ResourceKind kind,
      String text,
      int sort,
      java.util.Set<ResourceKey> craftable,
      boolean ghosts,
      boolean craftableOnly,
      List<ResourceKey> pinned) {
    if (craftable.size() > MAX_CRAFTABLE)
      throw new IllegalArgumentException("Too many craftable keys");
    if (pinned.size() > MAX_PINNED) throw new IllegalArgumentException("Too many pinned keys");
    if (!TerminalSearchSupport.validSort(sort))
      throw new IllegalArgumentException("Invalid terminal sort");
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
            sort,
            member.scope(),
            saved.network(member.scope().network()).generation(),
            java.util.Set.copyOf(craftable),
            ghosts,
            craftableOnly,
            List.copyOf(pinned),
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
    var anchor =
        after == null
            ? null
            : Objects.requireNonNullElse(search.counts.row(after), search.ghostRows.get(after));
    if (after != null && anchor == null) return failed(OperationResult.Reason.DENIED);
    var iterator =
        (anchor == null ? search.ordered : search.ordered.tailSet(anchor, false)).iterator();
    var rows = new ArrayList<CatalogCounts.Row>();
    long charge = 0;
    ResourceKey last = null;
    boolean more = false;
    while (iterator.hasNext()) {
      var row = iterator.next();
      long size = 256L + 3L * row.key().registryId().length() + row.key().componentByteCount();
      if (rows.size() == limit || charge > ResourceLedger.CATALOG_PAGE_BYTES - size) {
        more = true;
        break;
      }
      rows.add(row);
      last = row.key();
      charge += size;
    }
    return new Result(OperationResult.Reason.OK, true, search.pages, rows, more ? last : null);
  }

  public Window window(UUID id, NetworkPermissions.Actor actor, int offset, int limit) {
    if (offset < 0 || offset > CatalogCounts.MAX_CONTRIBUTIONS || limit < 1 || limit > 256)
      throw new IllegalArgumentException("Invalid display window");
    var state = result(id, actor, null, 1);
    var search = searches.get(id);
    if (state.reason() != OperationResult.Reason.OK || search == null)
      return new Window(
          state.reason(),
          true,
          List.of(),
          List.of(),
          List.of(),
          0,
          0,
          BigInteger.ZERO,
          BigInteger.ZERO,
          false,
          false);
    int start = Math.min(offset, Math.max(0, search.index.size() - 1));
    return new Window(
        state.reason(),
        state.complete(),
        state.complete()
            ? search.index.subList(start, Math.min(search.index.size(), start + limit))
            : List.of(),
        state.complete()
            ? search.index.subList(start, Math.min(search.index.size(), start + limit)).stream()
                .map(row -> search.names.get(row.key()))
                .toList()
            : List.of(),
        state.complete()
            ? search.index.subList(start, Math.min(search.index.size(), start + limit)).stream()
                .map(row -> search.craftable.contains(row.key()))
                .toList()
            : List.of(),
        start,
        search.index.size(),
        search.used,
        search.capacity,
        search.infinite,
        search.external);
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
                  if (page.source() instanceof WorldStorageTransfers.Digital digital) {
                    var owner = saved.registry().ownership(digital.handle().backing());
                    search.used = search.used.add(BigInteger.valueOf(owner.total()));
                    search.capacity =
                        search.capacity.add(BigInteger.valueOf(owner.definition().capacity()));
                    search.infinite |= owner.definition().infinite();
                  } else search.external = true;
                }
                if (page.source() instanceof WorldStorageTransfers.External)
                  for (var row : page.rows())
                    search.used = search.used.add(BigInteger.valueOf(row.total()));
                for (var row : page.rows()) {
                  if (search.craftableOnly && !search.craftable.contains(row.key())) continue;
                  var codec = new PlatformResourceCodec(server.registryAccess());
                  String name;
                  try {
                    if (!search.query.mayMatch(TerminalSearchSupport.subject(codec, row.key())))
                      continue;
                    name = TerminalSearchSupport.name(codec, row.key());
                  } catch (IllegalArgumentException unsupported) {
                    fail(search, OperationResult.Reason.UNSUPPORTED);
                    return;
                  }
                  var previousRow = search.counts.row(row.key());
                  long indexCharge = previousRow == null ? 1024 : 0;
                  long before = search.counts.bytes();
                  long worst = search.counts.additionalBytes(source, row.key());
                  if (bytes > MAX_BYTES - worst - indexCharge) {
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
                  if (previousRow != null) search.ordered.remove(previousRow);
                  // Q62: the latest change over this search's cells, merged while the row is out.
                  if (page.source() instanceof WorldStorageTransfers.Digital digital)
                    search.changed.merge(
                        row.key(),
                        saved.registry().changed(digital.handle(), row.key()),
                        Math::max);
                  if (previousRow == null) {
                    search.names.put(row.key(), name.substring(0, Math.min(256, name.length())));
                  }
                  search.ordered.add(search.counts.row(row.key()));
                  long delta = search.counts.bytes() - before + indexCharge;
                  bytes += delta;
                  search.charge += delta;
                }
              }
              if (page.next() == null) {
                if (!addGhosts(search)) return;
                search.indexing = search.ordered.iterator();
                index(search);
              } else {
                var admission = observe(search, page.next());
                if (admission.rejection() != OperationResult.Reason.OK)
                  fail(search, admission.rejection());
              }
            });
  }

  /**
   * Adds a zero-count row for each craftable key of this kind with no stock that matches the text.
   * Bounded by {@link #MAX_CRAFTABLE}, charged like a stocked row; false when it failed.
   */
  private boolean addGhosts(Search search) {
    if (!search.ghosts) return true;
    var codec = new PlatformResourceCodec(server.registryAccess());
    for (var key : search.craftable) {
      if (key.kind() != search.kind || search.counts.row(key) != null) continue;
      String name;
      try {
        // Ghost rows have no stock, so count terms see zero (Q61: "=0" finds them).
        if (!search.query.matches(TerminalSearchSupport.subject(codec, key), BigInteger.ZERO))
          continue;
        name = TerminalSearchSupport.name(codec, key);
      } catch (IllegalArgumentException unsupported) {
        continue;
      }
      long charge = 1024L + key.registryId().length() + key.componentByteCount();
      if (bytes > MAX_BYTES - charge) {
        fail(search, OperationResult.Reason.TECHNICAL_LIMIT);
        return false;
      }
      var row = new CatalogCounts.Row(key, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, null);
      search.names.put(key, name.substring(0, Math.min(256, name.length())));
      search.ghostRows.put(key, row);
      search.ordered.add(row);
      bytes += charge;
      search.charge += charge;
    }
    return true;
  }

  /** Materialize at most 32 sorted row references per shared scheduler turn for O(1) scrolling. */
  private void index(Search search) {
    search.ticket = UUID.randomUUID();
    var admission =
        transfers.query(
            search.ticket,
            search.actor,
            search.origin,
            search.kind,
            () -> {
              if (searches.get(search.id) != search) return;
              if (!valid(search, search.actor)) {
                fail(search, OperationResult.Reason.DENIED);
                return;
              }
              search.touched = server.getTickCount();
              var codec = new PlatformResourceCodec(server.registryAccess());
              for (int i = 0; i < 32 && search.indexing.hasNext(); i++) {
                var row = search.indexing.next();
                // Q61: count terms are settled now that each row's total is summed.
                try {
                  if (search.query.counts()
                      && !search.query.matches(
                          TerminalSearchSupport.subject(codec, row.key()), row.total())) continue;
                } catch (IllegalArgumentException unsupported) {
                  fail(search, OperationResult.Reason.UNSUPPORTED);
                  return;
                }
                search.index.add(row);
              }
              if (search.indexing.hasNext()) index(search);
              else {
                search.ticket = null;
                search.indexing = null;
                search.complete = true;
              }
            });
    if (admission.rejection() != OperationResult.Reason.OK) fail(search, admission.rejection());
  }

  private boolean valid(Search search, NetworkPermissions.Actor actor) {
    var member = networks.membership(search.origin);
    return search.actor.id().equals(actor.id())
        && member.status() == NetworkTopology.Status.VALID
        && search.scope.equals(member.scope())
        && search.grantsGeneration == saved.network(search.scope.network()).generation()
        && networks.allows(actor, search.origin, NetworkPermissions.Permission.VIEW);
  }

  private void fail(Search search, OperationResult.Reason reason) {
    bytes -= search.charge - 2048;
    search.charge = 2048;
    search.indexing = null;
    search.index.clear();
    search.sources.clear();
    search.ordered.clear();
    search.names.clear();
    search.ghostRows.clear();
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
        search.indexing = null;
        search.index.clear();
        search.sources.clear();
        search.ordered.clear();
        search.names.clear();
        search.ghostRows.clear();
        iterator.remove();
      } else if (!search.complete
          && search.ticket != null
          && !transfers.routeTicketActive(search.ticket)) {
        bytes -= search.charge;
        search.counts = null;
        search.indexing = null;
        search.index.clear();
        search.sources.clear();
        search.ordered.clear();
        search.names.clear();
        search.ghostRows.clear();
        iterator.remove();
      }
    }
  }

  private void drop(Search search) {
    if (searches.remove(search.id, search)) {
      bytes -= search.charge;
      search.counts = null;
      search.indexing = null;
      search.index.clear();
      search.sources.clear();
      search.ordered.clear();
      search.names.clear();
      search.ghostRows.clear();
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
    ServerThread.require(server, "Storage search");
  }
}
