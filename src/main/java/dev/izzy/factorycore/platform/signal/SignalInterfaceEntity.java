package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.FilterCodec;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * C1: the Signal mode of the unified Interface. It holds no resources and no lease; it is a
 * positional network member that emits redstone on every side from its {@link SignalRule},
 * evaluated by {@link WorldSignals} under its owner's current grants (the player who last applied
 * its settings). Saved: the rule, the watched filter and the owner; power is recomputed. Schema 2
 * (Q24) saves the filter as "Watch"; schema 1's single "Resource" loads as one exact term.
 */
public final class SignalInterfaceEntity extends BlockEntity implements WiredMember {
  static final String FIELD = "FactoryCoreSignal";

  private UUID owner;
  private SignalRule rule = SignalRule.DEFAULT;
  private ResourceFilter watch;
  private int power;
  private String reason = "unset";
  private String label = "";

  /** Longest label (D6), matching Machine Interface labels. */
  public static final int MAX_LABEL = 64;

  /** D6 (ADR 0062): a deployed program's strength and the tick it expires. Runtime only. */
  record Driven(int strength, long until) {}

  private final java.util.Map<UUID, Driven> driven = new java.util.HashMap<>();

  public SignalInterfaceEntity(BlockPos position, BlockState state) {
    super(SignalContent.ENTITY.get(), position, state);
  }

  public ServerLevel serverLevel() {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread())
      throw new IllegalStateException("Signal Interface requires server thread");
    return server;
  }

  public NodeRegistry.Position origin() {
    return Positions.of(serverLevel(), worldPosition);
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel)) return false;
    var chunk = Loaded.chunk(serverLevel(), worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  /** In reach and loaded; authority is checked by the menu (VIEW to look, EDIT to apply). */
  public boolean canUse(ServerPlayer player) {
    return player.isAlive()
        && current()
        && player.serverLevel() == level
        && player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 64;
  }

  public UUID owner() {
    return owner;
  }

  public SignalRule rule() {
    return rule;
  }

  /** Q24: what a STOCK or JOB condition watches (an allow list, items or fluids), or null. */
  public ResourceFilter watch() {
    return watch;
  }

  /**
   * The one resource a JOB condition follows: the only slot, if it is an exact resource or a plain
   * ID (its default form); null when the filter names several or a tag.
   */
  public ResourceKey resource() {
    if (watch == null || watch.terms().size() != 1) return null;
    var term = watch.terms().getFirst();
    if (term instanceof ResourceFilter.Exact exact) return exact.key();
    if (!(term instanceof ResourceFilter.Registry registry) || level == null) return null;
    var id = net.minecraft.resources.ResourceLocation.tryParse(registry.id());
    if (id == null) return null;
    var codec = new PlatformResourceCodec(level.registryAccess());
    if (watch.kind() == ResourceKind.FLUID) {
      if (!net.minecraft.core.registries.BuiltInRegistries.FLUID.containsKey(id)) return null;
      return codec.fluidKey(
          new net.neoforged.neoforge.fluids.FluidStack(
              net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id), 1));
    }
    if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) return null;
    var stack =
        new net.minecraft.world.item.ItemStack(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));
    return stack.isEmpty() ? null : codec.itemKey(stack);
  }

  public int power() {
    return power;
  }

  /** Why the output is what it is: "" when evaluated, else a reason key suffix. */
  public String reason() {
    return reason;
  }

  /** The label programs name in {@code emit redstone N on machines("label")}; "" for none. */
  public String label() {
    return label;
  }

  /** Sets the label (EDIT checked by the caller); control characters are removed. */
  public void label(String value) {
    var clean = value.replaceAll("[\\p{Cntrl}§]", "").strip();
    if (clean.length() > MAX_LABEL) throw new IllegalArgumentException("Label longer than 64");
    label = clean;
    setChanged();
  }

  /** D6: {@code deployment} drives this Interface at {@code strength} until tick {@code until}. */
  void drive(UUID deployment, int strength, long until) {
    if (strength < 0 || strength > SignalRule.MAX) throw new IllegalArgumentException("Strength");
    driven.put(deployment, new Driven(strength, until));
  }

  /** Forgets {@code deployment}'s output; true when it was driving this Interface. */
  boolean release(UUID deployment) {
    return driven.remove(deployment) != null;
  }

  /** The highest unexpired program strength, or -1 when no program drives this Interface. */
  int driven(long now) {
    driven.values().removeIf(value -> value.until() < now);
    int best = -1;
    for (var value : driven.values()) best = Math.max(best, value.strength());
    return best;
  }

  /** A fresh Signal Interface owned by {@code owner}; it stays off until a resource is set. */
  public void place(UUID owner) {
    this.owner = owner;
    setChanged();
  }

  /** Saves new settings under {@code owner}, who must hold EDIT here (checked by the caller). */
  public void configure(UUID owner, SignalRule rule, ResourceKey resource) {
    configure(
        owner,
        rule,
        resource == null
            ? null
            : new ResourceFilter(
                resource.kind(),
                ResourceFilter.Mode.INCLUDE,
                java.util.List.of(new ResourceFilter.Exact(resource))));
  }

  /** Q24: as above, watching every resource {@code watch} allows (null watches nothing). */
  public void configure(UUID owner, SignalRule rule, ResourceFilter watch) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(rule);
    checkWatch(watch);
    this.owner = owner;
    this.rule = rule;
    this.watch = watch;
    setChanged();
    if (level instanceof ServerLevel server) WorldSignals.get(server.getServer()).dueNow(this);
  }

  /** An allow list of 1–16 item or fluid slots a screen can show, or null. */
  static void checkWatch(ResourceFilter watch) {
    if (watch == null) return;
    if (watch.kind() == ResourceKind.ENERGY)
      throw new IllegalArgumentException("A Signal Interface watches items or fluids");
    if (watch.mode() != ResourceFilter.Mode.INCLUDE
        || watch.terms().isEmpty()
        || watch.terms().stream().anyMatch(ResourceFilter::programOnly))
      throw new IllegalArgumentException("A Signal Interface watches a list of slots");
  }

  /** Sets the emitted strength and its reason; neighbors are updated only on a change. */
  void output(int strength, String why) {
    if (strength < 0 || strength > SignalRule.MAX) throw new IllegalArgumentException("Strength");
    reason = why;
    if (strength == power) return;
    power = strength;
    setChanged();
    if (level == null) return;
    // Weak power reaches only direct neighbors. Network blocks treat any neighbor update as a
    // topology change, and none of them read redstone, so they are not told (no rebuild).
    var block = getBlockState().getBlock();
    for (var direction : net.minecraft.core.Direction.values()) {
      var neighbor = worldPosition.relative(direction);
      if (!(level.getBlockState(neighbor).getBlock()
          instanceof dev.izzy.factorycore.platform.network.NetworkedBlock))
        level.neighborChanged(neighbor, block, worldPosition);
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server) {
      WorldNetworks.changed(level, worldPosition);
      WorldSignals.get(server.getServer()).loaded(this);
    }
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel server) {
      WorldNetworks.changed(level, worldPosition);
      WorldSignals.get(server.getServer()).removed(this);
    }
    super.setRemoved();
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(null, "signal_interface", NetworkTopology.Role.TERMINAL, current());
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    var data = new CompoundTag();
    data.putInt("Schema", 2);
    if (owner != null) data.putUUID("Owner", owner);
    data.putString("Condition", rule.condition().name());
    data.putString("Compare", rule.compare().name());
    data.putLong("Threshold", rule.threshold());
    data.putString("Output", rule.output().name());
    if (watch != null) data.put("Watch", FilterCodec.encode(watch));
    data.putInt("Power", power);
    if (!label.isEmpty()) data.putString("Label", label);
    tag.put(FIELD, data);
  }

  /**
   * Unknown or malformed data keeps the defaults (off, unowned) rather than guessing; a Signal
   * Interface holds no resources, so there is nothing to preserve for recovery.
   */
  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    owner = null;
    rule = SignalRule.DEFAULT;
    watch = null;
    power = 0;
    reason = "unset";
    label = "";
    if (!tag.contains(FIELD, Tag.TAG_COMPOUND)) return;
    var data = tag.getCompound(FIELD);
    try {
      int schema = data.getInt("Schema");
      if (schema != 1 && schema != 2) throw new IllegalArgumentException("Signal schema");
      var loadedRule =
          new SignalRule(
              SignalRule.Condition.valueOf(data.getString("Condition")),
              SignalRule.Compare.valueOf(data.getString("Compare")),
              data.getLong("Threshold"),
              SignalRule.Output.valueOf(data.getString("Output")));
      ResourceFilter loadedWatch = null;
      if (schema == 2 && data.contains("Watch", Tag.TAG_COMPOUND))
        loadedWatch = FilterCodec.decode(data.getCompound("Watch"), "Signal");
      else if (schema == 1 && data.contains("Resource", Tag.TAG_COMPOUND)) {
        var key = data.getCompound("Resource");
        var resource =
            new ResourceKey(
                ResourceKind.valueOf(key.getString("Kind")),
                key.getString("Id"),
                key.getByteArray("Components"));
        loadedWatch =
            new ResourceFilter(
                resource.kind(),
                ResourceFilter.Mode.INCLUDE,
                java.util.List.of(new ResourceFilter.Exact(resource)));
      }
      checkWatch(loadedWatch);
      int saved = data.getInt("Power");
      if (saved < 0 || saved > SignalRule.MAX) throw new IllegalArgumentException("Power");
      var savedLabel = data.getString("Label");
      if (savedLabel.length() > MAX_LABEL) throw new IllegalArgumentException("Label");
      owner = data.hasUUID("Owner") ? data.getUUID("Owner") : null;
      label = savedLabel;
      rule = loadedRule;
      watch = loadedWatch;
      // The last strength is kept until the first evaluation, so a reload does not flicker.
      power = saved;
      reason = "";
    } catch (IllegalArgumentException malformed) {
      reason = "unset";
    }
  }
}
