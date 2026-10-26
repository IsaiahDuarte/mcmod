package dev.izzy.factorycore.platform.crafting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The Crafter (ADR 0060): one holder for crafting-table and processing patterns. Crafting-table
 * patterns run inside it; processing patterns are pushed into any machine beside it (H8), taking
 * turns between machines. A job's persisted dispatch is the only lease on the machine port; the
 * Crafter itself stores no job state and owns no resources.
 */
public final class CrafterEntity extends PatternHolderEntity
    implements dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeable {
  /** Saved tier tag (E2); absent means BASE, so older Crafters keep their bytes. */
  static final String THROUGHPUT = "FactoryCoreThroughput";

  private dev.izzy.factorycore.core.resource.ThroughputTier tier =
      dev.izzy.factorycore.core.resource.ThroughputTier.BASE;

  /**
   * E2 (PD5 option a): crafting-table crafts one scheduled run may make: 1, 8 or 32 by tier. Each
   * run is charged {@code batch × TASK_COST} on the shared scheduler, so speed never buys fairness
   * (R11); 32 × 16 credits is the scheduler's per-task cap (512).
   */
  public static int batch(dev.izzy.factorycore.core.resource.ThroughputTier tier) {
    return switch (tier) {
      case BASE -> 1;
      case ADVANCED -> 8;
      case ENDGAME -> 32;
    };
  }

  @Override
  public dev.izzy.factorycore.core.resource.ThroughputTier tier() {
    return tier;
  }

  /** One tier step under EDIT where the Crafter is; the item is consumed by the caller. */
  @Override
  public boolean upgrade(
      dev.izzy.factorycore.core.network.NetworkPermissions.Actor actor,
      dev.izzy.factorycore.core.resource.ThroughputTier item) {
    var next = tier.upgradeWith(item, false);
    if (next == null) throw new IllegalStateException("upgrade_not_applicable");
    if (!(level instanceof ServerLevel server)
        || !dev.izzy.factorycore.platform.network.WorldNetworks.get(server.getServer())
            .allows(
                actor,
                origin(),
                dev.izzy.factorycore.core.network.NetworkPermissions.Permission.EDIT)) return false;
    tier = next;
    setChanged();
    return true;
  }

  @Override
  public net.minecraft.network.chat.Component throughputStatus(
      net.minecraft.server.MinecraftServer server) {
    return net.minecraft.network.chat.Component.translatable(
        "message.factorycore.crafter_throughput", batch(tier));
  }

  @Override
  protected void saveAdditional(
      net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (tier != dev.izzy.factorycore.core.resource.ThroughputTier.BASE)
      tag.putString(THROUGHPUT, tier.name());
  }

  @Override
  protected void loadAdditional(
      net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    // An unknown tier name keeps BASE; the drop table then returns nothing extra.
    var saved = tag.getString(THROUGHPUT);
    tier = dev.izzy.factorycore.core.resource.ThroughputTier.BASE;
    for (var known : dev.izzy.factorycore.core.resource.ThroughputTier.values())
      if (known.name().equals(saved)) tier = known;
  }

  /** Per side: the neighbour's unsided handler, and its handler on the side facing us. */
  private final java.util.EnumMap<Direction, BlockCapabilityCache<IItemHandler, Direction>>
      machines = new java.util.EnumMap<>(Direction.class),
      sidedMachines = new java.util.EnumMap<>(Direction.class);

  private final java.util.EnumMap<Direction, BlockCapabilityCache<IFluidHandler, Direction>>
      tanks = new java.util.EnumMap<>(Direction.class),
      sidedTanks = new java.util.EnumMap<>(Direction.class);

  /** Where the next processing dispatch starts looking, so jobs rotate between machines. */
  private int nextSide;

  public CrafterEntity(BlockPos position, BlockState state) {
    super(CraftingContent.CRAFTER_ENTITY.get(), position, state);
  }

  @Override
  protected boolean accepts(ItemStack stack) {
    return stack.is(CraftingContent.CRAFTING_PATTERN.get())
            && stack.get(CraftingContent.PATTERN.get()) != null
        || stack.is(CraftingContent.PROCESSING_PATTERN.get())
            && stack.get(CraftingContent.PROCESSING.get()) != null;
  }

  /** The way the Crafter was placed; only its front texture uses it (H8). */
  public Direction facing() {
    return getBlockState().getValue(BlockStateProperties.FACING);
  }

  /**
   * H8: the sides with a machine the Crafter may push processing patterns into, starting after the
   * side it used last. A machine is any neighbour with an item or fluid handler, except Factory
   * Core devices and cables, other Crafters, and a hopper pointing into this Crafter (it feeds
   * patterns).
   */
  public java.util.List<Direction> machineSides() {
    var sides = new java.util.ArrayList<Direction>(6);
    var all = Direction.values();
    for (int i = 0; i < all.length; i++) {
      var side = all[(nextSide + i) % all.length];
      if (machineSide(side)) sides.add(side);
    }
    return sides;
  }

  /** True when {@code side} holds a machine (see {@link #machineSides}). */
  public boolean machineSide(Direction side) {
    if (!(level instanceof ServerLevel server)) return false;
    var target = worldPosition.relative(side);
    if (dev.izzy.factorycore.platform.Loaded.chunk(server, target) == null) return false;
    var state = server.getBlockState(target);
    if (state.getBlock() instanceof dev.izzy.factorycore.platform.network.NetworkedBlock
        || state.getBlock() instanceof CrafterBlock) return false;
    if (state.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
        && state.getValue(net.minecraft.world.level.block.HopperBlock.FACING) == side.getOpposite())
      return false;
    return machine(side) != null || fluidMachine(side) != null;
  }

  /**
   * Remembers that a dispatch used {@code side}, so the next one tries the following side first.
   */
  public void used(Direction side) {
    nextSide = (side.ordinal() + 1) % Direction.values().length;
  }

  /** The side whose neighbour is at {@code target}, or null when it is not adjacent. */
  public Direction sideOf(BlockPos target) {
    for (var side : Direction.values())
      if (worldPosition.relative(side).equals(target)) return side;
    return null;
  }

  /**
   * The machine's unsided item handler on {@code side} when it exposes one (so a furnace's input,
   * fuel and result slots are all reachable), otherwise its handler on the side facing the Crafter.
   */
  public IItemHandler machine(Direction side) {
    if (!(level instanceof ServerLevel server) || !current()) return null;
    var target = worldPosition.relative(side);
    var whole =
        machines.computeIfAbsent(
            side,
            s -> BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, server, target, null));
    var handler = whole.getCapability();
    if (handler != null) return handler;
    return sidedMachines
        .computeIfAbsent(
            side,
            s ->
                BlockCapabilityCache.create(
                    Capabilities.ItemHandler.BLOCK, server, target, side.getOpposite()))
        .getCapability();
  }

  /** The machine's fluid handler on {@code side}, chosen like {@link #machine}; null if none. */
  public IFluidHandler fluidMachine(Direction side) {
    if (!(level instanceof ServerLevel server) || !current()) return null;
    var target = worldPosition.relative(side);
    var whole =
        tanks.computeIfAbsent(
            side,
            s ->
                BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, server, target, null));
    var handler = whole.getCapability();
    if (handler != null) return handler;
    return sidedTanks
        .computeIfAbsent(
            side,
            s ->
                BlockCapabilityCache.create(
                    Capabilities.FluidHandler.BLOCK, server, target, side.getOpposite()))
        .getCapability();
  }

  @Override
  public void setRemoved() {
    machines.clear();
    sidedMachines.clear();
    tanks.clear();
    sidedTanks.clear();
    super.setRemoved();
  }
}
