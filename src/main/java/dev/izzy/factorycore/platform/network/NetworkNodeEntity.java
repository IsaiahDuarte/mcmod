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
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("Network node (ownership recovery)"));
    return stack;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) bind(false);
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
