package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.storage.DeviceRecoveryAudit;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.LeaseRecovery;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeable;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Chunk/item references; ownership and gateway policies live exclusively in canonical SavedData.
 */
public final class NetworkNodeEntity extends BlockEntity implements ThroughputUpgradeable {
  private Tag data;

  /** A gateway's installed wireless link (ADR 0061), or null. */
  private LinkModule link;

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

  public LinkModule link() {
    return link;
  }

  /**
   * Installs a wireless link on {@code side}, which must be this gateway's upstream or downstream
   * face; {@code carried} is an item's recovery data, re-read strictly, or null for a fresh link.
   */
  public void installLink(Direction side, UUID owner, CompoundTag carried) {
    if (kind() != NodeRegistry.Kind.GATEWAY) throw new IllegalStateException("link_needs_gateway");
    if (link != null) throw new IllegalStateException("link_installed_already");
    var facing = getBlockState().getValue(BlockStateProperties.FACING);
    if (side != facing && side != facing.getOpposite())
      throw new IllegalStateException("link_wrong_face");
    link = carried == null ? new LinkModule(this, side, owner) : LinkModule.load(this, carried);
    setChanged();
    link.refresh();
  }

  /** Removes the link for a break, returning the items it hands back. */
  public List<ItemStack> removeLinkForBreak() {
    if (link == null) return List.of();
    var drops = link.drops();
    var old = link;
    link = null;
    setChanged();
    old.refresh();
    return drops;
  }

  /** True while this exact entity is the loaded one at its position. */
  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  @Override
  public ThroughputTier tier() {
    return link == null ? ThroughputTier.BASE : link.tier();
  }

  /** Overdrive goes into an installed link; a gateway without one takes no upgrade. */
  @Override
  public boolean upgrade(NetworkPermissions.Actor actor, ThroughputTier item) {
    if (link == null) throw new IllegalStateException("upgrade_not_applicable");
    if (!link.mayConfigure(actor)) return false;
    link.install(item);
    return true;
  }

  @Override
  public ThroughputTier removeUpgrade(NetworkPermissions.Actor actor) {
    if (link == null) throw new IllegalStateException("upgrade_not_applicable");
    if (!link.mayConfigure(actor)) return null;
    return link.remove();
  }

  @Override
  public boolean linkUpgrade() {
    return true;
  }

  @Override
  public Component throughputStatus(MinecraftServer server) {
    return ThroughputStatus.link(server, link);
  }

  /**
   * A reference only; routing must also check current canonical position and validated topology.
   */
  public NodeRegistry.Handle handle() {
    return handle;
  }

  public NodeRegistry.Position position() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Structural node requires server level");
    return Positions.of(server, worldPosition);
  }

  private DeviceSavedData authority() {
    if (!(level instanceof ServerLevel server))
      throw new IllegalStateException("Structural node requires server level");
    return DeviceSavedData.get(server.getServer());
  }

  public void place(ItemStack stack, UUID player) {
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

  public void commission(UUID player) {
    if (!diagnostic.isEmpty() || handle != null)
      throw new IllegalStateException("Node already bound or needs recovery");
    handle =
        kind() == NodeRegistry.Kind.CONTROLLER
            ? authority().createController(Objects.requireNonNull(player), position())
            : authority().createGateway(position());
    data = NodeHandleData.write(handle);
    setChanged();
    networkingChanged();
  }

  /** Trusted operator recovery at the loaded original holder; permissions/policy never change. */
  public Path repairConflict(NodeRegistry.Handle expected) throws IOException {
    if (!(level instanceof ServerLevel server)
        || !server.getServer().isSameThread()
        || !LeaseRecovery.loadedHere(this)
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
        DeviceRecoveryAudit.preserveNetworkNode(
            server.getServer().getWorldPath(LevelResource.ROOT),
            expected.world(),
            owner,
            kind() == NodeRegistry.Kind.CONTROLLER ? saved.network(expected.id()).snapshot() : null,
            NbtUtils.writeBlockState(getBlockState()),
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
  public Path adoptNode(NodeRegistry.Handle expected) throws IOException {
    if (!(level instanceof ServerLevel server)
        || !server.getServer().isSameThread()
        || !LeaseRecovery.loadedHere(this)
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
    var old =
        LeaseRecovery.releasedOldHolder(
            server.getServer(),
            owner.position(),
            entity ->
                entity instanceof NetworkNodeEntity previous
                    && previous.handle != null
                    && previous.handle.id().equals(expected.id()));
    var path =
        DeviceRecoveryAudit.preserveNetworkNodeAdoption(
            server.getServer().getWorldPath(LevelResource.ROOT),
            expected.world(),
            owner,
            kind() == NodeRegistry.Kind.CONTROLLER ? saved.network(expected.id()).snapshot() : null,
            position(),
            NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(server.registryAccess()));
    var adopted = saved.nodes().adopt(expected, position());
    if (adopted.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Network-node adoption refused: " + adopted.failure());
    handle = adopted.handle();
    data = NodeHandleData.write(handle);
    diagnostic = "";
    setChanged();
    old.changed();
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
    if (link != null) tag.put(LinkModule.FIELD, link.save());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    data = tag.contains(NodeHandleData.FIELD) ? tag.get(NodeHandleData.FIELD).copy() : null;
    link =
        tag.contains(LinkModule.FIELD, Tag.TAG_COMPOUND)
            ? LinkModule.load(this, tag.getCompound(LinkModule.FIELD))
            : null;
    parse();
  }
}
