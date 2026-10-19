package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.BufferedItemHandler;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.UUID;
import java.util.function.Consumer;
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
public final class BufferedInterfaceEntity extends BlockEntity
    implements net.minecraft.world.MenuProvider {
  private UUID opening;

  public boolean canUse(net.minecraft.server.level.ServerPlayer player) {
    try {
      validatePlaced();
    } catch (IllegalStateException | IllegalArgumentException invalid) {
      return false;
    }
    return player.isAlive()
        && player.serverLevel() == level
        && player.server.getPlayerList().getPlayer(player.getUUID()) == player
        && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(worldPosition)) <= 64
        && WorldNetworks.get(player.server)
                .authorize(
                    new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                    position(),
                    NetworkPermissions.Permission.VIEW)
            == NetworkAuthority.Decision.ALLOWED;
  }

  UUID sessionForOpen() {
    return opening;
  }

  @Override
  public net.minecraft.network.chat.Component getDisplayName() {
    return net.minecraft.network.chat.Component.translatable(
        "block.factorycore.buffered_interface");
  }

  @Override
  public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
      int id,
      net.minecraft.world.entity.player.Inventory inventory,
      net.minecraft.world.entity.player.Player player) {
    if (!(player instanceof net.minecraft.server.level.ServerPlayer server) || !canUse(server))
      return null;
    opening = UUID.randomUUID();
    return new BufferSettingsMenu(id, inventory, this, opening);
  }

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

  /**
   * Trusted operator recovery; stock/deployment survive and old automation needs explicit reapply.
   */
  public java.nio.file.Path repairConflict(NodeRegistry.Handle expected)
      throws java.io.IOException {
    serverLevel();
    if (isRemoved()
        || !physicallyCurrent()
        || state == null
        || !expected.equals(state.lease())
        || !state.equals(BufferData.read(data)))
      throw new IllegalStateException("Exact loaded buffer reference required");
    var saved = authority();
    var owner = saved.nodes().evidence(expected.id());
    if (owner == null
        || owner.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE
        || !owner.conflict()
        || saved.nodes().validate(expected) != NodeRegistry.Failure.CONFLICT
        || !position().equals(owner.position())
        || expected.generation() == Long.MAX_VALUE
        || saved.transfers().unresolved(expected.id()) != null)
      throw new IllegalStateException("Canonical buffer conflict cannot be repaired");
    var next =
        new BufferData.State(
            new NodeRegistry.Handle(
                expected.world(), expected.id(), expected.generation() + 1, expected.kind()),
            state.principal(),
            state.configuration(),
            state.tier());
    var nextData = BufferData.write(next);
    var path =
        DeviceRecoveryAudit.preserveBuffer(
            serverLevel()
                .getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
            expected.world(),
            owner,
            saved.buffers().evidence(expected.id()),
            saved.buffers().deployment(expected.id()),
            net.minecraft.nbt.NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(serverLevel().registryAccess()));
    var repaired = saved.buffers().repairConflict(expected, position());
    if (repaired.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Buffer conflict repair refused: " + repaired.failure());
    WorldNetworks.bufferInvalidated(level, this);
    saved.transfers().unbindBacking(expected.id());
    handler = null;
    state = next;
    data = nextData;
    diagnostic = "";
    setChanged();
    onLoad();
    return path;
  }

  /**
   * Independent-save reconciliation adapter: adopts this loaded buffer's mismatched reference as
   * canonical. Refuses unless the old canonical position is loaded and is not a buffer referencing
   * this ID, this reference has the same world/ID with a different generation, and no transfer is
   * unresolved. Slots and the accepted deployment are unchanged; automation needs reapply.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level and server.
  public java.nio.file.Path adoptBuffer(NodeRegistry.Handle expected) throws java.io.IOException {
    var server = serverLevel();
    if (isRemoved() || !physicallyCurrent() || state == null)
      throw new IllegalStateException("Adopting buffer must be loaded and readable");
    var saved = authority();
    var owner = saved.nodes().evidence(expected.id());
    var physical = state.lease();
    if (owner == null
        || owner.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE
        || owner.position() == null
        || owner.generation() != expected.generation()
        || !physical.world().equals(expected.world())
        || !physical.id().equals(expected.id())
        || physical.generation() == expected.generation()
        || expected.generation() == Long.MAX_VALUE
        || saved.transfers().unresolved(expected.id()) != null)
      throw new IllegalStateException("Buffer lease cannot be adopted");
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
    if (oldLevel.getBlockEntity(oldPos) instanceof BufferedInterfaceEntity previous
        && previous.state != null
        && previous.state.lease().id().equals(expected.id()))
      throw new IllegalStateException("Old canonical holder still references the buffer");
    var path =
        DeviceRecoveryAudit.preserveBufferAdoption(
            server.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
            expected.world(),
            owner,
            saved.buffers().evidence(expected.id()),
            saved.buffers().deployment(expected.id()),
            position(),
            net.minecraft.nbt.NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(server.registryAccess()));
    var adopted = saved.buffers().adopt(expected, position());
    if (adopted.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Buffer adoption refused: " + adopted.failure());
    WorldNetworks.bufferInvalidated(level, this);
    saved.transfers().unbindBacking(expected.id());
    handler = null;
    state =
        new BufferData.State(
            adopted.handle(), state.principal(), state.configuration(), state.tier());
    data = BufferData.write(state);
    diagnostic = "";
    setChanged();
    WorldNetworks.changed(oldLevel, oldPos);
    onLoad();
    return path;
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

  /** Authenticated server controls; reference provenance never supplies deployment authority. */
  public BufferRegistry.Deployment configure(
      NetworkPermissions.Actor actor, BufferRegistry.Mode mode) {
    validatePlaced();
    var previous = authority().buffers().deployment(state.lease().id());
    return configure(
        actor,
        mode,
        previous == null
            ? dev.izzy.factorycore.core.storage.BufferPolicy.DEFAULT
            : previous.policy());
  }

  /** Installed paid throughput tier (ADR 0054); BASE before placement. */
  public dev.izzy.factorycore.core.resource.ThroughputTier tier() {
    return state == null ? dev.izzy.factorycore.core.resource.ThroughputTier.BASE : state.tier();
  }

  /**
   * Installs one upgrade: re-deploys the current mode and policy with the new tier's maximum rate
   * under the installer's current EDIT/DEPLOY (and, while active, transfer) authority, exactly like
   * a settings change. Throws before any change when the upgrade does not apply or is denied; the
   * caller consumes the item only after this returns.
   */
  public BufferRegistry.Deployment installThroughput(
      NetworkPermissions.Actor actor, dev.izzy.factorycore.core.resource.ThroughputTier upgrade) {
    validatePlaced();
    var next = state.tier().upgradeWith(upgrade, false);
    if (next == null) throw new IllegalStateException("upgrade_not_applicable");
    var previous = authority().buffers().deployment(state.lease().id());
    var policy =
        previous == null
            ? dev.izzy.factorycore.core.storage.BufferPolicy.DEFAULT
            : previous.policy();
    return configure(
        actor,
        previous == null ? BufferRegistry.Mode.PAUSED : previous.mode(),
        new dev.izzy.factorycore.core.storage.BufferPolicy(
            policy.filter(), next.bufferRate(), policy.target()),
        next);
  }

  public BufferRegistry.Deployment configure(
      NetworkPermissions.Actor actor,
      BufferRegistry.Mode mode,
      dev.izzy.factorycore.core.storage.BufferPolicy policy) {
    validatePlaced();
    return configure(actor, mode, policy, state.tier());
  }

  private BufferRegistry.Deployment configure(
      NetworkPermissions.Actor actor,
      BufferRegistry.Mode mode,
      dev.izzy.factorycore.core.storage.BufferPolicy policy,
      dev.izzy.factorycore.core.resource.ThroughputTier tier) {
    if (!policy.fits(tier))
      throw new IllegalStateException(
          "Rate exceeds the installed throughput upgrade (max " + tier.bufferRate() + ")");
    var networks = WorldNetworks.get(serverLevel().getServer());
    var member = networks.membership(position());
    if (member.status() != NetworkTopology.Status.VALID)
      throw new IllegalStateException("Buffer network is offline");
    for (var permission :
        new NetworkPermissions.Permission[] {
          NetworkPermissions.Permission.EDIT, NetworkPermissions.Permission.DEPLOY
        })
      if (networks.authorize(actor, position(), permission) != NetworkAuthority.Decision.ALLOWED)
        throw new IllegalStateException("Buffer deployment permission denied");
    if (mode != BufferRegistry.Mode.PAUSED)
      for (var permission :
          new NetworkPermissions.Permission[] {
            NetworkPermissions.Permission.VIEW,
            NetworkPermissions.Permission.WITHDRAW,
            NetworkPermissions.Permission.DEPOSIT
          })
        if (networks.authorize(actor, position(), permission) != NetworkAuthority.Decision.ALLOWED)
          throw new IllegalStateException("Buffer transfer permission denied");
    var deployment =
        authority()
            .buffers()
            .configure(
                state.lease(),
                this,
                state.configuration(),
                actor.id(),
                member.scope().network(),
                member.scope().segment(),
                mode,
                policy);
    authority().transfers().unbindBacking(state.lease().id());
    state = new BufferData.State(state.lease(), state.principal(), deployment.revision(), tier);
    write();
    return deployment;
  }

  public IItemHandler capability() {
    try {
      validatePlaced();
      return handler;
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  /** Explicit cleanup by the original owner; only known staging returns, never fresh extraction. */
  public WorldStorageTransfers.Submission returnStaging(
      NetworkPermissions.Actor actor, Consumer<WorldStorageTransfers.Result> completion) {
    validatePlaced();
    var saved = authority();
    var record = saved.transfers().unresolved(state.lease().id());
    if (record == null)
      return new WorldStorageTransfers.Submission(null, OperationResult.Reason.DENIED);
    if (!record.transfer().owner().equals(actor.id())
        || record.context() == null
        || !((record.context().source().type() == TransferContext.Type.BUFFER
                && record.context().source().backing().equals(state.lease().id()))
            || (record.context().destination().type() == TransferContext.Type.BUFFER
                && record.context().destination().backing().equals(state.lease().id()))))
      return new WorldStorageTransfers.Submission(null, OperationResult.Reason.DENIED);
    return WorldNetworks.get(serverLevel().getServer())
        .transfers()
        .progress(
            record.id(),
            actor,
            true,
            result -> {
              if (result.transfer() != null
                  && result.transfer().state() == StagedTransfer.State.COMPLETE
                  && result.transfer().pending() == StagedTransfer.PendingCall.NONE
                  && result.transfer().staged() == 0
                  && saved.transfers().contains(result.id())) saved.transfers().retire(result.id());
              completion.accept(result);
            });
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
      state =
          new BufferData.State(
              result.handle(), state.principal(), state.configuration(), state.tier());
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
      WorldNetworks.get(serverLevel().getServer()).imports().register(this);
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
    WorldNetworks.bufferInvalidated(level, this);
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
        state =
            new BufferData.State(
                result.handle(), state.principal(), state.configuration(), state.tier());
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
