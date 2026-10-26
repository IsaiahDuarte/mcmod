package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.power.RateLimitedEnergyInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Wired Wireless Access Point: grants handheld terminal access within range of this block while it
 * has FE for each open tick. It changes player access only, never machine discovery, routing or
 * chunk loading.
 */
public final class AccessPointEntity extends BlockEntity implements WiredMember {
  public static final long CAPACITY = 20_000;
  public static final long INPUT_RATE = 1_000;
  public static final int RANGE = 64;
  public static final long TICK_COST = 16;
  private final RateLimitedEnergyInput input =
      new RateLimitedEnergyInput(
          CAPACITY, INPUT_RATE, this::serverTick, () -> true, this::setChanged);

  public AccessPointEntity(BlockPos position, BlockState state) {
    super(WirelessContent.ACCESS_POINT_ENTITY.get(), position, state);
  }

  private long serverTick() {
    return level == null || level.isClientSide() ? -1 : level.getGameTime();
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
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    input.restore(tag.getLong("Energy"));
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(null, "access_point", NetworkTopology.Role.TERMINAL, current());
  }
}
