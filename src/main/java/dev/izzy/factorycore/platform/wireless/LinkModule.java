package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.power.RateLimitedEnergyInput;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A wireless link installed in a gateway (ADR 0061, rules of ADR 0040/0050). It occupies the
 * gateway's upstream or downstream side; while paired, in range and paying {@link #TICK_COST} FE
 * per tick from its own buffer, discovery adds one reciprocal port from that side's vertex to the
 * partner gateway, so the pair behaves as one cable edge. It never loads chunks; an unloaded
 * partner suspends the link segment. The host gateway persists it under {@link #FIELD}. Q36 (QD10):
 * the partner may be in another dimension, with no range limit and {@link #CROSS_DIMENSION_COST} FE
 * per tick.
 */
public final class LinkModule {
  public static final String FIELD = "FactoryCoreLink";
  public static final long CAPACITY = 20_000;
  public static final long INPUT_RATE = 1_000;
  public static final long TICK_COST = 32;

  /** Q36: per-tick cost of a link whose partner is in another dimension. */
  public static final long CROSS_DIMENSION_COST = 128;

  public static final int RANGE = 256;
  private static final String RECOVERY = "FactoryCoreLinkRecovery";

  private final NetworkNodeEntity host;
  private Direction side;
  private Long partner;
  private String partnerDimension;
  private UUID owner;
  private boolean powered;
  private ThroughputTier tier = ThroughputTier.BASE;
  private CompoundTag rejected;

  private final RateLimitedEnergyInput input =
      new RateLimitedEnergyInput(
          CAPACITY, INPUT_RATE, this::gameTime, () -> rejected == null, this::changed);

  private long gameTime() {
    var level = host.getLevel();
    return level == null || level.isClientSide() ? -1 : level.getGameTime();
  }

  private void changed() {
    host.setChanged();
  }

  /** A freshly installed link on {@code side} of {@code host}, owned by its installer. */
  public LinkModule(NetworkNodeEntity host, Direction side, UUID owner) {
    this.host = host;
    this.side = side;
    this.owner = owner;
  }

  private LinkModule(NetworkNodeEntity host) {
    this.host = host;
  }

  public IEnergyStorage energyStorage() {
    return input;
  }

  public long energy() {
    return input.energy();
  }

  /** Test/diagnostic drain; spends nothing and returns false if unaffordable. */
  public boolean spend(long amount) {
    return input.spend(amount);
  }

  /** FE per tick this link spends (Q25), see {@link RateLimitedEnergyInput#use}. */
  public double use() {
    return input.use();
  }

  public UUID owner() {
    return owner;
  }

  /** The gateway face the link occupies: its upstream or downstream face. */
  public Direction side() {
    return side;
  }

  /** True when the link occupies the gateway's downstream (blue dot) face. */
  public boolean downstream() {
    return side == host.getBlockState().getValue(BlockStateProperties.FACING);
  }

  public BlockPos partner() {
    return partner == null ? null : BlockPos.of(partner);
  }

  /** The partner's dimension id (Q36); the host's own when unset. */
  public String partnerDimension() {
    return partnerDimension != null ? partnerDimension : ownDimension();
  }

  private String ownDimension() {
    var level = host.getLevel();
    return level == null ? null : level.dimension().location().toString();
  }

  /** The partner's network position, in its own dimension; null when unpaired. */
  public NodeRegistry.Position partnerPosition() {
    return partner == null ? null : new NodeRegistry.Position(partnerDimension(), partner);
  }

  /** True when the partner is in another dimension (Q36). */
  public boolean crossDimension() {
    var own = ownDimension();
    return partner != null
        && partnerDimension != null
        && own != null
        && !partnerDimension.equals(own);
  }

  /** FE per tick this link pays while paired. */
  public long tickCost() {
    return crossDimension() ? CROSS_DIMENSION_COST : TICK_COST;
  }

  /** The partner's level, or null when unpaired or that dimension is gone. */
  public ServerLevel partnerLevel() {
    if (partner == null || !(host.getLevel() instanceof ServerLevel own)) return null;
    return own.getServer().getLevel(Loaded.dimension(partnerDimension()));
  }

  /** Sets or clears the partner gateway; callers refresh topology at both ends. */
  void partner(ServerLevel level, BlockPos value) {
    partner = value == null ? null : value.asLong();
    partnerDimension = value == null ? null : level.dimension().location().toString();
    powered = partner != null && input.energy() >= tickCost();
    host.setChanged();
  }

  public BlockPos position() {
    return host.getBlockPos();
  }

  /** The host gateway's network position, in its own dimension. */
  public NodeRegistry.Position origin() {
    return host.position();
  }

  /** The link of the loaded gateway at {@code position}, without loading its chunk. */
  public static LinkModule loaded(ServerLevel level, BlockPos position) {
    var chunk = Loaded.chunk(level, position);
    return chunk != null
            && chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK)
                instanceof NetworkNodeEntity node
        ? node.link()
        : null;
  }

  public static boolean inRange(BlockPos first, BlockPos second) {
    return first.distSqr(second) <= (double) RANGE * RANGE;
  }

  /** Why the link is inactive, or an empty string when it offers its wireless port. */
  public String diagnostic() {
    if (rejected != null) return "unknown_data";
    if (partner == null) return "unpaired";
    if (!crossDimension() && !inRange(position(), partner())) return "out_of_range";
    if (!powered) return "no_power";
    return "";
  }

  public boolean current() {
    return host.current() && host.link() == this;
  }

  public boolean active() {
    return current() && diagnostic().isEmpty();
  }

  /** Current reciprocal pair identity, without loading either gateway's chunk. */
  public WirelessBudget.Link pair() {
    if (!active()) return null;
    var other = partnerOf();
    if (other == null || !other.active()) return null;
    return new WirelessBudget.Link(origin(), other.origin());
  }

  /** Installed throughput upgrade; BASE or ENDGAME (Overdrive) for links. */
  public ThroughputTier tier() {
    return tier;
  }

  /** Installs an Overdrive upgrade after the caller's MANAGE check; re-discovers both ends. */
  public void install(ThroughputTier upgrade) {
    if (rejected != null) throw new IllegalStateException("upgrade_unknown_data");
    var next = tier.upgradeWith(upgrade, true);
    if (next == null) throw new IllegalStateException("upgrade_not_applicable");
    tier = next;
    host.setChanged();
    refresh();
  }

  /** Q19: takes the Overdrive out after the caller's check; returns the removed tier. */
  public ThroughputTier remove() {
    if (rejected != null) throw new IllegalStateException("upgrade_unknown_data");
    var next = tier.removeStep(true);
    if (next == null) throw new IllegalStateException("upgrade_none");
    var removed = tier;
    tier = next;
    host.setChanged();
    refresh();
    return removed;
  }

  /**
   * Per-pair multiplier on the ADR 0050 caps: the Overdrive scale only when both loaded, reciprocal
   * ends carry it; otherwise 1. Never loads the partner chunk.
   */
  public int pairScale() {
    var other = partnerOf();
    return other != null ? Math.min(tier.linkScale(), other.tier.linkScale()) : 1;
  }

  /** True when the partner's chunk (or dimension) is not loaded; never loads it. */
  public boolean partnerUnloaded() {
    var level = partnerLevel();
    return partner != null && (level == null || Loaded.chunk(level, partner()) == null);
  }

  /** The loaded partner link that names this one back, without loading its chunk; else null. */
  public LinkModule partnerOf() {
    var level = partnerLevel();
    if (level == null) return null;
    var other = loaded(level, partner());
    return other != null && other.names(host) ? other : null;
  }

  /** True when this link's partner is {@code gateway}. */
  boolean names(NetworkNodeEntity gateway) {
    return partner != null
        && gateway.getLevel() != null
        && partner == gateway.getBlockPos().asLong()
        && partnerDimension().equals(gateway.getLevel().dimension().location().toString());
  }

  /** Pays operating power every tick; a change in powered state re-discovers both ends. */
  public void serverTick() {
    if (rejected != null || partner == null) return;
    boolean next = input.spend(tickCost());
    if (next != powered) {
      powered = next;
      refresh();
    }
  }

  public void refresh() {
    var level = host.getLevel();
    WorldNetworks.changed(level, position());
    var far = partnerLevel();
    if (far != null) WorldNetworks.changed(far, partner());
  }

  /** Who may install, pair or upgrade: MANAGE on a valid network, else the link's owner. */
  public static boolean mayConfigure(
      NetworkPermissions.Actor actor, NetworkNodeEntity gateway, UUID owner) {
    var origin = Positions.of(gateway.getLevel(), gateway.getBlockPos());
    var networks = WorldNetworks.get(gateway.getLevel().getServer());
    if (networks.membership(origin).status() == NetworkTopology.Status.VALID)
      return networks.allows(actor, origin, NetworkPermissions.Permission.MANAGE);
    return actor.operator() || owner == null || actor.id().equals(owner);
  }

  public boolean mayConfigure(NetworkPermissions.Actor actor) {
    return mayConfigure(actor, host, owner);
  }

  public CompoundTag save() {
    if (rejected != null) return rejected.copy();
    var tag = new CompoundTag();
    tag.putString("Side", side.getSerializedName());
    tag.putLong("Energy", input.energy());
    if (partner != null) tag.putLong("Partner", partner);
    if (partnerDimension != null) tag.putString("PartnerDimension", partnerDimension);
    if (owner != null) tag.putUUID("Owner", owner);
    if (tier != ThroughputTier.BASE) tag.putString("Throughput", tier.name());
    return tag;
  }

  /** Strict reader; unsupported data is kept unchanged and the link stays inert. */
  public static LinkModule load(NetworkNodeEntity host, CompoundTag tag) {
    var link = new LinkModule(host);
    link.side = Direction.byName(tag.getString("Side").toLowerCase(Locale.ROOT));
    link.input.restore(tag.getLong("Energy"));
    link.partner = tag.contains("Partner") ? tag.getLong("Partner") : null;
    link.partnerDimension =
        link.partner != null && tag.contains("PartnerDimension", Tag.TAG_STRING)
            ? tag.getString("PartnerDimension")
            : null;
    link.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    link.powered = link.partner != null && link.input.energy() >= TICK_COST;

    try {
      if (link.side == null) throw new IllegalArgumentException("Missing link side");
      if (link.partnerDimension != null
          && net.minecraft.resources.ResourceLocation.tryParse(link.partnerDimension) == null)
        throw new IllegalArgumentException("Invalid partner dimension");
      if (tag.contains("Throughput")) {
        if (!tag.contains("Throughput", Tag.TAG_STRING))
          throw new IllegalArgumentException("Invalid throughput type");
        link.tier = ThroughputTier.parse(tag.getString("Throughput"));
        if (link.tier != ThroughputTier.ENDGAME)
          throw new IllegalArgumentException("Unsupported link throughput tier");
      }
    } catch (IllegalArgumentException unknown) {
      link.tier = ThroughputTier.BASE;
      link.rejected = tag.copy();
      link.powered = false;
    }
    return link;
  }

  public boolean needsRecovery() {
    return rejected != null;
  }

  /** The item a fresh install consumes, carrying unknown data unchanged when it must. */
  public List<ItemStack> drops() {
    var stack = new ItemStack(WirelessContent.LINK_ITEM.get());
    if (rejected != null) {
      stack.set(
          DataComponents.CUSTOM_NAME, Component.translatable("item.factorycore.link_recovery"));
      CustomData.update(
          DataComponents.CUSTOM_DATA, stack, data -> data.put(RECOVERY, rejected.copy()));
      return List.of(stack);
    }
    var drops = new ArrayList<ItemStack>();
    drops.add(stack);
    drops.addAll(ThroughputContent.installed(tier, true));
    return drops;
  }

  /** A link item's carried recovery data, re-read through the strict loader; else null. */
  public static CompoundTag recovery(ItemStack stack) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    return data.contains(RECOVERY, Tag.TAG_COMPOUND) ? data.getCompound(RECOVERY) : null;
  }
}
