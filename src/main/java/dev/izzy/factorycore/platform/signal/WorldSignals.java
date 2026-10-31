package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformFilterTags;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.WorldStorageCatalog;
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
 * tick. A stock condition walks the same bounded, scheduled and VIEW-checked catalogs as the
 * terminal page by page under the owner's current grants, adding stored totals across drives and
 * Storage-mode containers: for one exact resource its own catalog ({@link
 * WorldStorageTransfers#catalog}); for any other filter (Q24: several slots, IDs or tags) every key
 * of the filter's kind ({@link WorldStorageTransfers#allKeys}), summing those the filter allows. An
 * energy condition counts the network's stored FE the same way. A job condition reads the network's
 * crafting jobs. Offline networks, revoked VIEW and missing settings turn the output off with a
 * reason.
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

  /** Signal Interfaces one {@code emit} may drive (ADR 0062 D6), as for a machine label. */
  public static final int MAX_LABELED = 64;

  /**
   * D6: drives every loaded Signal Interface labelled {@code label} in the segment of {@code
   * origin} at {@code strength} for {@code deployment} until tick {@code until}, then re-evaluates
   * it. Returns how many it drove; the caller has authorized the program at {@code origin}.
   */
  public int emit(
      UUID deployment,
      dev.izzy.factorycore.core.network.NodeRegistry.Position origin,
      String label,
      int strength,
      long until) {
    var networks = WorldNetworks.get(server);
    var from = networks.membership(origin);
    if (from.status() != NetworkTopology.Status.VALID || label.isEmpty()) return 0;
    int count = 0;
    for (var signal : signals) {
      if (!signal.label().equals(label) || !signal.current()) continue;
      var member = networks.membership(signal.origin());
      if (member.status() != NetworkTopology.Status.VALID
          || !member.scope().segment().equals(from.scope().segment())) continue;
      if (++count > MAX_LABELED) break;
      signal.drive(deployment, strength, until);
      dueNow(signal);
    }
    return Math.min(count, MAX_LABELED);
  }

  /** D6: a stopped, paused or replaced deployment stops driving every Signal Interface now. */
  public void release(UUID deployment) {
    for (var signal : signals) if (signal.release(deployment)) dueNow(signal);
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
    int programmed = signal.driven(server.getTickCount());
    if (programmed >= 0) {
      if (WorldNetworks.get(server).membership(signal.origin()).status()
          != NetworkTopology.Status.VALID) signal.output(0, "offline");
      else signal.output(programmed, "program");
      return;
    }
    var owner = signal.owner();
    var rule = signal.rule();
    var watch = signal.watch();
    var resource = signal.resource();
    boolean energy = rule.condition() == SignalRule.Condition.ENERGY;
    if (owner == null
        || !energy && watch == null
        || rule.condition() == SignalRule.Condition.JOB && resource == null) {
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
    if (energy) count(signal, actor, ResourceKey.ENERGY, null, 0, 0);
    else if (watch.terms().size() == 1
        && watch.terms().getFirst() instanceof ResourceFilter.Exact exact)
      count(signal, actor, exact.key(), null, 0, 0);
    else sum(signal, actor, watch, null, 0, 0);
  }

  /** Q24: every key of the filter's kind, page by page, adding those the filter allows. */
  private void sum(
      SignalInterfaceEntity signal,
      NetworkPermissions.Actor actor,
      ResourceFilter watch,
      WorldStorageCatalog.Cursor cursor,
      long total,
      int pages) {
    var submission =
        WorldNetworks.get(server)
            .transfers()
            .allKeys()
            .page(
                UUID.randomUUID(),
                actor,
                signal.origin(),
                watch.kind(),
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
                  // Settings changed while counting: the next evaluation counts the new filter.
                  if (!watch.equals(signal.watch())) {
                    counting.remove(signal);
                    return;
                  }
                  long sum = total;
                  for (var row : page.rows())
                    if (watch.matches(row.key(), new PlatformFilterTags(server)))
                      sum = saturatedAdd(sum, row.total());
                  if (page.next() == null) {
                    finish(signal, signal.rule().strength(sum), "");
                    return;
                  }
                  if (pages + 1 >= MAX_PAGES) {
                    finish(signal, 0, "limit");
                    return;
                  }
                  sum(signal, actor, watch, page.next(), sum, pages + 1);
                });
    if (submission.rejection() != OperationResult.Reason.OK)
      finish(signal, 0, submission.rejection().name().toLowerCase(Locale.ROOT));
  }

  private void count(
      SignalInterfaceEntity signal,
      NetworkPermissions.Actor actor,
      ResourceKey key,
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
                key,
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
                  count(signal, actor, key, page.next(), sum, pages + 1);
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
