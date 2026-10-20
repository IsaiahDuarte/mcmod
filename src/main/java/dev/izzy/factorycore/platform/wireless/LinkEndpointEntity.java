package dev.izzy.factorycore.platform.wireless;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkNodeBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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
  private ThroughputTier tier = ThroughputTier.BASE;
  private CompoundTag rejected;
  private static final String RECOVERY = "FactoryCoreLinkRecovery";
  private long inputTick = Long.MIN_VALUE, inputThisTick;

  private final IEnergyStorage storage =
      new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
          if (amount <= 0 || level == null || level.isClientSide() || rejected != null) return 0;
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
  public Direction back() {
    return getBlockState().getValue(BlockStateProperties.FACING).getOpposite();
  }

  /** True when the back face touches a gateway block; never loads the neighbor's chunk. */
  public boolean touchesGateway() {
    if (!(level instanceof ServerLevel server)) return false;
    var neighbor = worldPosition.relative(back());
    var chunk = Loaded.chunk(server, neighbor);
    return chunk != null
        && chunk.getBlockState(neighbor).getBlock() instanceof NetworkNodeBlock node
        && node.kind() == NodeRegistry.Kind.GATEWAY;
  }

  /** The loaded endpoint at {@code position}, without loading its chunk. */
  static LinkEndpointEntity loaded(ServerLevel level, BlockPos position) {
    var chunk = Loaded.chunk(level, position);
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
    if (rejected != null) return "unknown_data";
    if (partner == null) return "unpaired";
    if (!touchesGateway()) return "needs_gateway";
    if (!inRange(worldPosition, partner())) return "out_of_range";
    if (!powered) return "no_power";
    return "";
  }

  /** Presentation lamp: off while unpaired, lit while offering its port, fault otherwise. */
  DeviceLamp lamp() {
    var reason = diagnostic();
    if ("unpaired".equals(reason)) return DeviceLamp.OFF;
    return reason.isEmpty() ? DeviceLamp.ACTIVE : DeviceLamp.FAULT;
  }

  public boolean active() {
    return current() && diagnostic().isEmpty();
  }

  /** Current reciprocal pair identity, without loading either endpoint's chunk. */
  public WirelessBudget.Link pair() {
    if (!(level instanceof ServerLevel server) || !active()) return null;
    var other = loaded(server, partner());
    if (other == null || !other.active() || !worldPosition.equals(other.partner())) return null;
    String dimension = server.dimension().location().toString();
    return new WirelessBudget.Link(
        new NodeRegistry.Position(dimension, worldPosition.asLong()),
        new NodeRegistry.Position(dimension, other.getBlockPos().asLong()));
  }

  /** Installed throughput upgrade; BASE or ENDGAME (Overdrive) for link endpoints. */
  public ThroughputTier tier() {
    return tier;
  }

  /**
   * Installs an Overdrive upgrade. The caller has already checked MANAGE authority and consumes the
   * item only after this returns. Both ends of a pair are re-discovered so the new scale is
   * published through normal topology preparation.
   */
  public void install(ThroughputTier upgrade) {
    if (rejected != null) throw new IllegalStateException("upgrade_unknown_data");
    var next = tier.upgradeWith(upgrade, true);
    if (next == null) throw new IllegalStateException("upgrade_not_applicable");
    tier = next;
    setChanged();
    refresh();
  }

  /**
   * Per-pair multiplier on the ADR 0050 caps: the Overdrive scale only when both loaded, reciprocal
   * ends carry it; otherwise 1. Never loads the partner chunk.
   */
  public int pairScale() {
    if (!(level instanceof ServerLevel server) || partner == null) return 1;
    var other = loaded(server, partner());
    return other != null && worldPosition.equals(other.partner())
        ? Math.min(tier.linkScale(), other.tier.linkScale())
        : 1;
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  /** Pays operating power; a change in powered state re-discovers both ends. */
  void serverTick() {
    if (level != null && DeviceLamp.due(level, worldPosition))
      DeviceLamp.show(level, worldPosition, getBlockState(), lamp());
    if (rejected != null) return;
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
    if (rejected != null) {
      tag.merge(rejected.copy());
      return;
    }
    super.saveAdditional(tag, registries);
    tag.putLong("Energy", energy);
    if (partner != null) tag.putLong("Partner", partner);
    if (owner != null) tag.putUUID("Owner", owner);
    if (tier != ThroughputTier.BASE) tag.putString("Throughput", tier.name());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    if (tag.contains(RECOVERY)) {
      rejected = tag.copy();
      powered = false;
      tier = ThroughputTier.BASE;
      return;
    }
    energy = Math.max(0, Math.min(CAPACITY, tag.getLong("Energy")));
    partner = tag.contains("Partner") ? tag.getLong("Partner") : null;
    owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    powered = partner != null && energy >= TICK_COST;
    // Absence is the legacy base format. Unsupported values preserve the whole before-state and
    // remain inert, including across break/place, rather than becoming an unupgraded live endpoint.
    tier = ThroughputTier.BASE;
    rejected = null;
    if (tag.contains("Throughput")) {
      try {
        if (!tag.contains("Throughput", Tag.TAG_STRING))
          throw new IllegalArgumentException("Invalid throughput type");
        tier = ThroughputTier.parse(tag.getString("Throughput"));
        if (tier != ThroughputTier.ENDGAME)
          throw new IllegalArgumentException("Unsupported link throughput tier");
      } catch (IllegalArgumentException unknown) {
        tier = ThroughputTier.BASE;
        rejected = tag.copy();
        powered = false;
      }
    }
  }

  public boolean needsRecovery() {
    return rejected != null;
  }

  /**
   * Recovery envelopes are re-read through the strict loader, never treated as live item grants.
   */
  public void restoreRecovery(ItemStack stack, HolderLookup.Provider registries) {
    var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (!data.contains(RECOVERY)) return;
    if (data.contains(RECOVERY, Tag.TAG_COMPOUND))
      loadAdditional(data.getCompound(RECOVERY), registries);
    else rejected = data.copy();
    setChanged();
    refresh();
  }

  /** Known upgrades return separately; unknown data stays in exactly one inert endpoint item. */
  public List<ItemStack> upgradeDrops() {
    if (rejected != null) {
      var stack = new ItemStack(WirelessContent.LINK_ITEM.get());
      stack.set(
          DataComponents.CUSTOM_NAME, Component.translatable("item.factorycore.link_recovery"));
      CustomData.update(
          DataComponents.CUSTOM_DATA, stack, data -> data.put(RECOVERY, rejected.copy()));
      return List.of(stack);
    }
    return ThroughputContent.installed(tier, true);
  }
}
