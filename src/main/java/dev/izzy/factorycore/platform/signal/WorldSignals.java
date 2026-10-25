package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * C1: evaluates loaded Signal Interfaces at most once per {@link #INTERVAL} ticks each, never per
 * tick. A stock condition walks the same bounded, scheduled and VIEW-checked catalog as the
 * terminal ({@link WorldStorageTransfers#catalog}) page by page under the owner's current grants,
 * adding stored totals of the watched resource across drives and Storage-mode containers. A job
 * condition reads the network's crafting jobs. Offline networks, revoked VIEW and missing settings
 * turn the output off with a reason.
 */
public final class WorldSignals {
  public static final int INTERVAL = 20;

  /** Loaded Signal Interfaces tracked per server; more are not evaluated and stay off. */
  public static final int MAX_SIGNALS = 4096;

  /** Catalog pages one evaluation may walk before giving up with "limit". */
  public static final int MAX_PAGES = 640;

  private static final ServerScoped<WorldSignals> SERVERS = ServerScoped.of(WorldSignals::new);

  private final MinecraftServer server;
  private final Set<SignalInterfaceEntity> signals = new LinkedHashSet<>();
  private final Map<SignalInterfaceEntity, Long> due = new HashMap<>();
  private final Set<SignalInterfaceEntity> counting = new HashSet<>();

  private WorldSignals(MinecraftServer server) {
    this.server = server;
  }

  public static WorldSignals get(MinecraftServer server) {
    ServerThread.require(server, "Signals");
    return SERVERS.get(server);
  }

  public static void postTick(ServerTickEvent.Post event) {
    if (DeviceSavedData.get(event.getServer()).diagnostic().isEmpty())
      get(event.getServer()).tick();
  }

  void loaded(SignalInterfaceEntity signal) {
    if (signals.size() < MAX_SIGNALS) signals.add(signal);
    else signal.output(0, "limit");
  }

  void removed(SignalInterfaceEntity signal) {
    signals.remove(signal);
    due.remove(signal);
    counting.remove(signal);
  }

  /** Re-evaluates at the next tick (new settings), still within the scheduler's budget. */
  void dueNow(SignalInterfaceEntity signal) {
    due.put(signal, Long.MIN_VALUE);
  }

  /** Number of Signal Interfaces being evaluated (for tests and diagnostics). */
  public int tracked() {
    return signals.size();
  }

  private void tick() {
    long now = server.getTickCount();
    for (var signal : List.copyOf(signals)) {
      if (!signal.current()) {
        removed(signal);
        continue;
      }
      if (counting.contains(signal) || due.getOrDefault(signal, Long.MIN_VALUE) > now) continue;
      due.put(signal, now + INTERVAL);
      evaluate(signal);
    }
  }

  private void evaluate(SignalInterfaceEntity signal) {
    var owner = signal.owner();
    var resource = signal.resource();
    if (owner == null || resource == null) {
      signal.output(0, "unset");
      return;
    }
    var networks = WorldNetworks.get(server);
    var origin = signal.origin();
    var member = networks.membership(origin);
    if (member.status() != NetworkTopology.Status.VALID) {
      signal.output(0, "offline");
      return;
    }
    var actor = new NetworkPermissions.Actor(owner, false);
    if (!networks.allows(actor, origin, NetworkPermissions.Permission.VIEW)) {
      signal.output(0, "denied");
      return;
    }
    var rule = signal.rule();
    if (rule.condition() == SignalRule.Condition.JOB) {
      boolean running = false;
      for (var job : DeviceSavedData.get(server).craftingJobs().all())
        if (job.network().equals(member.scope().network())
            && job.request().equals(resource)
            && job.state() != CraftingJob.State.CANCELLING) {
          running = true;
          break;
        }
      signal.output(rule.strength(running), "");
      return;
    }
    counting.add(signal);
    count(signal, actor, null, 0, 0);
  }

  private void count(
      SignalInterfaceEntity signal,
      NetworkPermissions.Actor actor,
      WorldStorageTransfers.CatalogCursor cursor,
      long total,
      int pages) {
    var submission =
        WorldNetworks.get(server)
            .transfers()
            .catalog(
                UUID.randomUUID(),
                actor,
                signal.origin(),
                signal.resource(),
                cursor,
                page -> {
                  if (!counting.contains(signal) || !signal.current()) {
                    counting.remove(signal);
                    return;
                  }
                  if (page.reason() != OperationResult.Reason.OK) {
                    finish(signal, 0, page.reason().name().toLowerCase(Locale.ROOT));
                    return;
                  }
                  long sum = total;
                  for (var row : page.rows()) sum = saturatedAdd(sum, row.total());
                  if (page.next() == null) {
                    finish(signal, signal.rule().strength(sum), "");
                    return;
                  }
                  if (pages + 1 >= MAX_PAGES) {
                    finish(signal, 0, "limit");
                    return;
                  }
                  count(signal, actor, page.next(), sum, pages + 1);
                });
    if (submission.rejection() != OperationResult.Reason.OK)
      finish(signal, 0, submission.rejection().name().toLowerCase(Locale.ROOT));
  }

  private void finish(SignalInterfaceEntity signal, int strength, String reason) {
    counting.remove(signal);
    signal.output(strength, reason);
  }

  private static long saturatedAdd(long a, long b) {
    long sum = a + b;
    return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
  }
}
