package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import net.minecraft.core.BlockPos;
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

/**
 * Chunk/item references; ownership and gateway policies live exclusively in canonical SavedData.
 */
public final class NetworkNodeEntity extends BlockEntity {
  private Tag data;
  private NodeRegistry.Handle handle;
  private String diagnostic = "";

  public NetworkNodeEntity(BlockPos position, BlockState state) {
    super(NetworkContent.NODE_ENTITY.get(), position, state);
  }

  public NodeRegistry.Kind kind() {
    return ((NetworkNodeBlock) getBlockState().getBlock()).kind();
  }

  public String diagnostic() {
    return diagnostic;
  }

  /**
   * A reference only; routing must also check current canonical position and validated topology.
   */
  public NodeRegistry.Handle handle() {
    return handle;
  }

  @SuppressWarnings("PMD.CloseResource")
  public NodeRegistry.Position position() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Structural node requires server level");
    return new NodeRegistry.Position(
        server.dimension().location().toString(), worldPosition.asLong());
  }

  @SuppressWarnings("PMD.CloseResource")
  private DeviceSavedData authority() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Structural node requires server level");
    return DeviceSavedData.get(server.getServer());
  }

  public void place(ItemStack stack, java.util.UUID player) {
    var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (custom.contains(NodeHandleData.FIELD)) {
      data = custom.get(NodeHandleData.FIELD).copy();
      parse();
      bind(true);
    } else if (data != null) {
      parse();
      bind(true);
    } else if (kind() == NodeRegistry.Kind.GATEWAY || player != null) {
      commission(player);
    }
    setChanged();
    networkingChanged();
  }

  public void commission(java.util.UUID player) {
    if (!diagnostic.isEmpty() || handle != null)
      throw new IllegalStateException("Node already bound or needs recovery");
    handle =
        kind() == NodeRegistry.Kind.CONTROLLER
            ? authority().createController(java.util.Objects.requireNonNull(player), position())
            : authority().createGateway(position());
    data = NodeHandleData.write(handle);
    setChanged();
    networkingChanged();
  }

  /** Trusted operator recovery at the loaded original holder; permissions/policy never change. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level and server.
  public java.nio.file.Path repairConflict(NodeRegistry.Handle expected)
      throws java.io.IOException {
    if (!(level instanceof ServerLevel server)
        || !server.getServer().isSameThread()
        || isRemoved()
        || server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4)
            == null
        || server.getBlockEntity(worldPosition) != this
        || !expected.equals(handle)
        || !expected.equals(NodeHandleData.read(data))
        || expected.kind() != kind())
      throw new IllegalStateException("Exact loaded network-node reference required");
    var saved = authority();
    var owner = saved.nodes().evidence(expected.id());
    if (owner == null
        || !owner.conflict()
        || saved.nodes().validate(expected) != NodeRegistry.Failure.CONFLICT
        || !position().equals(owner.position())
        || expected.generation() == Long.MAX_VALUE
        || kind() != NodeRegistry.Kind.CONTROLLER && kind() != NodeRegistry.Kind.GATEWAY
        || saved.transfers().unresolved(expected.id()) != null
        || saved.transfers().unresolvedDevice(expected.id()) != null)
      throw new IllegalStateException("Canonical network-node conflict cannot be repaired");
    var next =
        new NodeRegistry.Handle(
            expected.world(), expected.id(), expected.generation() + 1, expected.kind());
    var nextData = NodeHandleData.write(next);
    var path =
        dev.izzy.factorycore.platform.storage.DeviceRecoveryAudit.preserveNetworkNode(
            server.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
            expected.world(),
            owner,
            kind() == NodeRegistry.Kind.CONTROLLER ? saved.network(expected.id()).snapshot() : null,
            net.minecraft.nbt.NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(server.registryAccess()));
    var repaired = saved.nodes().repairConflict(expected, position());
    if (repaired.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Network-node repair refused: " + repaired.failure());
    handle = next;
    data = nextData;
    diagnostic = "";
    setChanged();
    onLoad();
    return path;
  }

  /**
   * Independent-save reconciliation adapter for controllers and gateways: adopts this loaded
   * holder's mismatched reference as canonical. Refuses unless the old canonical position is loaded
   * and is not a structural node referencing this ID, this holder's reference has the same world,
   * ID and kind with a different generation, and no transfer is unresolved. Grants and policy are
   * unchanged.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level and server.
  public java.nio.file.Path adoptNode(NodeRegistry.Handle expected) throws java.io.IOException {
    if (!(level instanceof ServerLevel server)
        || !server.getServer().isSameThread()
        || isRemoved()
        || server.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4)
            == null
        || server.getBlockEntity(worldPosition) != this
        || handle == null
        || expected.kind() != kind())
      throw new IllegalStateException("Adopting network node must be loaded and readable");
    var saved = authority();
    var owner = saved.nodes().evidence(expected.id());
    if (owner == null
        || owner.position() == null
        || owner.generation() != expected.generation()
        || owner.kind() != expected.kind()
        || kind() != NodeRegistry.Kind.CONTROLLER && kind() != NodeRegistry.Kind.GATEWAY
        || !handle.world().equals(expected.world())
        || !handle.id().equals(expected.id())
        || handle.generation() == expected.generation()
        || expected.generation() == Long.MAX_VALUE
        || saved.transfers().unresolved(expected.id()) != null
        || saved.transfers().unresolvedDevice(expected.id()) != null)
      throw new IllegalStateException("Network-node lease cannot be adopted");
    var old = owner.position();
    var oldLevel =
        server
            .getServer()
            .getLevel(
                net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(old.dimension())));
    var oldPos = BlockPos.of(old.block());
    if (oldLevel == null
        || oldLevel.getChunkSource().getChunkNow(oldPos.getX() >> 4, oldPos.getZ() >> 4) == null)
      throw new IllegalStateException("Old canonical holder must be loaded");
    if (oldLevel.getBlockEntity(oldPos) instanceof NetworkNodeEntity previous
        && previous.handle != null
        && previous.handle.id().equals(expected.id()))
      throw new IllegalStateException("Old canonical holder still references the node");
    var path =
        dev.izzy.factorycore.platform.storage.DeviceRecoveryAudit.preserveNetworkNodeAdoption(
            server.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
            expected.world(),
            owner,
            kind() == NodeRegistry.Kind.CONTROLLER ? saved.network(expected.id()).snapshot() : null,
            position(),
            net.minecraft.nbt.NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(server.registryAccess()));
    var adopted = saved.nodes().adopt(expected, position());
    if (adopted.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Network-node adoption refused: " + adopted.failure());
    handle = adopted.handle();
    data = NodeHandleData.write(handle);
    diagnostic = "";
    setChanged();
    WorldNetworks.changed(oldLevel, oldPos);
    onLoad();
    return path;
  }

  public void validatePlaced() {
    if (!diagnostic.isEmpty()) throw new IllegalStateException(diagnostic);
    if (handle == null) throw new IllegalStateException("Uncommissioned structural node");
    if (!position().equals(authority().nodes().definition(handle).position()))
      throw new IllegalStateException("Structural reference is not at its canonical position");
  }

  private void parse() {
    handle = null;
    diagnostic = "";
    try {
      handle = NodeHandleData.read(data);
      if (handle != null && handle.kind() != kind())
        throw new IllegalArgumentException("Structural block kind mismatch");
    } catch (IllegalArgumentException failure) {
      handle = null;
      diagnostic = failure.getMessage();
    }
  }

  private void bind(boolean placement) {
    if (handle == null || !diagnostic.isEmpty()) return;
    try {
      if (!placement && authority().nodes().definition(handle).position() == null)
        throw new IllegalStateException("Portable lease stored in a placed node");
      var placed = authority().nodes().attach(handle, position());
      if (placed.failure() != NodeRegistry.Failure.OK)
        throw new IllegalStateException("Structural binding: " + placed.failure());
      handle = placed.handle();
      data = NodeHandleData.write(handle);
      setChanged();
      networkingChanged();
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
      diagnostic = failure.getMessage();
    }
  }

  /** Break always emits one casing; unresolved references remain inert recovery data. */
  public ItemStack portableForBreak() {
    var stack = new ItemStack(getBlockState().getBlock().asItem());
    if (handle != null && diagnostic.isEmpty()) {
      try {
        var result = authority().nodes().detach(handle, position());
        if (result.failure() != NodeRegistry.Failure.OK)
          throw new IllegalStateException("Structural removal: " + result.failure());
        handle = result.handle();
        data = NodeHandleData.write(handle);
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
        diagnostic = failure.getMessage();
      }
    }
    if (data != null)
      CustomData.update(
          DataComponents.CUSTOM_DATA,
          stack,
          custom -> custom.put(NodeHandleData.FIELD, data.copy()));
    if (!diagnostic.isEmpty())
      stack.set(
          DataComponents.CUSTOM_NAME, Component.translatable("item.factorycore.node_recovery"));
    return stack;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) bind(false);
    networkingChanged();
  }

  private void networkingChanged() {
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    networkingChanged();
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (data != null) tag.put(NodeHandleData.FIELD, data.copy());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    data = tag.contains(NodeHandleData.FIELD) ? tag.get(NodeHandleData.FIELD).copy() : null;
    parse();
  }
}
