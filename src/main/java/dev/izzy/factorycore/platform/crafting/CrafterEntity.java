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
 * The Crafter (ADR 0060): one holder for crafting-table and processing patterns, facing one
 * machine. Crafting-table patterns run without the machine; processing patterns are pushed into it.
 * A job's persisted dispatch is the only lease on the machine port; the Crafter itself stores no
 * job state and owns no resources.
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

  private BlockCapabilityCache<IItemHandler, Direction> machine, sided;
  private BlockCapabilityCache<IFluidHandler, Direction> tanks, sidedTanks;
  private Direction tankFacing;
  private Direction cachedFacing;

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
  public IFluidHandler fluidMachine() {
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
