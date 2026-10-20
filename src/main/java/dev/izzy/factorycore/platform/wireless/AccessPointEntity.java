package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
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
  private long energy;
  private long inputTick = Long.MIN_VALUE, inputThisTick;

  private final IEnergyStorage storage =
      new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
          if (amount <= 0 || level == null || level.isClientSide()) return 0;
          long tick = level.getGameTime();
          long used = tick == inputTick ? inputThisTick : 0;
          long accepted =
              Math.max(0, Math.min(amount, Math.min(CAPACITY - energy, INPUT_RATE - used)));
          if (!simulate && accepted > 0) {
            if (tick != inputTick) {
              inputTick = tick;
              inputThisTick = 0;
            }
            inputThisTick += accepted;
            energy += accepted;
            setChanged();
          }
          return (int) accepted;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
          return 0;
        }

        @Override
        public int getEnergyStored() {
          return (int) energy;
        }

        @Override
        public int getMaxEnergyStored() {
          return (int) CAPACITY;
        }

        @Override
        public boolean canExtract() {
          return false;
        }

        @Override
        public boolean canReceive() {
          return true;
        }
      };

  public AccessPointEntity(BlockPos position, BlockState state) {
    super(WirelessContent.ACCESS_POINT_ENTITY.get(), position, state);
  }

  public IEnergyStorage energyStorage() {
    return storage;
  }

  public long energy() {
    return energy;
  }

  /** Spends FE if available; spends nothing and returns false otherwise. */
  public boolean spend(long amount) {
    if (amount < 0 || amount > energy) return false;
    energy -= amount;
    setChanged();
    return true;
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
    tag.putLong("Energy", energy);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    energy = Math.max(0, Math.min(CAPACITY, tag.getLong("Energy")));
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(null, "access_point", NetworkTopology.Role.TERMINAL, current());
  }
}
