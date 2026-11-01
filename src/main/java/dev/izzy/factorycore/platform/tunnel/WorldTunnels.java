package dev.izzy.factorycore.platform.tunnel;

import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Q37: the loaded tunnels of each frequency. Only loaded tunnels are known; nothing loads one. */
final class WorldTunnels {
  private static final ServerScoped<WorldTunnels> SERVERS = ServerScoped.of(WorldTunnels::new);

  private final Map<UUID, Set<TunnelEntity>> frequencies = new HashMap<>();

  private WorldTunnels(MinecraftServer server) {}

  static WorldTunnels get(MinecraftServer server) {
    ServerThread.require(server, "Tunnels");
    return SERVERS.get(server);
  }

  void add(UUID frequency, TunnelEntity tunnel) {
    if (frequency != null)
      frequencies.computeIfAbsent(frequency, any -> new LinkedHashSet<>()).add(tunnel);
  }

  void remove(UUID frequency, TunnelEntity tunnel) {
    if (frequency == null) return;
    var members = frequencies.get(frequency);
    if (members == null) return;
    members.remove(tunnel);
    if (members.isEmpty()) frequencies.remove(frequency);
  }

  List<TunnelEntity> members(UUID frequency) {
    var members = frequency == null ? null : frequencies.get(frequency);
    return members == null ? List.of() : new ArrayList<>(members);
  }
}
