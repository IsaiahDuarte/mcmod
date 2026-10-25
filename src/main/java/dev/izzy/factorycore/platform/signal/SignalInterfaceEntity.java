package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
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
 * its settings). Saved: the rule, the watched resource and the owner; power is recomputed.
 */
public final class SignalInterfaceEntity extends BlockEntity implements WiredMember {
  static final String FIELD = "FactoryCoreSignal";

  private UUID owner;
  private SignalRule rule = SignalRule.DEFAULT;
  private ResourceKey resource;
  private int power;
  private String reason = "unset";

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

  public ResourceKey resource() {
    return resource;
  }

  public int power() {
    return power;
  }

  /** Why the output is what it is: "" when evaluated, else a reason key suffix. */
  public String reason() {
    return reason;
  }

  /** A fresh Signal Interface owned by {@code owner}; it stays off until a resource is set. */
  public void place(UUID owner) {
    this.owner = owner;
    setChanged();
  }

  /** Saves new settings under {@code owner}, who must hold EDIT here (checked by the caller). */
  public void configure(UUID owner, SignalRule rule, ResourceKey resource) {
    this.owner = Objects.requireNonNull(owner);
    this.rule = Objects.requireNonNull(rule);
    if (resource != null && resource.kind() == ResourceKind.ENERGY)
      throw new IllegalArgumentException("A Signal Interface watches items or fluids");
    this.resource = resource;
    setChanged();
    if (level instanceof ServerLevel server) WorldSignals.get(server.getServer()).dueNow(this);
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
    data.putInt("Schema", 1);
    if (owner != null) data.putUUID("Owner", owner);
    data.putString("Condition", rule.condition().name());
    data.putString("Compare", rule.compare().name());
    data.putLong("Threshold", rule.threshold());
    data.putString("Output", rule.output().name());
    if (resource != null) {
      var key = new CompoundTag();
      key.putString("Kind", resource.kind().name());
      key.putString("Id", resource.registryId());
      key.putByteArray("Components", resource.components());
      data.put("Resource", key);
    }
    data.putInt("Power", power);
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
    resource = null;
    power = 0;
    reason = "unset";
    if (!tag.contains(FIELD, Tag.TAG_COMPOUND)) return;
    var data = tag.getCompound(FIELD);
    try {
      if (data.getInt("Schema") != 1) throw new IllegalArgumentException("Signal schema");
      var loadedRule =
          new SignalRule(
              SignalRule.Condition.valueOf(data.getString("Condition")),
              SignalRule.Compare.valueOf(data.getString("Compare")),
              data.getLong("Threshold"),
              SignalRule.Output.valueOf(data.getString("Output")));
      ResourceKey loadedResource = null;
      if (data.contains("Resource", Tag.TAG_COMPOUND)) {
        var key = data.getCompound("Resource");
        loadedResource =
            new ResourceKey(
                ResourceKind.valueOf(key.getString("Kind")),
                key.getString("Id"),
                key.getByteArray("Components"));
        if (loadedResource.kind() == ResourceKind.ENERGY)
          throw new IllegalArgumentException("Energy resource");
      }
      int saved = data.getInt("Power");
      if (saved < 0 || saved > SignalRule.MAX) throw new IllegalArgumentException("Power");
      owner = data.hasUUID("Owner") ? data.getUUID("Owner") : null;
      rule = loadedRule;
      resource = loadedResource;
      // The last strength is kept until the first evaluation, so a reload does not flicker.
      power = saved;
      reason = "";
    } catch (IllegalArgumentException malformed) {
      reason = "unset";
    }
  }
}
