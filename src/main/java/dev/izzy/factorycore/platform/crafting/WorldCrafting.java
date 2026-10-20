package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

  /**
   * A pattern and the loaded holder offering it; the first holder in stable order wins duplicates.
   * Exactly one of {@code contents} (crafting) and {@code processing} is present. {@code published}
   * marks a pattern held in a descendant branch and offered through its gateways.
   */
  public record Offer(
      Pattern pattern,
      PatternContents contents,
      ProcessingContents processing,
      PatternHolderEntity unit,
      int slot,
      boolean published) {}

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
  private final Set<PatternHolderEntity> units = new LinkedHashSet<>();
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

  void loaded(PatternHolderEntity unit) {
    if (units.size() >= MAX_UNITS) {
      overflow = true;
      return;
    }
    units.add(unit);
  }

  void removed(PatternHolderEntity unit) {
    units.remove(unit);
  }

  /**
   * Patterns from current, VALID holders in {@code scope}'s segment, plus patterns published by
   * descendant branches: a branch publishes to an ancestor when every gateway on the path allows
   * CRAFT for that branch. Published patterns add no storage or machine handles to the ancestor.
   */
  private static boolean publishes(
      WorldNetworks networks, PatternHolderEntity unit, NetworkTopology.Scope scope) {
    for (var ancestor : networks.ancestors(unit.origin())) {
      if (!ancestor.policy().branchPermissions().contains(NetworkPermissions.Permission.CRAFT))
        return false;
      if (ancestor.segment().equals(scope.segment())) return true;
    }
    return false;
  }

  public Index index(NetworkTopology.Scope scope) {
    if (!server.isSameThread()) throw new IllegalStateException("Crafting requires server thread");
    var networks = WorldNetworks.get(server);
    var codec = new PlatformResourceCodec(server.registryAccess());
    var members = new ArrayList<PatternHolderEntity>();
    var published = new HashSet<PatternHolderEntity>();
    var iterator = units.iterator();
    while (iterator.hasNext()) {
      var unit = iterator.next();
      if (!unit.current()) {
        iterator.remove();
        continue;
      }
      var membership = networks.membership(unit.origin());
      if (membership.status() != NetworkTopology.Status.VALID
          || !membership.scope().network().equals(scope.network())) continue;
      if (membership.scope().segment().equals(scope.segment())) members.add(unit);
      else if (publishes(networks, unit, scope)) {
        members.add(unit);
        published.add(unit);
      }
    }
    members.sort(
        Comparator.comparing((PatternHolderEntity unit) -> unit.origin().dimension())
            .thenComparingLong(unit -> unit.origin().block()));
    var byOutput = new LinkedHashMap<ResourceKey, List<Offer>>();
    var seen = new HashMap<UUID, Boolean>();
    int count = 0;
    boolean truncated = overflow;
    for (var unit : members)
      for (int slot = 0; slot < PatternHolderEntity.SLOTS; slot++) {
        var stack = unit.patterns().getStackInSlot(slot);
        var contents = stack.get(CraftingContent.PATTERN.get());
        var processing = stack.get(CraftingContent.PROCESSING.get());
        UUID id = contents != null ? contents.id() : processing != null ? processing.id() : null;
        if (id == null || seen.putIfAbsent(id, true) != null) continue;
        if (count == MAX_PATTERNS) {
          truncated = true;
          continue;
        }
        Pattern pattern;
        try {
          pattern = contents != null ? contents.pattern(codec) : processing.pattern(codec);
        } catch (IllegalArgumentException unsupported) {
          continue;
        }
        byOutput
            .computeIfAbsent(pattern.output(), key -> new ArrayList<>())
            .add(new Offer(pattern, contents, processing, unit, slot, published.contains(unit)));
        count++;
      }
    return new Index(byOutput, truncated);
  }
}
