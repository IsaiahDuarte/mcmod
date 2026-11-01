package dev.izzy.factorycore.platform.monitor;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Q28: reads each loaded Storage Monitor's count at most once per {@link #INTERVAL} ticks, through
 * the same bounded, scheduled and View-checked catalog as the terminal and Signal Interfaces, under
 * the grants of whoever chose its item. A monitor that cannot read shows no number.
 */
public final class WorldMonitors {
  public static final int INTERVAL = 20;

  /** Loaded monitors tracked per server; more are not read and show no number. */
  public static final int MAX_MONITORS = 4096;

  /** Catalog pages one reading may walk before giving up. */
  public static final int MAX_PAGES = 640;

  private static final ServerScoped<WorldMonitors> SERVERS = ServerScoped.of(WorldMonitors::new);

  private final MinecraftServer server;
  private final Set<MonitorEntity> monitors = new LinkedHashSet<>();
  private final Map<MonitorEntity, Long> due = new HashMap<>();
  private final Set<MonitorEntity> counting = new HashSet<>();

  private WorldMonitors(MinecraftServer server) {
    this.server = server;
  }

  public static WorldMonitors get(MinecraftServer server) {
    ServerThread.require(server, "Monitors");
    return SERVERS.get(server);
  }

  public static void postTick(ServerTickEvent.Post event) {
    if (DeviceSavedData.get(event.getServer()).diagnostic().isEmpty())
      get(event.getServer()).tick();
  }

  void loaded(MonitorEntity monitor) {
    if (monitors.size() < MAX_MONITORS) monitors.add(monitor);
  }

  void removed(MonitorEntity monitor) {
    monitors.remove(monitor);
    due.remove(monitor);
    counting.remove(monitor);
  }

  /** Reads again at the next tick (new item, or a move through it). */
  void dueNow(MonitorEntity monitor) {
    due.put(monitor, Long.MIN_VALUE);
  }

  private void tick() {
    long now = server.getTickCount();
    for (var monitor : List.copyOf(monitors)) {
      if (!monitor.current()) {
        removed(monitor);
        continue;
      }
      if (counting.contains(monitor) || due.getOrDefault(monitor, Long.MIN_VALUE) > now) continue;
      due.put(monitor, now + INTERVAL);
      read(monitor);
    }
  }

  private void read(MonitorEntity monitor) {
    var key = monitor.shown();
    var owner = monitor.owner();
    if (key == null || owner == null) {
      monitor.count(-1);
      return;
    }
    var networks = WorldNetworks.get(server);
    var origin = monitor.origin();
    var actor = new NetworkPermissions.Actor(owner, false);
    if (networks.membership(origin).status() != NetworkTopology.Status.VALID
        || !networks.allows(actor, origin, NetworkPermissions.Permission.VIEW)) {
      monitor.count(-1);
      return;
    }
    counting.add(monitor);
    count(monitor, actor, key, null, 0, 0);
  }

  private void count(
      MonitorEntity monitor,
      NetworkPermissions.Actor actor,
      dev.izzy.factorycore.core.resource.ResourceKey key,
      WorldStorageTransfers.CatalogCursor cursor,
      long total,
      int pages) {
    var submission =
        WorldNetworks.get(server)
            .transfers()
            .catalog(
                UUID.randomUUID(),
                actor,
                monitor.origin(),
                key,
                cursor,
                page -> {
                  if (!counting.contains(monitor) || !monitor.current()) {
                    counting.remove(monitor);
                    return;
                  }
                  // The item changed while counting: the next reading counts the new one.
                  if (!key.equals(monitor.shown())) {
                    counting.remove(monitor);
                    return;
                  }
                  if (page.reason() != OperationResult.Reason.OK) {
                    finish(monitor, -1);
                    return;
                  }
                  long sum = total;
                  for (var row : page.rows()) {
                    long next = sum + row.total();
                    sum = next < sum ? Long.MAX_VALUE : next;
                  }
                  if (page.next() == null) finish(monitor, sum);
                  else if (pages + 1 >= MAX_PAGES) finish(monitor, -1);
                  else count(monitor, actor, key, page.next(), sum, pages + 1);
                });
    if (submission.rejection() != OperationResult.Reason.OK) finish(monitor, -1);
  }

  private void finish(MonitorEntity monitor, long count) {
    counting.remove(monitor);
    monitor.count(count);
  }
}
