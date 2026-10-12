package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.BufferedItemHandler;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/** Loaded physical holder of one canonical inventory; no stock is saved into chunk/item NBT. */
public final class BufferedInterfaceEntity extends BlockEntity {
  private Tag data;
  private BufferData.State state;
  private String diagnostic = "";
  private IItemHandler handler;

  public BufferedInterfaceEntity(BlockPos position, BlockState blockState) {
    super(BufferedInterfaceContent.ENTITY.get(), position, blockState);
  }

  public NodeRegistry.Handle handle() {
    return state == null ? null : state.lease();
  }

  public BufferData.State reference() {
    return state;
  }

  public String diagnostic() {
    return diagnostic;
  }

  public NodeRegistry.Position position() {
    return new NodeRegistry.Position(
        serverLevel().dimension().location().toString(), worldPosition.asLong());
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns this borrowed level.
  public ServerLevel serverLevel() {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread())
      throw new IllegalStateException("Buffer requires server thread");
    return server;
  }

  private DeviceSavedData authority() {
    return DeviceSavedData.get(serverLevel().getServer());
  }

  private boolean physicallyCurrent() {
    var chunk =
        serverLevel()
            .getChunkSource()
            .getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
    return chunk != null
        && chunk.getBlockEntity(
                worldPosition, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK)
            == this;
  }

  public void place(ItemStack stack, UUID placer) {
    if (state != null) unbind();
    var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (custom.contains(BufferData.FIELD)) {
      data = custom.get(BufferData.FIELD).copy();
      parse();
      bind(true);
    } else if (data != null) {
      parse();
      bind(true);
    } else {
      state = new BufferData.State(authority().createBuffer(position()), placer, 1);
      write();
      bind(true);
    }
    WorldNetworks.changed(level, worldPosition);
  }

  public void validatePlaced() {
    if (state == null
        || !diagnostic.isEmpty()
        || isRemoved()
        || !physicallyCurrent()
        || !authority().buffers().usable(state.lease(), this))
      throw new IllegalStateException("Buffered interface unavailable: " + diagnostic);
  }

  public ItemBuffer inventory() {
    validatePlaced();
    return authority().buffers().inventory(state.lease(), this);
  }

  public IItemHandler capability() {
    try {
      validatePlaced();
      return handler;
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  private void parse() {
    state = null;
    diagnostic = "";
    if (data == null) return;
    try {
      state = BufferData.read(data);
    } catch (IllegalArgumentException failure) {
      fault(failure);
    }
  }

  private void bind(boolean placement) {
    if (state == null || !diagnostic.isEmpty()) return;
    try {
      var saved = authority();
      if (!placement && saved.nodes().definition(state.lease()).position() == null)
        throw new IllegalStateException("Portable buffer reference in chunk");
      var result = saved.nodes().attach(state.lease(), position());
      if (result.failure() != NodeRegistry.Failure.OK)
        throw new IllegalStateException("Buffer lease binding: " + result.failure());
      state = new BufferData.State(result.handle(), state.principal(), state.configuration());
      if (!saved.buffers().bind(state.lease(), position(), this))
        throw new IllegalStateException("Buffer inventory already has an active owner");
      var lease = state.lease();
      handler =
          new BufferedItemHandler(
              saved.buffers().inventory(lease, this),
              new PlatformResourceCodec(serverLevel().registryAccess()),
              () ->
                  !isRemoved()
                      && diagnostic.isEmpty()
                      && physicallyCurrent()
                      && saved.buffers().usable(lease, this));
      write();
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
      fault(failure);
    }
  }

  private void fault(RuntimeException failure) {
    var message =
        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    diagnostic = message.substring(0, Math.min(message.length(), 512));
    handler = null;
  }

  private void write() {
    data = BufferData.write(state);
    setChanged();
  }

  private void unbind() {
    var saved = authority();
    if (saved.buffers().boundTo(state.lease(), this))
      saved.transfers().unbindBacking(state.lease().id());
    saved.buffers().unbind(state.lease(), this);
    handler = null;
  }

  public ItemStack portableForBreak() {
    var stack = new ItemStack(BufferedInterfaceContent.ITEM.get());
    if (state != null && diagnostic.isEmpty()) {
      try {
        validatePlaced();
        unbind();
        var result = authority().nodes().detach(state.lease(), position());
        if (result.failure() != NodeRegistry.Failure.OK)
          throw new IllegalStateException("Buffer removal: " + result.failure());
        state = new BufferData.State(result.handle(), state.principal(), state.configuration());
        handler = null;
        write();
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
        fault(failure);
      }
    }
    if (data != null)
      CustomData.update(
          DataComponents.CUSTOM_DATA, stack, custom -> custom.put(BufferData.FIELD, data.copy()));
    return stack;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) bind(false);
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel && state != null) unbind();
    handler = null;
    WorldNetworks.changed(level, worldPosition);
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (data != null) tag.put(BufferData.FIELD, data.copy());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    if (level instanceof ServerLevel && state != null) unbind();
    handler = null;
    data = tag.contains(BufferData.FIELD) ? tag.get(BufferData.FIELD).copy() : null;
    parse();
    if (level instanceof ServerLevel) WorldNetworks.changed(level, worldPosition);
  }
}
