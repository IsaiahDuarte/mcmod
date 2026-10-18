package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Server-thread index of loaded Crafting Units and the patterns they offer to their own segment.
 * Each query rebuilds from loaded holders under fixed bounds, so there is no invalidation state; it
 * grants no resource or recipe authority.
 */
public final class WorldCrafting {
  public static final int MAX_UNITS = 1_024;
  public static final int MAX_PATTERNS = 4_096;
  private static final Map<MinecraftServer, WorldCrafting> SERVERS = new HashMap<>();

  /** A pattern and the loaded unit offering it; the first unit in stable order wins duplicates. */
  public record Offer(
      Pattern pattern, PatternContents contents, CraftingUnitEntity unit, int slot) {}

  /** Patterns visible to one segment, keyed by output. Overflow beyond the bounds is reported. */
  public record Index(Map<ResourceKey, List<Offer>> byOutput, boolean truncated) {
    public Collection<Pattern> producers(ResourceKey key) {
      return byOutput.getOrDefault(key, List.of()).stream().map(Offer::pattern).toList();
    }

    public Offer offer(UUID pattern) {
      for (var offers : byOutput.values())
        for (var offer : offers) if (offer.pattern().id().equals(pattern)) return offer;
      return null;
    }
  }

  private final MinecraftServer server;
  private final Set<CraftingUnitEntity> units = new LinkedHashSet<>();
  private boolean overflow;

  private WorldCrafting(MinecraftServer server) {
    this.server = server;
  }

  public static WorldCrafting get(MinecraftServer server) {
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
    return SERVERS.computeIfAbsent(server, WorldCrafting::new);
  }

  static boolean exists(MinecraftServer server) {
    return SERVERS.containsKey(server);
  }

  public static void stopped(ServerStoppedEvent event) {
    SERVERS.remove(event.getServer());
  }

  void loaded(CraftingUnitEntity unit) {
    if (units.size() >= MAX_UNITS) {
      overflow = true;
      return;
    }
    units.add(unit);
  }

  void removed(CraftingUnitEntity unit) {
    units.remove(unit);
  }

  /**
   * Patterns from current, VALID units in exactly {@code scope}'s network and segment. Gateway
   * publication of child recipes is not included.
   */
  public Index index(NetworkTopology.Scope scope) {
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
    var networks = WorldNetworks.get(server);
    var codec = new PlatformResourceCodec(server.registryAccess());
    var members = new ArrayList<CraftingUnitEntity>();
    var iterator = units.iterator();
    while (iterator.hasNext()) {
      var unit = iterator.next();
      if (!unit.current()) {
        iterator.remove();
        continue;
      }
      var membership = networks.membership(unit.origin());
      if (membership.status() != NetworkTopology.Status.VALID
          || !membership.scope().network().equals(scope.network())
          || !membership.scope().segment().equals(scope.segment())) continue;
      members.add(unit);
    }
    members.sort(
        Comparator.comparing((CraftingUnitEntity unit) -> unit.origin().dimension())
            .thenComparingLong(unit -> unit.origin().block()));
    var byOutput = new LinkedHashMap<ResourceKey, List<Offer>>();
    var seen = new HashMap<UUID, Boolean>();
    int count = 0;
    boolean truncated = overflow;
    for (var unit : members)
      for (int slot = 0; slot < CraftingUnitEntity.SLOTS; slot++) {
        var contents = unit.patterns().getStackInSlot(slot).get(CraftingContent.PATTERN.get());
        if (contents == null || seen.putIfAbsent(contents.id(), true) != null) continue;
        if (count == MAX_PATTERNS) {
          truncated = true;
          continue;
        }
        Pattern pattern;
        try {
          pattern = contents.pattern(codec);
        } catch (IllegalArgumentException unsupported) {
          continue;
        }
        byOutput
            .computeIfAbsent(pattern.output(), key -> new ArrayList<>())
            .add(new Offer(pattern, contents, unit, slot));
        count++;
      }
    return new Index(byOutput, truncated);
  }
}
