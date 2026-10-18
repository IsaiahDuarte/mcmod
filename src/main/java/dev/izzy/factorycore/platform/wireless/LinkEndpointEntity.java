package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.NetworkNodeBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * One end of an explicit same-dimension wireless pair. Its back face must touch a gateway; while
 * paired, in range and paying {@link #TICK_COST} FE per tick from its own buffer, discovery treats
 * the pair as one reciprocal cable edge, so the normal generation, cycle, bypass and gateway policy
 * checks apply. It never loads chunks; an unloaded partner suspends the link segment.
 */
@SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed ServerLevel.
public final class LinkEndpointEntity extends BlockEntity {
  public static final long CAPACITY = 20_000;
  public static final long INPUT_RATE = 1_000;
  public static final long TICK_COST = 32;
  public static final int RANGE = 256;
  private long energy;
  private Long partner;
  private UUID owner;
  private boolean powered;
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

  public LinkEndpointEntity(BlockPos position, BlockState state) {
    super(WirelessContent.LINK_ENTITY.get(), position, state);
  }

  public IEnergyStorage energyStorage() {
    return storage;
  }

  public long energy() {
    return energy;
  }

  /** Test/diagnostic drain; spends nothing and returns false if unaffordable. */
  public boolean spend(long amount) {
    if (amount < 0 || amount > energy) return false;
    energy -= amount;
    setChanged();
    return true;
  }

  public UUID owner() {
    return owner;
  }

  void owner(UUID placer) {
    owner = placer;
    setChanged();
  }

  public BlockPos partner() {
    return partner == null ? null : BlockPos.of(partner);
  }

  /** Sets or clears the partner; callers refresh topology at both ends. */
  void partner(BlockPos value) {
    partner = value == null ? null : value.asLong();
    powered = partner != null && energy >= TICK_COST;
    setChanged();
  }

  /** The face that carries the wired connection (the block it was placed against). */
  public net.minecraft.core.Direction back() {
    return getBlockState().getValue(BlockStateProperties.FACING).getOpposite();
  }

  /** True when the back face touches a gateway block; never loads the neighbor's chunk. */
  public boolean touchesGateway() {
    if (!(level instanceof ServerLevel server)) return false;
    var neighbor = worldPosition.relative(back());
    var chunk = server.getChunkSource().getChunkNow(neighbor.getX() >> 4, neighbor.getZ() >> 4);
    return chunk != null
        && chunk.getBlockState(neighbor).getBlock() instanceof NetworkNodeBlock node
        && node.kind() == NodeRegistry.Kind.GATEWAY;
  }

  /** The loaded endpoint at {@code position}, without loading its chunk. */
  static LinkEndpointEntity loaded(ServerLevel level, BlockPos position) {
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    return chunk != null
            && chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK)
                instanceof LinkEndpointEntity link
        ? link
        : null;
  }

  public static boolean inRange(BlockPos first, BlockPos second) {
    return first.distSqr(second) <= (double) RANGE * RANGE;
  }

  /** Why the link is inactive, or an empty string when it offers its wireless port. */
  public String diagnostic() {
    if (partner == null) return "unpaired";
    if (!touchesGateway()) return "needs_gateway";
    if (!inRange(worldPosition, partner())) return "out_of_range";
    if (!powered) return "no_power";
    return "";
  }

  public boolean active() {
    return current() && diagnostic().isEmpty();
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk =
        server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  /** Pays operating power; a change in powered state re-discovers both ends. */
  void serverTick() {
    if (partner == null) return;
    boolean next = energy >= TICK_COST;
    if (next) {
      energy -= TICK_COST;
      setChanged();
    }
    if (next != powered) {
      powered = next;
      refresh();
    }
  }

  void refresh() {
    WorldNetworks.changed(level, worldPosition);
    if (partner != null) WorldNetworks.changed(level, partner());
  }

  @Override
  public void onLoad() {
    super.onLoad();
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    if (level != null && !level.isClientSide()) refresh();
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.putLong("Energy", energy);
    if (partner != null) tag.putLong("Partner", partner);
    if (owner != null) tag.putUUID("Owner", owner);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    energy = Math.max(0, Math.min(CAPACITY, tag.getLong("Energy")));
    partner = tag.contains("Partner") ? tag.getLong("Partner") : null;
    owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    powered = partner != null && energy >= TICK_COST;
  }
}
