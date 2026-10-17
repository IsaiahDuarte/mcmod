package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformFilterTags;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/** Loaded-only machine presets; bounded discovery and every transfer share world scheduling. */
public final class WorldMachinePresets {
  public record Status(MachinePresets.Mode mode, OperationResult.Reason reason, boolean waiting) {}

  private static final class Entry {
    final MachineInterfaceEntity owner;
    final UUID node;
    boolean active, scheduled, route;
    MachinePresets.Deployment scheduledFor;
    UUID request;
    int nextTick, sourceCursor, pick, pages;
    long revision, cursorCharge;
    WorldStorageCatalog.Cursor cursor;
    WorldConnectors.Handle handle;
    OperationResult.Reason reason = OperationResult.Reason.SHORTAGE;

    Entry(MachineInterfaceEntity owner) {
      this.owner = owner;
      node = owner.handle().id();
    }

    String task() {
      return "machine-preset:" + node;
    }
  }

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final DeviceSavedData saved;
  private final Map<UUID, Entry> entries = new HashMap<>();
  private final ArrayDeque<Entry> active = new ArrayDeque<>();
  private long cursorBytes;
  private static final long MAX_CURSOR_BYTES = 2L * 1024 * 1024;

  public WorldMachinePresets(MinecraftServer server, WorldNetworks networks) {
    this.server = server;
    this.networks = networks;
    saved = DeviceSavedData.get(server);
    saved.machinePresets().activity(this::deploymentChanged);
  }

  private void checkThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("Machine presets require server thread");
  }

  private void deploymentChanged(UUID node) {
    var entry = entries.get(node);
    if (entry == null) return;
    if (saved.machinePresets().get(node) == null) unregister(entry.owner);
    else wake(entry);
  }

  public void register(MachineInterfaceEntity owner) {
    checkThread();
    owner.validatePlaced();
    var entry = entries.get(owner.handle().id());
    if (entry != null) {
      if (entry.owner != owner) throw new IllegalStateException("Machine preset holder conflict");
      wake(entry);
      return;
    }
    if (entries.size() == MachinePresets.MAX_DEPLOYMENTS)
      throw new IllegalStateException("Machine preset holder limit");
    entry = new Entry(owner);
    entries.put(entry.node, entry);
    wake(entry);
  }

  public void unregister(MachineInterfaceEntity owner) {
    checkThread();
    if (owner.handle() == null) return;
    var entry = entries.get(owner.handle().id());
    if (entry == null || entry.owner != owner) return;
    cursor(entry, null);
    entries.remove(entry.node);
    active.remove(entry);
    entry.active = false;
  }

  public Status status(MachineInterfaceEntity owner) {
    checkThread();
    var deployment = saved.machinePresets().get(owner.handle().id());
    var entry = entries.get(owner.handle().id());
    return new Status(
        deployment == null ? MachinePresets.Mode.PAUSED : deployment.mode(),
        entry == null ? OperationResult.Reason.OFFLINE : entry.reason,
        entry != null && entry.owner == owner && (entry.scheduled || entry.request != null));
  }

  public void tagsReloaded() {
    checkThread();
    for (var entry : entries.values()) {
      cursor(entry, null);
      entry.sourceCursor = 0;
      entry.pages = 0;
      wake(entry);
    }
  }

  private void activate(Entry e) {
    if (!e.active && entries.get(e.node) == e) {
      e.active = true;
      active.addLast(e);
    }
  }

  private void wake(Entry e) {
    e.nextTick = 0;
    activate(e);
  }

  private void retry(Entry e, OperationResult.Reason reason) {
    e.reason = reason;
    e.nextTick = server.getTickCount() + 20;
    activate(e);
  }

  private boolean valid(Entry e, MachinePresets.Deployment d) {
    return entries.get(e.node) == e && !e.owner.isRemoved() && e.owner.presetAuthorized(d);
  }

  private boolean matches(MachinePresets.Deployment d, ResourceKey key) {
    return d.filter().matches(key, new PlatformFilterTags(server));
  }

  private NetworkPermissions.Actor actor(MachinePresets.Deployment d) {
    return new NetworkPermissions.Actor(d.authority().principal(), false);
  }

  public void tick() {
    checkThread();
    int count = Math.min(8, active.size());
    for (int i = 0; i < count; i++) {
      var e = active.removeFirst();
      e.active = false;
      if (entries.get(e.node) != e) continue;
      if (e.scheduled) {
        var a = e.scheduledFor.authority();
        if (networks.scheduler().isQueued(a.network(), a.principal(), e.task())) {
          activate(e);
          continue;
        }
        e.scheduled = false;
      }
      if (e.request != null) {
        boolean pending =
            e.route
                ? networks.transfers().routes().active(e.request)
                : networks.transfers().routeTicketActive(e.request);
        if (pending) {
          activate(e);
          continue;
        }
        e.request = null;
      }
      var d = saved.machinePresets().get(e.node);
      if (d == null || d.mode() == MachinePresets.Mode.PAUSED) {
        cursor(e, null);
        e.pages = 0;
        e.reason = OperationResult.Reason.OK;
        continue;
      }
      if (server.getTickCount() < e.nextTick) {
        activate(e);
        continue;
      }
      e.scheduledFor = d;
      var admitted =
          networks
              .scheduler()
              .submit(
                  d.authority().network(),
                  d.authority().principal(),
                  e.task(),
                  16,
                  () -> {
                    e.scheduled = false;
                    active.remove(e);
                    e.active = false;
                    if (entries.get(e.node) == e) step(e, d);
                  });
      if (admitted == FairScheduler.Admission.ACCEPTED
          || admitted == FairScheduler.Admission.REPLACED) {
        e.scheduled = true;
        activate(e);
      } else retry(e, OperationResult.Reason.TECHNICAL_LIMIT);
    }
  }

  private void step(Entry e, MachinePresets.Deployment d) {
    if (!valid(e, d)) {
      cursor(e, null);
      var unresolved = e.handle == null ? null : saved.transfers().unresolved(e.handle.backing());
      retry(
          e,
          unresolved != null
                  && (unresolved.transfer().state() == StagedTransfer.State.QUARANTINED
                      || unresolved.transfer().pending() != StagedTransfer.PendingCall.NONE)
              ? OperationResult.Reason.UNCERTAIN
              : !e.owner.diagnostic().isEmpty()
                  ? OperationResult.Reason.HANDLER_FAULT
                  : OperationResult.Reason.DENIED);
      return;
    }
    if (e.revision != d.revision()) {
      cursor(e, null);
      e.sourceCursor = 0;
      e.pages = 0;
      e.revision = d.revision();
    }
    var registration = networks.connectors().bind(e.owner);
    if (registration.handle() == null) {
      retry(e, registration.reason());
      return;
    }
    var handle = registration.handle();
    if (!handle.equals(e.handle)) {
      cursor(e, null);
      e.sourceCursor = 0;
      e.pages = 0;
      e.handle = handle;
    }
    var unresolved = saved.transfers().unresolved(handle.backing());
    if (unresolved != null) {
      if (unresolved.transfer().pending() != StagedTransfer.PendingCall.NONE
          || unresolved.transfer().state() == StagedTransfer.State.QUARANTINED) {
        retry(e, OperationResult.Reason.UNCERTAIN);
        return;
      }
      if (!owns(e, d, unresolved) || !matches(d, unresolved.transfer().key())) {
        retry(e, OperationResult.Reason.RESERVED);
        return;
      }
      e.request = unresolved.id();
      e.route = false;
      var submission =
          networks
              .transfers()
              .progressGuarded(
                  unresolved.id(),
                  actor(d),
                  false,
                  r -> finish(e, unresolved.id(), r, unresolved.transfer().delivered()),
                  () -> valid(e, d) && matches(d, unresolved.transfer().key()));
      admitted(e, submission);
      return;
    }
    if (d.mode() == MachinePresets.Mode.IMPORT) importPage(e, d);
    else exportPage(e, d);
  }

  private static boolean owns(
      Entry e, MachinePresets.Deployment d, TransferRegistry.Stored record) {
    var c = record.context();
    if (c == null
        || c.source().type() == TransferContext.Type.PLAYER
        || c.destination().type() == TransferContext.Type.PLAYER) return false;
    var endpoint = d.mode() == MachinePresets.Mode.IMPORT ? c.source() : c.destination();
    return record.transfer().owner().equals(d.authority().principal())
        && c.network().equals(d.authority().network())
        && c.segment().equals(d.authority().segment())
        && c.origin().equals(e.owner.position())
        && endpoint.type() == TransferContext.Type.EXTERNAL
        && endpoint.device().equals(d.node())
        && endpoint.backing().equals(e.handle.backing())
        && endpoint.generation() == d.leaseGeneration()
        && endpoint.configuration() == d.configuration();
  }

  private void importPage(Entry e, MachinePresets.Deployment d) {
    var observation =
        networks.connectors().observe(actor(d), e.owner.position(), e.handle, e.sourceCursor);
    if (observation.reason() != OperationResult.Reason.OK) {
      retry(e, observation.reason());
      return;
    }
    if (!valid(e, d)) {
      retry(e, OperationResult.Reason.DENIED);
      return;
    }
    var page = observation.page();
    e.sourceCursor = page.next() < 0 ? 0 : page.next();
    if (++e.pages > WorldStorageSearch.MAX_PAGES) {
      e.pages = 0;
      e.sourceCursor = 0;
      retry(e, OperationResult.Reason.TECHNICAL_LIMIT);
      return;
    }
    for (int offset = 0; offset < page.entries().size(); offset++) {
      int index = Math.floorMod(e.pick + offset, page.entries().size());
      var row = page.entries().get(index);
      if (row.stored() == 0 || !matches(d, row.key())) continue;
      e.pick = (index + 1) % page.entries().size();
      move(
          e,
          d,
          row.key(),
          Math.min(e.owner.settings().rate(), row.stored()),
          WorldStorageRoutes.Direction.TO_STORAGE);
      return;
    }
    if (page.next() < 0) {
      e.pages = 0;
      retry(e, OperationResult.Reason.SHORTAGE);
    } else wake(e);
  }

  private long room(Entry e, MachinePresets.Deployment d) {
    if (d.mode() != MachinePresets.Mode.STOCK) return e.owner.settings().rate();
    var stock =
        networks.connectors().matchingStock(actor(d), e.owner.position(), e.handle, d.filter());
    if (stock.reason() != OperationResult.Reason.OK) {
      e.reason = stock.reason();
      return -1;
    }
    return new StockTarget(d.filter().kind(), d.target(), e.owner.settings().rate())
        .missing(stock.amount(), 0);
  }

  private boolean cursor(Entry e, WorldStorageCatalog.Cursor value) {
    long charge =
        value == null
            ? 0
            : 512
                + (value.afterKey() == null
                    ? 0
                    : value.afterKey().componentByteCount()
                        + 3L * value.afterKey().registryId().length());
    if (cursorBytes - e.cursorCharge > MAX_CURSOR_BYTES - charge) return false;
    cursorBytes += charge - e.cursorCharge;
    e.cursorCharge = charge;
    e.cursor = value;
    return true;
  }

  private void exportPage(Entry e, MachinePresets.Deployment d) {
    long missing = room(e, d);
    if (missing <= 0) {
      retry(e, missing == 0 ? OperationResult.Reason.OK : e.reason);
      return;
    }
    var id = UUID.randomUUID();
    e.request = id;
    e.route = false;
    admitted(
        e,
        networks
            .transfers()
            .allKeys()
            .page(
                id,
                actor(d),
                e.owner.position(),
                d.filter().kind(),
                e.cursor,
                page -> {
                  if (entries.get(e.node) != e || !id.equals(e.request)) return;
                  e.request = null;
                  if (!valid(e, d) || page.reason() != OperationResult.Reason.OK) {
                    cursor(e, null);
                    e.pages = 0;
                    retry(
                        e,
                        page.reason() == OperationResult.Reason.OK
                            ? OperationResult.Reason.DENIED
                            : page.reason());
                    return;
                  }
                  if (++e.pages > WorldStorageSearch.MAX_PAGES || !cursor(e, page.next())) {
                    cursor(e, null);
                    e.pages = 0;
                    retry(e, OperationResult.Reason.TECHNICAL_LIMIT);
                    return;
                  }
                  for (int offset = 0; offset < page.rows().size(); offset++) {
                    int index = Math.floorMod(e.pick + offset, page.rows().size());
                    var row = page.rows().get(index);
                    if (row.total() == 0 || !matches(d, row.key())) continue;
                    e.pick = (index + 1) % page.rows().size();
                    long amount = room(e, d);
                    if (amount <= 0) {
                      retry(e, amount == 0 ? OperationResult.Reason.OK : e.reason);
                      return;
                    }
                    if (page.next() == null) e.pages = 0;
                    move(e, d, row.key(), amount, WorldStorageRoutes.Direction.FROM_STORAGE);
                    return;
                  }
                  if (page.next() == null) {
                    e.pages = 0;
                    retry(e, OperationResult.Reason.SHORTAGE);
                  } else wake(e);
                }));
  }

  private void move(
      Entry e,
      MachinePresets.Deployment d,
      ResourceKey key,
      long amount,
      WorldStorageRoutes.Direction direction) {
    var id = UUID.randomUUID();
    e.request = id;
    e.route = true;
    admitted(
        e,
        networks
            .transfers()
            .routes()
            .priorityGuarded(
                id,
                actor(d),
                e.owner.position(),
                new WorldStorageTransfers.External(e.handle),
                key,
                amount,
                direction,
                r -> finish(e, id, r.move(), 0),
                () -> valid(e, d) && matches(d, key),
                () ->
                    saved.transfers().unresolved(e.handle.backing()) == null
                        && (d.mode() != MachinePresets.Mode.STOCK || room(e, d) >= amount)));
  }

  private void admitted(Entry e, WorldStorageTransfers.Submission submission) {
    if (submission.rejection() != OperationResult.Reason.OK) {
      e.request = null;
      retry(e, submission.rejection());
    } else activate(e);
  }

  private void finish(Entry e, UUID request, WorldStorageTransfers.Result result, long before) {
    var move = result.transfer();
    if (move != null
        && move.state() == StagedTransfer.State.COMPLETE
        && move.pending() == StagedTransfer.PendingCall.NONE
        && move.staged() == 0
        && saved.transfers().contains(result.id())) saved.transfers().retire(result.id());
    if (entries.get(e.node) != e || !request.equals(e.request)) return;
    e.request = null;
    e.reason = result.reason();
    if (move != null && move.delivered() > before) {
      e.pages = 0;
      wake(e);
    } else retry(e, result.reason());
  }
}
