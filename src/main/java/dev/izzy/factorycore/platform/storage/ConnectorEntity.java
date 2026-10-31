package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.Failures;
import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.upgrade.ThroughputStatus;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeable;
import java.io.IOException;
import java.nio.file.Path;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Physical policy and reference holder; foreign contents are never copied into this block. */
public class ConnectorEntity extends BlockEntity
    implements WiredMember, CanonicalStorage, ThroughputUpgradeable {
  private Tag data;
  private ConnectorData.State state;
  private String diagnostic = "";
  private UUID providerToken = UUID.randomUUID();
  private BlockCapabilityCache<?, Direction> cache;
  private Direction cachedDirection;

  public ConnectorEntity(BlockPos position, BlockState blockState) {
    this(ConnectorContent.ENTITY.get(), position, blockState);
  }

  protected ConnectorEntity(BlockEntityType<?> type, BlockPos position, BlockState blockState) {
    super(type, position, blockState);
  }

  protected NodeRegistry.Kind leaseKind() {
    return NodeRegistry.Kind.CONNECTOR;
  }

  protected Item portableItem() {
    return InterfaceContent.ITEM.get();
  }

  public String label() {
    return state == null ? "" : state.label();
  }

  public NodeRegistry.Handle handle() {
    return state == null ? null : state.lease();
  }

  public ConnectorSettings settings() {
    return state == null ? ConnectorSettings.defaults(ResourceKind.ITEM) : state.settings();
  }

  /** Installed paid throughput tier (ADR 0054); BASE for an unplaced or faulted casing. */
  public ThroughputTier tier() {
    return state == null ? ThroughputTier.BASE : state.tier();
  }

  public long generation() {
    return state == null ? 0 : state.generation();
  }

  public String diagnostic() {
    return diagnostic;
  }

  public UUID providerToken() {
    return providerToken;
  }

  public Direction targetDirection() {
    return getBlockState().getValue(BlockStateProperties.FACING);
  }

  public NodeRegistry.Position position() {
    return Positions.of(serverLevel(), worldPosition);
  }

  public ServerLevel serverLevel() {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread())
      throw new IllegalStateException("Connector requires server thread");
    return server;
  }

  private DeviceSavedData authority() {
    return DeviceSavedData.get(serverLevel().getServer());
  }

  private WorldNetworks runtime() {
    return WorldNetworks.get(serverLevel().getServer());
  }

  public void place(ItemStack stack) {
    var custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (custom.contains(ConnectorData.FIELD)) {
      data = custom.get(ConnectorData.FIELD).copy();
      parse();
      bind(true);
    } else if (data != null) {
      parse();
      bind(true);
    } else {
      state =
          new ConnectorData.State(
              authority().createForeignNode(position(), leaseKind()),
              ConnectorSettings.defaults(ResourceKind.ITEM),
              1,
              "");
      write();
    }
    changed();
  }

  /**
   * Audited ownership repair only; persisted provider faults and accepted presets are not reset.
   */
  public Path repairConflict(NodeRegistry.Handle expected) throws IOException {
    var server = serverLevel();
    if (!LeaseRecovery.loadedHere(this)
        || state == null
        || !expected.equals(state.lease())
        || expected.kind() != leaseKind()
        || !state.equals(ConnectorData.read(data))
        || !state.fault().isEmpty())
      throw new IllegalStateException("Exact loaded nonfaulted port reference required");
    var saved = authority();
    var owner = saved.nodes().evidence(expected.id());
    if (owner == null
        || saved.nodes().validate(expected) != NodeRegistry.Failure.CONFLICT
        || !position().equals(owner.position())
        || expected.generation() == Long.MAX_VALUE
        || saved.transfers().unresolved(expected.id()) != null
        || saved.transfers().unresolvedDevice(expected.id()) != null)
      throw new IllegalStateException("Canonical port conflict cannot be repaired");
    boolean machine = leaseKind() == NodeRegistry.Kind.MACHINE_INTERFACE;
    long fence = machine ? saved.machinePresets().configuration(expected.id()) : 0;
    if (machine && (fence <= 0 || fence != state.generation()))
      throw new IllegalStateException(
          "Machine configuration fence does not match physical reference");
    var registration = runtime().connectors().recoveryRegistration(this);
    if (registration != null
        ? saved.transfers().unresolved(registration.backing()) != null
        : saved.transfers().hasUnscopedUncertain(saved::canonicalIdentity))
      throw new IllegalStateException("Unresolved or unscoped foreign ownership");
    var next =
        new ConnectorData.State(
            new NodeRegistry.Handle(
                expected.world(), expected.id(), expected.generation() + 1, expected.kind()),
            state.settings(),
            state.generation(),
            state.fault(),
            state.label(),
            state.tier());
    var nextData = ConnectorData.write(next);
    var path =
        DeviceRecoveryAudit.preservePort(
            server.getServer().getWorldPath(LevelResource.ROOT),
            expected.world(),
            owner,
            machine ? saved.machinePresets().get(expected.id()) : null,
            fence,
            registration,
            NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(server.registryAccess()));
    var repaired = saved.nodes().repairConflict(expected, position());
    if (repaired.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Port conflict repair refused: " + repaired.failure());
    WorldNetworks.connectorInvalidated(level, this);
    if (this instanceof MachineInterfaceEntity machineOwner)
      WorldNetworks.machineInvalidated(level, machineOwner);
    cache = null;
    cachedDirection = null;
    providerToken = UUID.randomUUID();
    state = next;
    data = nextData;
    diagnostic = "";
    setChanged();
    onLoad();
    return path;
  }

  /** Reconcile a stale crash-surviving holder without reading or moving foreign contents. */
  public Path adoptPort(NodeRegistry.Handle expected) throws IOException {
    var server = serverLevel();
    var saved = authority();
    var owner = saved.nodes().evidence(expected.id());
    if (!LeaseRecovery.loadedHere(this)
        || state == null
        || !state.equals(ConnectorData.read(data))
        || !state.fault().isEmpty()
        || expected.kind() != leaseKind()
        || owner == null
        || owner.position() == null
        || owner.kind() != expected.kind()
        || owner.generation() != expected.generation()
        || !state.lease().world().equals(expected.world())
        || !state.lease().id().equals(expected.id())
        || state.lease().generation() == expected.generation()
        || expected.generation() == Long.MAX_VALUE
        || position().equals(owner.position())
        || saved.transfers().unresolved(expected.id()) != null
        || saved.transfers().unresolvedDevice(expected.id()) != null
        || saved.transfers().hasUnscopedUncertain(saved::canonicalIdentity))
      throw new IllegalStateException("Port lease cannot be adopted");
    var old =
        LeaseRecovery.releasedOldHolder(
            server.getServer(),
            owner.position(),
            entity ->
                entity instanceof ConnectorEntity previous
                    && previous.handle() != null
                    && previous.handle().id().equals(expected.id()));
    boolean machine = leaseKind() == NodeRegistry.Kind.MACHINE_INTERFACE;
    long fence = machine ? saved.machinePresets().configuration(expected.id()) : 0;
    long configuration =
        machine ? Math.incrementExact(Math.max(fence, state.generation())) : state.generation();
    if (machine) saved.machinePresets().prepareInvalidation(expected.id());
    var next =
        new ConnectorData.State(
            new NodeRegistry.Handle(
                expected.world(), expected.id(), expected.generation() + 1, expected.kind()),
            state.settings(),
            configuration,
            "",
            state.label(),
            state.tier());
    var nextData = ConnectorData.write(next);
    var path =
        DeviceRecoveryAudit.preservePortAdoption(
            server.getServer().getWorldPath(LevelResource.ROOT),
            expected.world(),
            owner,
            machine ? saved.machinePresets().get(expected.id()) : null,
            fence,
            position(),
            NbtUtils.writeBlockState(getBlockState()),
            saveWithFullMetadata(server.registryAccess()));
    var adopted = saved.nodes().adopt(expected, position());
    if (adopted.failure() != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Port adoption refused: " + adopted.failure());
    WorldNetworks.connectorInvalidated(level, this);
    if (this instanceof MachineInterfaceEntity machineOwner)
      WorldNetworks.machineInvalidated(level, machineOwner);
    if (machine) {
      saved.machinePresets().invalidate(expected.id());
      saved.machinePresets().observeConfiguration(adopted.handle(), configuration);
    }
    cache = null;
    cachedDirection = null;
    providerToken = UUID.randomUUID();
    state = next;
    data = nextData;
    diagnostic = "";
    setChanged();
    old.changed();
    onLoad();
    return path;
  }

  public void validatePlaced() {
    if (!diagnostic.isEmpty()) throw new IllegalStateException(diagnostic);
    if (state == null
        || state.lease().kind() != leaseKind()
        || !position().equals(authority().nodes().definition(state.lease()).position()))
      throw new IllegalStateException("Connector has no current placed lease");
  }

  /** A live, nearby player with current VIEW at this placed port may open its settings. */
  public boolean canUse(ServerPlayer player) {
    try {
      validatePlaced();
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return false;
    }
    return player.isAlive()
        && player.serverLevel() == getLevel()
        && player.server.getPlayerList().getPlayer(player.getUUID()) == player
        && player.distanceToSqr(Vec3.atCenterOf(getBlockPos())) <= 64
        && WorldNetworks.get(player.server)
            .allows(Actors.of(player), position(), NetworkPermissions.Permission.VIEW);
  }

  public NetworkAuthority.Decision configure(
      NetworkPermissions.Actor actor, ConnectorSettings settings) {
    return configure(actor, settings, label());
  }

  protected NetworkAuthority.Decision configure(
      NetworkPermissions.Actor actor, ConnectorSettings settings, String label) {
    validatePlaced();
    return configure(actor, settings, label, Math.incrementExact(state.generation()));
  }

  protected NetworkAuthority.Decision configure(
      NetworkPermissions.Actor actor, ConnectorSettings settings, String label, long generation) {
    var candidate = candidate(settings, label, generation);
    var result = authorizeEdit(actor);
    if (result != NetworkAuthority.Decision.ALLOWED) return result;
    commit(candidate);
    return result;
  }

  /** The state a configuration would produce; throws before anything changes when invalid. */
  protected ConnectorData.State candidate(
      ConnectorSettings settings, String label, long generation) {
    Objects.requireNonNull(settings);
    validatePlaced();
    if (generation <= state.generation())
      throw new IllegalArgumentException("Configuration must advance");
    if (!settings.fits(state.tier()))
      throw new IllegalArgumentException(
          "Rate exceeds the installed throughput upgrade (max "
              + state.tier().portRate(settings.kind())
              + ")");
    return new ConnectorData.State(state.lease(), settings, generation, "", label, state.tier());
  }

  /** The actor's EDIT here; read-only. */
  protected NetworkAuthority.Decision authorizeEdit(NetworkPermissions.Actor actor) {
    return runtime().authorize(actor, position(), NetworkPermissions.Permission.EDIT);
  }

  /**
   * Commits an authorized {@link #candidate}. A new label republishes the topology, which starts a
   * network rebuild: authorize every port of a batch before committing any (Q9).
   */
  protected void commit(ConnectorData.State candidate) {
    boolean relabel = !candidate.label().equals(label());
    WorldNetworks.connectorInvalidated(level, this);
    cache = null;
    providerToken = UUID.randomUUID();
    state = candidate;
    write();
    if (relabel) changed();
  }

  /**
   * Installs one upgrade item under current EDIT authority. The port's rate becomes the new tier's
   * maximum for its resource (a configuration change like any other control; lower it afterwards in
   * settings). Throws with a translatable reason key before any change when the upgrade does not
   * apply; the caller consumes the item only after ALLOWED.
   */
  public NetworkAuthority.Decision installThroughput(
      NetworkPermissions.Actor actor, ThroughputTier upgrade) {
    validatePlaced();
    var next = state.tier().upgradeWith(upgrade, false);
    if (next == null) throw new IllegalStateException("upgrade_not_applicable");
    var current = state.settings();
    var settings =
        new ConnectorSettings(
            current.kind(),
            current.access(),
            current.priority(),
            current.firstSlot(),
            current.slotCount(),
            next.portRate(current.kind()),
            current.insert());
    return configureTier(actor, settings, next);
  }

  /**
   * Q22: turns the socket to {@code face} as a configuration change: EDIT here, nothing in transit
   * for this lease (the Interface-switch rules), then the generation advances through the same
   * commit as an upgrade (the faces carrying cables change, so the network rebuilds; a machine port
   * pauses its preset and its deployer's resume waits for the rebuild) and the provider rebinds on
   * the new face. Returns the decision; nothing changes unless it is ALLOWED. Throws {@code
   * turn_busy} while a transfer is unresolved.
   */
  public NetworkAuthority.Decision turn(NetworkPermissions.Actor actor, Direction face) {
    validatePlaced();
    if (face == targetDirection()) throw new IllegalArgumentException("turn_same");
    if (DeviceSavedData.get(serverLevel().getServer()).transfers().unresolvedDevice(handle().id())
        != null) throw new IllegalStateException("turn_busy");
    var result = configureTier(actor, settings(), tier());
    if (result != NetworkAuthority.Decision.ALLOWED) return result;
    level.setBlock(
        worldPosition,
        getBlockState().setValue(BlockStateProperties.FACING, face),
        net.minecraft.world.level.block.Block.UPDATE_ALL);
    return result;
  }

  /**
   * Q19: takes the top upgrade out; a rate above the lower ceiling is lowered to it. A machine
   * interface pauses its preset exactly as on install.
   */
  public NetworkAuthority.Decision removeThroughput(NetworkPermissions.Actor actor) {
    validatePlaced();
    var next = state.tier().removeStep(false);
    if (next == null) throw new IllegalStateException("upgrade_none");
    var current = state.settings();
    var settings =
        new ConnectorSettings(
            current.kind(),
            current.access(),
            current.priority(),
            current.firstSlot(),
            current.slotCount(),
            Math.min(current.rate(), next.portRate(current.kind())),
            current.insert());
    return configureTier(actor, settings, next);
  }

  /**
   * Shared tier commit; a machine interface overrides it to advance its preset configuration fence
   * like every other machine configuration change.
   */
  protected NetworkAuthority.Decision configureTier(
      NetworkPermissions.Actor actor, ConnectorSettings settings, ThroughputTier next) {
    return commitTier(actor, settings, next, Math.incrementExact(state.generation()));
  }

  protected final NetworkAuthority.Decision commitTier(
      NetworkPermissions.Actor actor,
      ConnectorSettings settings,
      ThroughputTier next,
      long generation) {
    validatePlaced();
    if (generation <= state.generation())
      throw new IllegalArgumentException("Configuration must advance");
    var candidate =
        new ConnectorData.State(state.lease(), settings, generation, "", state.label(), next);
    var result = runtime().authorize(actor, position(), NetworkPermissions.Permission.EDIT);
    if (result != NetworkAuthority.Decision.ALLOWED) return result;
    WorldNetworks.connectorInvalidated(level, this);
    cache = null;
    providerToken = UUID.randomUUID();
    state = candidate;
    write();
    changed();
    return result;
  }

  /** Caller must establish every provider chunk is loaded before invoking a capability getter. */
  /** Item slots and fluid tanks reported to settings screens are capped here (P5 slot picker). */
  public static final int MAX_REPORTED_SLOTS = 65_536;

  /**
   * The bound container's item slot and fluid tank counts on the bound face, each 0 when absent and
   * capped at {@link #MAX_REPORTED_SLOTS}. Two capability lookups; server thread only. The settings
   * screens use it to draw a slot picker; it grants no access.
   */
  public int[] containerSize() {
    var target = worldPosition.relative(targetDirection());
    var side = targetDirection().getOpposite();
    var items = serverLevel().getCapability(Capabilities.ItemHandler.BLOCK, target, side);
    var fluids = serverLevel().getCapability(Capabilities.FluidHandler.BLOCK, target, side);
    return new int[] {
      items == null ? 0 : Math.min(MAX_REPORTED_SLOTS, Math.max(0, items.getSlots())),
      fluids == null ? 0 : Math.min(MAX_REPORTED_SLOTS, Math.max(0, fluids.getTanks()))
    };
  }

  public Object capability() {
    validatePlaced();
    if (cache != null && cachedDirection != targetDirection()) {
      WorldNetworks.connectorInvalidated(level, this);
      cache = null;
      providerToken = UUID.randomUUID();
    }
    if (cache == null) {
      cachedDirection = targetDirection();
      var target = worldPosition.relative(targetDirection());
      var server = serverLevel();
      var side = targetDirection().getOpposite();
      cache =
          switch (settings().kind()) {
            case ITEM ->
                BlockCapabilityCache.create(
                    Capabilities.ItemHandler.BLOCK,
                    server,
                    target,
                    side,
                    () -> !isRemoved(),
                    this::invalidated);
            case FLUID ->
                BlockCapabilityCache.create(
                    Capabilities.FluidHandler.BLOCK,
                    server,
                    target,
                    side,
                    () -> !isRemoved(),
                    this::invalidated);
            case ENERGY ->
                BlockCapabilityCache.create(
                    Capabilities.EnergyStorage.BLOCK,
                    server,
                    target,
                    side,
                    () -> !isRemoved(),
                    this::invalidated);
          };
    }
    return cache.getCapability();
  }

  /**
   * D3 (ADR 0062): the same target block's capability seen from {@code face} of that block, looked
   * up afresh (uncached) for one statement. Null when it exposes none there.
   */
  public Object capabilityAt(Direction face) {
    validatePlaced();
    var target = worldPosition.relative(targetDirection());
    var server = serverLevel();
    return switch (settings().kind()) {
      case ITEM -> server.getCapability(Capabilities.ItemHandler.BLOCK, target, face);
      case FLUID -> server.getCapability(Capabilities.FluidHandler.BLOCK, target, face);
      case ENERGY -> server.getCapability(Capabilities.EnergyStorage.BLOCK, target, face);
    };
  }

  private void invalidated() {
    providerToken = UUID.randomUUID();
    WorldNetworks.connectorInvalidated(level, this);
  }

  public void quarantine(RuntimeException failure) {
    diagnostic = bounded(failure);
    state =
        new ConnectorData.State(
            state.lease(),
            state.settings(),
            state.generation(),
            diagnostic,
            state.label(),
            state.tier());
    write();
    changed();
  }

  private static String bounded(RuntimeException failure) {
    String message = Failures.message(failure);
    return message.substring(0, Math.min(message.length(), 512));
  }

  private void parse() {
    state = null;
    diagnostic = "";
    try {
      if (data != null) {
        state = ConnectorData.read(data);
        if (state.lease().kind() != leaseKind())
          throw new IllegalArgumentException("Wrong physical casing for lease");
        diagnostic = state.fault();
      }
    } catch (IllegalArgumentException failure) {
      diagnostic = bounded(failure);
    }
  }

  private void bind(boolean placement) {
    if (state == null || !diagnostic.isEmpty()) return;
    try {
      if (!placement && authority().nodes().definition(state.lease()).position() == null)
        throw new IllegalStateException("Portable connector reference in a chunk");
      var result = authority().nodes().attach(state.lease(), position());
      if (result.failure() != NodeRegistry.Failure.OK)
        throw new IllegalStateException("Connector binding: " + result.failure());
      state =
          new ConnectorData.State(
              result.handle(),
              state.settings(),
              state.generation(),
              state.fault(),
              state.label(),
              state.tier());
      write();
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
      diagnostic = bounded(failure);
    }
  }

  private void write() {
    data = ConnectorData.write(state);
    setChanged();
  }

  private void changed() {
    WorldNetworks.changed(level, worldPosition);
  }

  public ItemStack portableForBreak() {
    if (level instanceof ServerLevel) {
      WorldNetworks.connectorInvalidated(level, this);
      if (this instanceof MachineInterfaceEntity machineOwner)
        WorldNetworks.machineInvalidated(level, machineOwner);
    }
    cache = null;
    cachedDirection = null;
    providerToken = UUID.randomUUID();
    var stack = new ItemStack(portableItem());
    if (state != null && diagnostic.isEmpty()) {
      try {
        var result = authority().nodes().detach(state.lease(), position());
        if (result.failure() != NodeRegistry.Failure.OK)
          throw new IllegalStateException("Connector removal: " + result.failure());
        state =
            new ConnectorData.State(
                result.handle(),
                state.settings(),
                state.generation(),
                "",
                state.label(),
                state.tier());
        write();
      } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
        diagnostic = bounded(failure);
      }
    }
    if (data != null)
      CustomData.update(
          DataComponents.CUSTOM_DATA,
          stack,
          custom -> custom.put(ConnectorData.FIELD, data.copy()));
    return stack;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) bind(false);
    changed();
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel) WorldNetworks.connectorInvalidated(level, this);
    changed();
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (data != null) tag.put(ConnectorData.FIELD, data.copy());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    if (level instanceof ServerLevel) {
      WorldNetworks.connectorInvalidated(level, this);
      if (this instanceof MachineInterfaceEntity machineOwner)
        WorldNetworks.machineInvalidated(level, machineOwner);
    }
    data = tag.contains(ConnectorData.FIELD) ? tag.get(ConnectorData.FIELD).copy() : null;
    parse();
    cache = null;
    providerToken = UUID.randomUUID();
    if (level instanceof ServerLevel) changed();
  }

  /** Machine interfaces are labelled INTERFACE vertices; storage connectors are STORAGE. */
  @Override
  public Vertex wireVertex() {
    var lease = handle();
    boolean machine = lease != null && lease.kind() == NodeRegistry.Kind.MACHINE_INTERFACE;
    return new Vertex(
        lease == null ? null : lease.id(),
        "unowned",
        machine ? NetworkTopology.Role.INTERFACE : NetworkTopology.Role.STORAGE,
        WiredMember.placedValid(this::validatePlaced) && !isRemoved(),
        machine ? label() : "");
  }

  @Override
  public boolean upgrade(NetworkPermissions.Actor actor, ThroughputTier item) {
    return installThroughput(actor, item) == NetworkAuthority.Decision.ALLOWED;
  }

  @Override
  public ThroughputTier removeUpgrade(NetworkPermissions.Actor actor) {
    var removed = state.tier();
    return removeThroughput(actor) == NetworkAuthority.Decision.ALLOWED ? removed : null;
  }

  @Override
  public Component throughputStatus(MinecraftServer server) {
    return ThroughputStatus.port(server, this);
  }
}
