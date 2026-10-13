package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.resource.TransferRegistry;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Active-only import coordinator; all inventory discovery and mutations use shared scheduling. */
public final class WorldBufferImports {
  public static final int VISITS_PER_TICK = 8;
  private static final int RETRY_TICKS = 20;

  public record Status(BufferRegistry.Mode mode, OperationResult.Reason reason, boolean waiting) {}

  private static final class Entry {
    final BufferedInterfaceEntity owner;
    final UUID backing;
    boolean active;
    boolean scheduled;
    UUID scheduledNetwork;
    UUID scheduledPrincipal;
    UUID request;
    boolean progress;
    int slot;
    int nextTick;
    OperationResult.Reason reason = OperationResult.Reason.SHORTAGE;

    Entry(BufferedInterfaceEntity owner) {
      this.owner = owner;
      backing = owner.handle().id();
    }

    String task() {
      return "buffer-import:" + backing;
    }
  }

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final DeviceSavedData saved;
  private final Map<UUID, Entry> entries = new HashMap<>();
  private final ArrayDeque<Entry> active = new ArrayDeque<>();

  public WorldBufferImports(MinecraftServer server, WorldNetworks networks) {
    this.server = server;
    this.networks = networks;
    saved = DeviceSavedData.get(server);
    saved.buffers().activity(this::wake);
  }

  public void register(BufferedInterfaceEntity owner) {
    checkThread();
    owner.validatePlaced();
    var existing = entries.get(owner.handle().id());
    if (existing != null) {
      if (existing.owner != owner) throw new IllegalStateException("Import buffer already bound");
      wake(existing.backing);
      return;
    }
    if (entries.size() == BufferRegistry.MAX_BUFFERS)
      throw new IllegalStateException("Import holder limit");
    var entry = new Entry(owner);
    entries.put(entry.backing, entry);
    wake(entry.backing);
  }

  /** Matching holders only; queued guards reject after unregister, and owned staging survives. */
  public void unregister(BufferedInterfaceEntity owner) {
    checkThread();
    if (owner.handle() == null) return;
    var entry = entries.get(owner.handle().id());
    if (entry == null || entry.owner != owner) return;
    entries.remove(entry.backing);
    active.remove(entry);
    entry.active = false;
  }

  public Status status(BufferedInterfaceEntity owner) {
    checkThread();
    var deployment = saved.buffers().deployment(owner.handle().id());
    var entry = entries.get(owner.handle().id());
    return new Status(
        deployment == null ? BufferRegistry.Mode.PAUSED : deployment.mode(),
        entry == null || entry.owner != owner ? OperationResult.Reason.OFFLINE : entry.reason,
        entry != null && entry.owner == owner && (entry.request != null || entry.scheduled));
  }

  private void wake(UUID backing) {
    var entry = entries.get(backing);
    if (entry == null) return;
    entry.nextTick = 0;
    activate(entry);
  }

  private void activate(Entry entry) {
    if (!entry.active && entries.get(entry.backing) == entry) {
      entry.active = true;
      active.addLast(entry);
    }
  }

  /** Tick-boundary metadata only; at most eight entries schedule one sixteen-credit preparation. */
  public void tick() {
    checkThread();
    int visits = Math.min(VISITS_PER_TICK, active.size());
    for (int index = 0; index < visits; index++) {
      var entry = active.removeFirst();
      entry.active = false;
      if (entries.get(entry.backing) != entry) continue;
      if (entry.scheduled) {
        if (networks
            .scheduler()
            .isQueued(entry.scheduledNetwork, entry.scheduledPrincipal, entry.task())) {
          activate(entry);
          continue;
        }
        entry.scheduled = false;
      }
      if (entry.request != null) {
        boolean pending =
            entry.progress
                ? networks.transfers().routeTicketActive(entry.request)
                : networks.transfers().routes().active(entry.request);
        if (pending) {
          activate(entry);
          continue;
        }
        entry.request = null;
      }
      if (server.getTickCount() < entry.nextTick) {
        activate(entry);
        continue;
      }
      var deployment = saved.buffers().deployment(entry.backing);
      if (deployment == null || deployment.mode() == BufferRegistry.Mode.PAUSED) {
        entry.reason = OperationResult.Reason.OK;
        continue;
      }
      entry.scheduledNetwork = deployment.network();
      entry.scheduledPrincipal = deployment.principal();
      var admission =
          networks
              .scheduler()
              .submit(
                  deployment.network(),
                  deployment.principal(),
                  entry.task(),
                  16,
                  () -> {
                    entry.scheduled = false;
                    active.remove(entry);
                    entry.active = false;
                    if (entries.get(entry.backing) == entry) step(entry, deployment);
                  });
      if (admission == FairScheduler.Admission.ACCEPTED
          || admission == FairScheduler.Admission.REPLACED) {
        entry.scheduled = true;
        activate(entry);
      } else retry(entry, OperationResult.Reason.TECHNICAL_LIMIT);
    }
  }

  private OperationResult.Reason validate(Entry entry, BufferRegistry.Deployment deployment) {
    if (entries.get(entry.backing) != entry
        || !deployment.equals(saved.buffers().deployment(entry.backing)))
      return OperationResult.Reason.DENIED;
    try {
      entry.owner.validatePlaced();
    } catch (IllegalStateException | IllegalArgumentException unavailable) {
      return OperationResult.Reason.OFFLINE;
    }
    if (deployment.mode() != BufferRegistry.Mode.IMPORT
        || deployment.revision() != entry.owner.reference().configuration()
        || deployment.leaseGeneration() != entry.owner.handle().generation())
      return OperationResult.Reason.DENIED;
    var member = networks.membership(entry.owner.position());
    if (member.status() != NetworkTopology.Status.VALID) return OperationResult.Reason.OFFLINE;
    if (!member.scope().network().equals(deployment.network())
        || !member.scope().segment().equals(deployment.segment()))
      return OperationResult.Reason.DENIED;
    var actor = new NetworkPermissions.Actor(deployment.principal(), false);
    for (var permission :
        new NetworkPermissions.Permission[] {
          NetworkPermissions.Permission.DEPLOY, NetworkPermissions.Permission.VIEW,
          NetworkPermissions.Permission.DEPOSIT, NetworkPermissions.Permission.WITHDRAW
        })
      if (networks.authorize(actor, entry.owner.position(), permission)
          != NetworkAuthority.Decision.ALLOWED) return OperationResult.Reason.DENIED;
    return OperationResult.Reason.OK;
  }

  private void step(Entry entry, BufferRegistry.Deployment deployment) {
    var reason = validate(entry, deployment);
    if (reason != OperationResult.Reason.OK) {
      retry(entry, reason);
      return;
    }
    var actor = new NetworkPermissions.Actor(deployment.principal(), false);
    var unresolved = saved.transfers().unresolved(entry.backing);
    if (unresolved != null) {
      if (unresolved.transfer().pending() != StagedTransfer.PendingCall.NONE
          || unresolved.transfer().state() == StagedTransfer.State.QUARANTINED) {
        retry(entry, OperationResult.Reason.UNCERTAIN);
        return;
      }
      if (!owns(unresolved, entry, deployment)) {
        retry(entry, OperationResult.Reason.RESERVED);
        return;
      }
      entry.request = unresolved.id();
      entry.progress = true;
      var admission =
          networks
              .transfers()
              .progressGuarded(
                  unresolved.id(),
                  actor,
                  false,
                  result ->
                      finish(entry, unresolved.id(), result, unresolved.transfer().delivered()),
                  () -> validate(entry, deployment) == OperationResult.Reason.OK);
      admitted(entry, admission);
      return;
    }
    var inventory = entry.owner.inventory();
    for (int offset = 0; offset < ItemBuffer.SLOTS; offset++) {
      int index = (entry.slot + offset) % ItemBuffer.SLOTS;
      var slot = inventory.slot(index);
      if (slot.key() == null) continue;
      entry.slot = (index + 1) % ItemBuffer.SLOTS;
      var id = UUID.randomUUID();
      entry.request = id;
      entry.progress = false;
      var admission =
          networks
              .transfers()
              .routes()
              .priorityGuarded(
                  id,
                  actor,
                  entry.owner.position(),
                  new WorldStorageTransfers.Buffered(entry.owner.handle(), deployment.revision()),
                  slot.key(),
                  Math.min(64, inventory.total(slot.key())),
                  WorldStorageRoutes.Direction.TO_STORAGE,
                  result -> finish(entry, id, result.move(), 0),
                  () -> validate(entry, deployment) == OperationResult.Reason.OK,
                  () -> saved.transfers().unresolved(entry.backing) == null);
      admitted(entry, admission);
      return;
    }
    entry.reason = OperationResult.Reason.SHORTAGE;
  }

  private static boolean owns(
      TransferRegistry.Stored record, Entry entry, BufferRegistry.Deployment deployment) {
    var context = record.context();
    return context != null
        && context.destination().type() != TransferContext.Type.PLAYER
        && record.transfer().owner().equals(deployment.principal())
        && context.network().equals(deployment.network())
        && context.segment().equals(deployment.segment())
        && context.origin().equals(entry.owner.position())
        && context.source().type() == TransferContext.Type.BUFFER
        && context.source().backing().equals(entry.backing)
        && context.source().generation() == deployment.leaseGeneration()
        && context.source().configuration() == deployment.revision();
  }

  private void admitted(Entry entry, WorldStorageTransfers.Submission admission) {
    if (admission.rejection() != OperationResult.Reason.OK) {
      entry.request = null;
      retry(entry, admission.rejection());
    } else activate(entry);
  }

  private void finish(
      Entry entry, UUID request, WorldStorageTransfers.Result result, long deliveredBefore) {
    var transfer = result.transfer();
    if (transfer != null
        && transfer.state() == StagedTransfer.State.COMPLETE
        && transfer.pending() == StagedTransfer.PendingCall.NONE
        && transfer.staged() == 0
        && saved.transfers().contains(result.id())) saved.transfers().retire(result.id());
    if (entries.get(entry.backing) != entry || !request.equals(entry.request)) return;
    entry.request = null;
    if (transfer != null && transfer.delivered() > deliveredBefore) wake(entry.backing);
    else retry(entry, result.reason());
    entry.reason = result.reason();
  }

  private void retry(Entry entry, OperationResult.Reason reason) {
    entry.reason = reason;
    entry.nextTick = server.getTickCount() + RETRY_TICKS;
    activate(entry);
  }

  private void checkThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("Buffer imports require server thread");
  }
}
