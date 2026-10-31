package dev.izzy.factorycore.platform.power;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Q25: the loaded devices that pay their own FE running cost (through a {@link
 * RateLimitedEnergyInput}), so the network Overview can say what a segment uses and who uses most.
 * Devices join on load and leave on removal or unload; at most {@link #MAX_USERS} are tracked, and
 * a summary walks only those. Server thread only.
 */
public final class EnergyUsers {
  public enum Kind {
    ACCESS_POINT,
    PROGRAM_CONTROLLER,
    WIRELESS_LINK
  }

  /** A device that spends its own FE. */
  public interface User {
    /** What it is, or null while it uses no energy (a Network Node without a Wireless Link). */
    Kind energyKind();

    NodeRegistry.Position origin();

    /** Still the loaded block entity at its position. */
    boolean current();

    /** FE per tick it spends, averaged as in {@link RateLimitedEnergyInput#use}. */
    double energyUse();
  }

  public static final int MAX_USERS = 4096;

  /** How many of the largest users a summary lists. */
  public static final int TOP = 5;

  /** One device's use. */
  public record Use(Kind kind, double perTick) {}

  /**
   * A segment's energy use: the total FE per tick, the device count and use for each {@link Kind}
   * (by ordinal), and the {@link #TOP} largest users, largest first.
   */
  public record Summary(double total, int[] counts, double[] uses, List<Use> top) {
    public Summary {
      counts = counts.clone();
      uses = uses.clone();
      top = List.copyOf(top);
    }
  }

  private static final ServerScoped<EnergyUsers> SERVERS =
      ServerScoped.of(server -> new EnergyUsers());

  private final Set<User> users = new LinkedHashSet<>();

  private EnergyUsers() {}

  public static void loaded(Level level, User user) {
    if (level instanceof ServerLevel server) {
      var tracked = SERVERS.get(server.getServer()).users;
      if (tracked.size() < MAX_USERS) tracked.add(user);
    }
  }

  public static void removed(Level level, User user) {
    if (level instanceof ServerLevel server) {
      var scope = SERVERS.peek(server.getServer());
      if (scope != null) scope.users.remove(user);
    }
  }

  /** The use of every tracked device in {@code scope}'s segment of a valid network. */
  public static Summary summary(ServerLevel level, NetworkTopology.Scope scope) {
    var networks = WorldNetworks.get(level.getServer());
    var tracked = SERVERS.get(level.getServer()).users;
    int kinds = Kind.values().length;
    var counts = new int[kinds];
    var uses = new double[kinds];
    var all = new ArrayList<Use>();
    double total = 0;
    for (var user : List.copyOf(tracked)) {
      if (!user.current()) {
        tracked.remove(user);
        continue;
      }
      var kind = user.energyKind();
      if (kind == null) continue;
      var member = networks.membership(user.origin());
      if (member.status() != NetworkTopology.Status.VALID
          || !member.scope().network().equals(scope.network())
          || !member.scope().segment().equals(scope.segment())) continue;
      double use = user.energyUse();
      counts[kind.ordinal()]++;
      uses[kind.ordinal()] += use;
      total += use;
      all.add(new Use(kind, use));
    }
    all.sort(Comparator.comparingDouble(Use::perTick).reversed());
    return new Summary(total, counts, uses, all.subList(0, Math.min(TOP, all.size())));
  }
}
