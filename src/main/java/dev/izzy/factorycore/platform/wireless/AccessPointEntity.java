package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.power.RateLimitedEnergyInput;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Wired Wireless Access Point: grants handheld terminal access within range of this block while it
 * has FE for each open tick. It changes player access only, never machine discovery, routing or
 * chunk loading. Q15: Throughput and Overdrive raise its range (64, 128, 256 blocks) and its cost
 * per open tick (16, 32, 64 FE).
 */
public final class AccessPointEntity extends BlockEntity
    implements WiredMember, ThroughputUpgradeable {
  public static final long CAPACITY = 20_000;
  public static final long INPUT_RATE = 1_000;

  /** Base range and cost per open tick; see {@link #range()} and {@link #tickCost()}. */
  public static final int RANGE = 64;

  public static final long TICK_COST = 16;

  /** Saved tier tag; absent means BASE, so older Access Points keep their bytes. */
  static final String THROUGHPUT = "FactoryCoreThroughput";

  private ThroughputTier tier = ThroughputTier.BASE;
  private final RateLimitedEnergyInput input =
      new RateLimitedEnergyInput(
          CAPACITY, INPUT_RATE, this::serverTick, () -> true, this::setChanged);

  public AccessPointEntity(BlockPos position, BlockState state) {
    super(WirelessContent.ACCESS_POINT_ENTITY.get(), position, state);
  }

  private long serverTick() {
    return level == null || level.isClientSide() ? -1 : level.getGameTime();
  }

  /** Blocks a handheld may be from this point, by tier. */
  public static int range(ThroughputTier tier) {
    return switch (tier) {
      case BASE -> RANGE;
      case ADVANCED -> 2 * RANGE;
      case ENDGAME -> 4 * RANGE;
    };
  }

  /** FE per tick a handheld session through this point costs, by tier. */
  public static long tickCost(ThroughputTier tier) {
    return switch (tier) {
      case BASE -> TICK_COST;
      case ADVANCED -> 2 * TICK_COST;
      case ENDGAME -> 4 * TICK_COST;
    };
  }

  public int range() {
    return range(tier);
  }

  public long tickCost() {
    return tickCost(tier);
  }

  @Override
  public ThroughputTier tier() {
    return tier;
  }

  /** One tier step under EDIT where the point is; the item is consumed by the caller. */
  @Override
  public boolean upgrade(NetworkPermissions.Actor actor, ThroughputTier item) {
    var next = tier.upgradeWith(item, false);
    if (next == null) throw new IllegalStateException("upgrade_not_applicable");
    if (!(level instanceof ServerLevel server)
        || !WorldNetworks.get(server.getServer())
            .allows(actor, origin(), NetworkPermissions.Permission.EDIT)) return false;
    tier = next;
    setChanged();
    return true;
  }

  @Override
  public Component throughputStatus(MinecraftServer server) {
    return Component.translatable(
        "message.factorycore.access_point_throughput", range(), tickCost());
  }

  public IEnergyStorage energyStorage() {
    return input;
  }

  public long energy() {
    return input.energy();
  }

  /** Spends FE if available; spends nothing and returns false otherwise. */
  public boolean spend(long amount) {
    return input.spend(amount);
  }

  public NodeRegistry.Position origin() {
    return Positions.of(level, worldPosition);
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    WorldNetworks.changed(level, worldPosition);
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.putLong("Energy", input.energy());
    if (tier != ThroughputTier.BASE) tag.putString(THROUGHPUT, tier.name());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    input.restore(tag.getLong("Energy"));
    // An unknown tier name keeps BASE; breaking it then returns no upgrade items.
    var saved = tag.getString(THROUGHPUT);
    tier = ThroughputTier.BASE;
    for (var known : ThroughputTier.values()) if (known.name().equals(saved)) tier = known;
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(null, "access_point", NetworkTopology.Role.TERMINAL, current());
  }
}
