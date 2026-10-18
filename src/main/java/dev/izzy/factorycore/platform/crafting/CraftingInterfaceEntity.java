package dev.izzy.factorycore.platform.crafting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Processing pattern holder facing one machine. A job's persisted dispatch is the only lease on the
 * machine port; the interface itself stores no job state and owns no resources.
 */
@SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed ServerLevel.
public final class CraftingInterfaceEntity extends PatternHolderEntity {
  private BlockCapabilityCache<IItemHandler, Direction> machine, sided;
  private BlockCapabilityCache<net.neoforged.neoforge.fluids.capability.IFluidHandler, Direction>
      tanks,
      sidedTanks;
  private Direction tankFacing;
  private Direction cachedFacing;

  public CraftingInterfaceEntity(BlockPos position, BlockState state) {
    super(CraftingContent.INTERFACE_ENTITY.get(), position, state);
  }

  @Override
  protected boolean accepts(ItemStack stack) {
    return stack.is(CraftingContent.PROCESSING_PATTERN.get())
        && stack.get(CraftingContent.PROCESSING.get()) != null;
  }

  public Direction facing() {
    return getBlockState().getValue(BlockStateProperties.FACING);
  }

  /**
   * The faced machine's unsided item handler when it exposes one (so a furnace's input, fuel and
   * result slots are all reachable), otherwise its handler on the interface-facing side.
   */
  public IItemHandler machine() {
    if (!(level instanceof ServerLevel server) || !current()) return null;
    if (machine == null || cachedFacing != facing()) {
      cachedFacing = facing();
      var target = worldPosition.relative(cachedFacing);
      machine = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, server, target, null);
      sided =
          BlockCapabilityCache.create(
              Capabilities.ItemHandler.BLOCK, server, target, cachedFacing.getOpposite());
    }
    var whole = machine.getCapability();
    return whole != null ? whole : sided.getCapability();
  }

  /** The faced machine's fluid handler, chosen like {@link #machine()}; null if it has none. */
  public net.neoforged.neoforge.fluids.capability.IFluidHandler fluidMachine() {
    if (!(level instanceof ServerLevel server) || !current()) return null;
    if (tanks == null || tankFacing != facing()) {
      tankFacing = facing();
      var target = worldPosition.relative(tankFacing);
      tanks = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, server, target, null);
      sidedTanks =
          BlockCapabilityCache.create(
              Capabilities.FluidHandler.BLOCK, server, target, tankFacing.getOpposite());
    }
    var whole = tanks.getCapability();
    return whole != null ? whole : sidedTanks.getCapability();
  }

  @Override
  public void setRemoved() {
    machine = null;
    sided = null;
    tanks = null;
    sidedTanks = null;
    super.setRemoved();
  }
}
