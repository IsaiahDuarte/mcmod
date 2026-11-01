package dev.izzy.factorycore.platform.power;

import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Q26: the Creative Energy Source's endless FE. It stores nothing and saves nothing. */
public final class CreativeEnergySourceEntity extends BlockEntity {
  /** FE offered to each neighbour per tick; any receiver's own rate limit still applies. */
  public static final int PUSH = Integer.MAX_VALUE;

  private final EnumMap<Direction, BlockCapabilityCache<IEnergyStorage, Direction>> receivers =
      new EnumMap<>(Direction.class);

  private final IEnergyStorage energy =
      new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
          return 0;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
          return Math.max(0, amount);
        }

        @Override
        public int getEnergyStored() {
          return Integer.MAX_VALUE;
        }

        @Override
        public int getMaxEnergyStored() {
          return Integer.MAX_VALUE;
        }

        @Override
        public boolean canExtract() {
          return true;
        }

        @Override
        public boolean canReceive() {
          return false;
        }
      };

  public CreativeEnergySourceEntity(BlockPos position, BlockState state) {
    super(FuelGeneratorContent.CREATIVE_ENTITY.get(), position, state);
  }

  public IEnergyStorage energy() {
    return energy;
  }

  void serverTick() {
    if (!(level instanceof ServerLevel server)) return;
    for (var face : Direction.values()) {
      var receiver =
          receivers
              .computeIfAbsent(
                  face,
                  f ->
                      BlockCapabilityCache.create(
                          Capabilities.EnergyStorage.BLOCK,
                          server,
                          worldPosition.relative(f),
                          f.getOpposite()))
              .getCapability();
      if (receiver != null && receiver.canReceive()) receiver.receiveEnergy(PUSH, false);
    }
  }

  @Override
  public void setRemoved() {
    receivers.clear();
    super.setRemoved();
  }
}
