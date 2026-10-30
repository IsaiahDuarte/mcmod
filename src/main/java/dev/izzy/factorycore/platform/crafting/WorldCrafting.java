package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.ServerThread;
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

/**
 * Server-thread index of loaded Crafting Units and the patterns they offer to their own segment.
 * Each query rebuilds from loaded holders under fixed bounds, so there is no invalidation state; it
 * grants no resource or recipe authority.
 */
public final class WorldCrafting {
  public static final int MAX_UNITS = 1_024;
  public static final int MAX_PATTERNS = 4_096;

  /** Holders one crafting-table offer spreads its crafts across (E3). */
  public static final int MAX_PARALLEL = 32;

  private static final ServerScoped<WorldCrafting> SERVERS = ServerScoped.of(WorldCrafting::new);

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
      boolean published,
      List<PatternHolderEntity> also) {
    /**
     * E3: {@code also} lists other loaded holders in the segment offering the same crafting-table
     * pattern (same ID and contents), in stable order; jobs spread crafts across all of them.
     */
    public Offer {
      also = List.copyOf(also);
    }

    /** Every holder that can run this offer's crafting-table crafts, the first one first. */
    public List<PatternHolderEntity> holders() {
      var all = new ArrayList<PatternHolderEntity>(1 + also.size());
      all.add(unit);
      all.addAll(also);
      return all;
    }
  }

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
    ServerThread.require(server, "Crafting");
    return SERVERS.get(server);
  }

  static boolean exists(MinecraftServer server) {
    return SERVERS.peek(server) != null;
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

  /**
   * A loaded holder that offers patterns to a segment; {@code published} when it sits in a
   * descendant branch and offers them through its gateways.
   */
  public record Visible(PatternHolderEntity unit, boolean published) {}

  /**
   * Current, VALID holders whose patterns {@code scope}'s segment can use, in stable order
   * (dimension, then position): its own, plus those of descendant branches that publish to it.
   */
  public List<Visible> visible(NetworkTopology.Scope scope) {
    ServerThread.require(server, "Crafting");
    var networks = WorldNetworks.get(server);
    var members = new ArrayList<Visible>();
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
      if (membership.scope().segment().equals(scope.segment()))
        members.add(new Visible(unit, false));
      else if (publishes(networks, unit, scope)) members.add(new Visible(unit, true));
    }
    members.sort(
        Comparator.comparing((Visible visible) -> visible.unit().origin().dimension())
            .thenComparingLong(visible -> visible.unit().origin().block()));
    return members;
  }

  public Index index(NetworkTopology.Scope scope) {
    var codec = new PlatformResourceCodec(server.registryAccess());
    var members = new ArrayList<PatternHolderEntity>();
    var published = new HashSet<PatternHolderEntity>();
    for (var visible : visible(scope)) {
      members.add(visible.unit());
      if (visible.published()) published.add(visible.unit());
    }
    var byOutput = new LinkedHashMap<ResourceKey, List<Offer>>();
    var seen = new HashMap<UUID, Boolean>();
    var first = new HashMap<UUID, Offer>();
    var extra = new HashMap<UUID, List<PatternHolderEntity>>();
    int count = 0;
    boolean truncated = overflow;
    for (var unit : members)
      for (int slot = 0; slot < PatternHolderEntity.SLOTS; slot++) {
        var stack = unit.patterns().getStackInSlot(slot);
        var contents = stack.get(CraftingContent.PATTERN.get());
        var processing = stack.get(CraftingContent.PROCESSING.get());
        UUID id = contents != null ? contents.id() : processing != null ? processing.id() : null;
        if (id == null) continue;
        if (seen.putIfAbsent(id, true) != null) {
          // E3: another holder of the same crafting-table pattern can share its crafts.
          var earlier = first.get(id);
          if (contents != null
              && earlier != null
              && earlier.contents() != null
              && earlier.contents().equals(contents)
              && earlier.unit() != unit
              && !published.contains(unit) == !earlier.published()) {
            var list = extra.computeIfAbsent(id, key -> new ArrayList<>());
            if (!list.contains(unit) && list.size() < MAX_PARALLEL - 1) list.add(unit);
          }
          continue;
        }
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
        var offer =
            new Offer(
                pattern, contents, processing, unit, slot, published.contains(unit), List.of());
        first.put(id, offer);
        byOutput.computeIfAbsent(pattern.output(), key -> new ArrayList<>()).add(offer);
        count++;
      }
    if (!extra.isEmpty())
      for (var offers : byOutput.values())
        offers.replaceAll(
            offer -> {
              var more = extra.get(offer.pattern().id());
              return more == null
                  ? offer
                  : new Offer(
                      offer.pattern(),
                      offer.contents(),
                      offer.processing(),
                      offer.unit(),
                      offer.slot(),
                      offer.published(),
                      more);
            });
    return new Index(byOutput, truncated);
  }
}
