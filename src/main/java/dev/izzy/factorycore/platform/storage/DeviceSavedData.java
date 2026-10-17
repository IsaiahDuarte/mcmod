package dev.izzy.factorycore.platform.storage;

import com.mojang.logging.LogUtils;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ExternalClaims;
import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.resource.TransferRegistry;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankAccess;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.core.storage.RouteCursors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/** Overworld authority; unreadable/incompatible data never becomes a replacement empty registry. */
public final class DeviceSavedData extends SavedData {
  public static final String NAME = "factorycore_devices";
  public static final int MAX_NETWORKS = 1024;
  public static final int MAX_NETWORK_GRANTS = 65536;
  private final Map<UUID, NetworkPermissions> networks = new TreeMap<>();
  private int principalBindings;
  private static final Logger LOGGER = LogUtils.getLogger();
  private final DeviceRegistry registry;
  private EnergyBankAccess energyBanks;
  private final NodeRegistry nodes;
  private final TransferRegistry transfers;
  private final ExternalClaims externalClaims;
  private final RouteCursors routeCursors;
  private final BufferRegistry buffers;
  private final MachinePresets machinePresets;
  private final CompoundTag rejected;
  private final String diagnostic;

  private DeviceSavedData(
      DeviceRegistry.State state,
      List<NetworkPermissions.State> networkStates,
      List<NodeRegistry.Stored> nodeStates,
      List<TransferRegistry.Stored> transferStates,
      List<ExternalClaims.Claim> claimStates,
      List<RouteCursors.Cursor> cursorStates,
      List<ItemBuffer.State> bufferStates,
      List<BufferRegistry.Deployment> deploymentStates,
      List<MachinePresets.Deployment> machineStates,
      CompoundTag rejected,
      String diagnostic) {
    externalClaims = state == null ? null : new ExternalClaims(claimStates, this::setDirty);
    routeCursors = state == null ? null : new RouteCursors(cursorStates, this::setDirty);
    transfers = state == null ? null : new TransferRegistry(transferStates, this::setDirty);
    this.rejected = rejected;
    this.diagnostic = diagnostic;
    registry =
        state == null ? null : DeviceRegistry.restore(state, this::setDirty, this::newBackingId);
    for (var network : networkStates) {
      if (registry == null || registry.containsBacking(network.network()))
        throw new IllegalArgumentException("Network/backing identity collision");
      principalBindings += network.grants().size();
      if (networks.putIfAbsent(network.network(), restoreNetwork(network)) != null)
        throw new IllegalArgumentException("Duplicate network identity");
    }
    nodes = state == null ? null : new NodeRegistry(state.world(), nodeStates, this::setDirty);
    buffers =
        state == null
            ? null
            : new BufferRegistry(nodes, bufferStates, deploymentStates, this::setDirty);
    machinePresets =
        state == null ? null : new MachinePresets(nodes, machineStates, this::setDirty);
    for (var preset : machineStates) {
      var authority = preset.authority();
      if (!networks.containsKey(authority.network())
          || !nodes.contains(authority.network())
          || nodes.kind(authority.network()) != NodeRegistry.Kind.CONTROLLER
          || (!authority.segment().equals(authority.network())
              && (!nodes.contains(authority.segment())
                  || nodes.kind(authority.segment()) != NodeRegistry.Kind.GATEWAY)))
        throw new IllegalArgumentException("Machine deployment has unknown network/segment");
    }
    for (var node : nodeStates) {
      if (registry.containsBacking(node.id())
          || (node.kind() == NodeRegistry.Kind.CONTROLLER) != networks.containsKey(node.id()))
        throw new IllegalArgumentException("Invalid structural/network ownership binding");
    }
    for (var record : transferStates)
      if (record.context() != null) {
        var context = record.context();
        if (!state.world().equals(context.world()) || !networks.containsKey(context.network()))
          throw new IllegalArgumentException("Transfer context has unknown world/network");
        for (var endpoint :
            new dev.izzy.factorycore.core.resource.TransferContext.Endpoint[] {
              context.source(), context.destination()
            })
          if (endpoint.type() == dev.izzy.factorycore.core.resource.TransferContext.Type.PLAYER
              && (registry.containsBacking(endpoint.backing())
                  || nodes.contains(endpoint.backing())
                  || networks.containsKey(endpoint.backing())))
            throw new IllegalArgumentException("Player inventory collides with owned identity");
      }
    for (var claim : claimStates)
      if (registry.containsBacking(claim.backing())
          || nodes.contains(claim.backing())
          || networks.containsKey(claim.backing()))
        throw new IllegalArgumentException("Foreign claim backing collides with owned identity");
    for (var cursor : cursorStates)
      if (!networks.containsKey(cursor.scope().network()))
        throw new IllegalArgumentException("Route cursor has unknown network");
    for (var deployment : deploymentStates)
      if (!networks.containsKey(deployment.network())
          || nodeStates.stream()
              .noneMatch(
                  node ->
                      node.id().equals(deployment.segment())
                          && (node.kind() == NodeRegistry.Kind.GATEWAY
                              || node.kind() == NodeRegistry.Kind.CONTROLLER
                                  && node.id().equals(deployment.network()))))
        throw new IllegalArgumentException("Buffer deployment has unknown network/segment");
  }

  public static DeviceSavedData get(MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("Device registry belongs to the server thread");
    var saved =
        open(
            server.overworld().getDataStorage(),
            server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat"));
    if (saved.registry != null && saved.energyBanks == null)
      saved.energyBanks =
          new EnergyBankAccess(saved.registry, () -> Integer.toUnsignedLong(server.getTickCount()));
    if (saved.buffers != null) {
      var codec =
          new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(server.registryAccess());
      saved.buffers.stackLimit(key -> codec.itemStack(key, 1).getMaxStackSize());
    }
    return saved;
  }

  public EnergyBankAccess energyBanks() {
    registry().world();
    if (energyBanks == null)
      throw new IllegalStateException("Bank service requires a running server");
    return energyBanks;
  }

  public static void serverStarted(ServerStartedEvent event) {
    try {
      get(event.getServer()).registry();
    } catch (IllegalStateException unavailable) {
      LOGGER.error(
          "Factory Core storage unavailable; preserve factorycore_devices.dat for recovery",
          unavailable);
    }
  }

  static DeviceSavedData open(DimensionDataStorage storage, Path file) {
    var loaded =
        storage.get(new SavedData.Factory<>(DeviceSavedData::create, DeviceSavedData::load), NAME);
    if (loaded != null) return loaded;
    // DimensionDataStorage catches deserialization/I/O errors. Absence must be proven before
    // creation.
    if (!Files.notExists(file))
      throw new IllegalStateException(
          "Device registry is unreadable; preserve " + file + " for recovery");
    var created = create();
    storage.set(NAME, created);
    created.setDirty();
    return created;
  }

  private static DeviceSavedData create() {
    return new DeviceSavedData(
        new DeviceRegistry.State(UUID.randomUUID(), List.of()),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        "");
  }

  public DeviceRegistry registry() {
    if (registry == null)
      throw new IllegalStateException("Device registry quarantined: " + diagnostic);
    return registry;
  }

  public ExternalClaims externalClaims() {
    registry().world();
    return externalClaims;
  }

  public RouteCursors routeCursors() {
    registry().world();
    return routeCursors;
  }

  public MachinePresets machinePresets() {
    registry().world();
    return machinePresets;
  }

  public BufferRegistry buffers() {
    registry().world();
    return buffers;
  }

  /** Preflight structural and owned inventory quotas together; this does not bind a live block. */
  public NodeRegistry.Handle createBuffer(NodeRegistry.Position position) {
    var admission = nodes().admission(position);
    if (admission != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Buffer placement admission: " + admission);
    if (!buffers().canCreate()) throw new IllegalStateException("Buffer inventory admission limit");
    var id = newBackingId();
    if (registry.containsBacking(id))
      throw new IllegalStateException("Buffer/backing identity collision");
    var handle = nodes.create(id, NodeRegistry.Kind.BUFFERED_INTERFACE, position).handle();
    buffers.create(handle);
    return handle;
  }

  public TransferRegistry transfers() {
    registry().world();
    return transfers;
  }

  /** Trusted server commissioning only; this creates neither stock nor a physical lease/scope. */
  public UUID createNetwork(UUID owner) {
    registry().world();
    Objects.requireNonNull(owner);
    if (networks.size() == MAX_NETWORKS) throw new IllegalStateException("Network count limit");
    UUID id = UUID.randomUUID();
    if (networks.containsKey(id) || nodes.contains(id) || registry.containsBacking(id))
      throw new IllegalStateException("Network identity collision");
    networks.put(id, restoreNetwork(new NetworkPermissions.State(id, owner, 1, Map.of())));
    setDirty();
    return id;
  }

  public NodeRegistry nodes() {
    registry().world();
    return nodes;
  }

  /** Preflight both authorities before creating a root; owner is authenticated by the adapter. */
  public NodeRegistry.Handle createController(UUID owner, NodeRegistry.Position position) {
    Objects.requireNonNull(owner);
    var admission = nodes().admission(position);
    if (admission != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Controller admission: " + admission);
    UUID id = createNetwork(owner);
    return nodes.create(id, NodeRegistry.Kind.CONTROLLER, position).handle();
  }

  public NodeRegistry.Handle createGateway(NodeRegistry.Position position) {
    var admission = nodes().admission(position);
    if (admission != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Gateway admission: " + admission);
    UUID id = newBackingId();
    if (registry.containsBacking(id))
      throw new IllegalStateException("Gateway/backing identity collision");
    return nodes.create(id, NodeRegistry.Kind.GATEWAY, position).handle();
  }

  public NodeRegistry.Handle createConnector(NodeRegistry.Position position) {
    return createForeignNode(position, NodeRegistry.Kind.CONNECTOR);
  }

  NodeRegistry.Handle createForeignNode(NodeRegistry.Position position, NodeRegistry.Kind kind) {
    if (kind != NodeRegistry.Kind.CONNECTOR && kind != NodeRegistry.Kind.MACHINE_INTERFACE)
      throw new IllegalArgumentException("Foreign node kind");
    var admission = nodes().admission(position);
    if (admission != NodeRegistry.Failure.OK)
      throw new IllegalStateException("Connector admission: " + admission);
    UUID id = newBackingId();
    if (registry.containsBacking(id))
      throw new IllegalStateException("Connector/backing identity collision");
    return nodes.create(id, kind, position).handle();
  }

  /** Missing references never create replacement permissions or an implicit owner. */
  public NetworkPermissions network(UUID id) {
    registry().world();
    var permissions = networks.get(Objects.requireNonNull(id));
    if (permissions == null) throw new IllegalArgumentException("Unknown network identity");
    return permissions;
  }

  public int principalBindings() {
    registry().world();
    return principalBindings;
  }

  private NetworkPermissions restoreNetwork(NetworkPermissions.State state) {
    return new NetworkPermissions(
        state,
        this::setDirty,
        delta -> principalBindings + delta >= 0 && principalBindings + delta <= MAX_NETWORK_GRANTS,
        delta -> principalBindings += delta);
  }

  private UUID newBackingId() {
    UUID id = UUID.randomUUID();
    if (networks.containsKey(id) || nodes.contains(id))
      throw new IllegalStateException("Backing/network identity collision");
    return id;
  }

  public String diagnostic() {
    return diagnostic;
  }

  @Override
  public void setDirty(boolean dirty) {
    if (dirty && rejected != null)
      throw new IllegalStateException("Quarantined registry cannot overwrite its recovery file");
    super.setDirty(dirty);
  }

  public static DeviceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
    try {
      require(tag, "Schema", Tag.TAG_INT);
      int schema = tag.getInt("Schema");
      if ((schema < 1 || schema > 12) || !tag.hasUUID("World"))
        throw new IllegalArgumentException("Unknown registry schema/world identity");
      NetworkStateCodec.fields(tag, worldFields(schema));
      var networkStates =
          schema == 1 ? List.<NetworkPermissions.State>of() : NetworkStateCodec.decode(tag);
      var nodeStates = schema < 3 ? List.<NodeRegistry.Stored>of() : NodeStateCodec.decode(tag);
      require(tag, "Devices", Tag.TAG_LIST);
      ListTag devices = tag.getList("Devices", Tag.TAG_COMPOUND);
      if (devices.size() != ((ListTag) tag.get("Devices")).size()
          || devices.size() > DeviceRegistry.MAX_DEVICES) {
        throw new IllegalArgumentException("Invalid device list");
      }
      var records = new ArrayList<DeviceRegistry.Stored>(devices.size());
      long bytes = 0;
      long entries = 0;
      for (int i = 0; i < devices.size(); i++) {
        CompoundTag entry = devices.getCompound(i);
        NetworkStateCodec.fields(entry, Set.of("Ledger", "Generation", "Conflict", "Location"));
        require(entry, "Ledger", Tag.TAG_BYTE_ARRAY);
        require(entry, "Generation", Tag.TAG_LONG);
        require(entry, "Conflict", Tag.TAG_BYTE);
        if (entry.getByte("Conflict") < 0 || entry.getByte("Conflict") > 1)
          throw new IllegalArgumentException("Invalid conflict flag");
        byte[] encoded = entry.getByteArray("Ledger");
        bytes += encoded.length;
        if (bytes > DeviceRegistry.MAX_LEDGER_BYTES)
          throw new IllegalArgumentException("World ledger byte limit");
        DeviceRegistry.Location location = null;
        if (entry.contains("Location")) {
          require(entry, "Location", Tag.TAG_COMPOUND);
          var placed = entry.getCompound("Location");
          NetworkStateCodec.fields(placed, Set.of("Dimension", "Position", "Slot"));
          require(placed, "Dimension", Tag.TAG_STRING);
          require(placed, "Position", Tag.TAG_LONG);
          require(placed, "Slot", Tag.TAG_INT);
          location =
              new DeviceRegistry.Location(
                  placed.getString("Dimension"), placed.getLong("Position"), placed.getInt("Slot"));
        }
        var ledger = LedgerStateCodec.decode(encoded);
        entries += (long) ledger.contents().size() + ledger.reservations().size();
        if (entries > DeviceRegistry.MAX_WORLD_ENTRIES) {
          throw new IllegalArgumentException("World ledger entry limit");
        }
        records.add(
            new DeviceRegistry.Stored(
                ledger, entry.getLong("Generation"), location, entry.getBoolean("Conflict")));
      }
      if (schema >= 6) require(tag, "ExternalClaims", Tag.TAG_COMPOUND);
      if (schema >= 7) require(tag, "RouteCursors", Tag.TAG_COMPOUND);
      if (schema >= 8) require(tag, "Buffers", Tag.TAG_COMPOUND);
      var transferStates =
          schema < 4 ? List.<TransferRegistry.Stored>of() : TransferStateCodec.decode(tag);
      if (schema < 9
          && transferStates.stream()
              .anyMatch(
                  record ->
                      record.context() != null
                          && (record.context().source().type()
                                  == dev.izzy.factorycore.core.resource.TransferContext.Type.BUFFER
                              || record.context().destination().type()
                                  == dev.izzy.factorycore.core.resource.TransferContext.Type
                                      .BUFFER)))
        throw new IllegalArgumentException("Buffer transfer requires world schema nine");
      if (schema < 10
          && transferStates.stream()
              .anyMatch(
                  record ->
                      record.context() != null
                          && (record.context().source().type()
                                  == dev.izzy.factorycore.core.resource.TransferContext.Type.PLAYER
                              || record.context().destination().type()
                                  == dev.izzy.factorycore.core.resource.TransferContext.Type
                                      .PLAYER)))
        throw new IllegalArgumentException("Player transfer requires world schema ten");
      var loaded =
          new DeviceSavedData(
              new DeviceRegistry.State(tag.getUUID("World"), records),
              networkStates,
              nodeStates,
              transferStates,
              schema < 6
                  ? List.of()
                  : ExternalClaimsCodec.decode(tag.getCompound("ExternalClaims")),
              schema < 7 ? List.of() : RouteCursorCodec.decode(tag.getCompound("RouteCursors")),
              schema < 8 ? List.of() : BufferStateCodec.decode(tag.getCompound("Buffers")),
              schema < 8 ? List.of() : BufferStateCodec.deployments(tag.getCompound("Buffers")),
              schema < 12
                  ? List.of()
                  : MachinePresetCodec.decode(tag.getCompound("MachinePresets")),
              null,
              "");
      if (schema < 12) loaded.setDirty();
      return loaded;
    } catch (RuntimeException invalid) {
      String message =
          invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
      return new DeviceSavedData(
          null,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          tag.copy(),
          message.substring(0, Math.min(message.length(), 512)));
    }
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    if (rejected != null) return rejected.copy();
    var state = registry().snapshot();
    tag.putInt("Schema", 12);
    tag.putUUID("World", state.world());
    var devices = new ListTag();
    for (var record : state.devices()) {
      var entry = new CompoundTag();
      entry.putByteArray("Ledger", LedgerStateCodec.encode(record.ledger()));
      entry.putLong("Generation", record.generation());
      entry.putBoolean("Conflict", record.conflict());
      if (record.location() != null) {
        var location = new CompoundTag();
        location.putString("Dimension", record.location().dimension());
        location.putLong("Position", record.location().position());
        location.putInt("Slot", record.location().slot());
        entry.put("Location", location);
      }
      devices.add(entry);
    }
    tag.put("Devices", devices);
    tag.put("Nodes", NodeStateCodec.encode(nodes.snapshot()));
    tag.put("MachinePresets", MachinePresetCodec.encode(machinePresets.snapshot()));
    tag.put("Transfers", TransferStateCodec.encode(transfers.snapshot()));
    tag.put("ExternalClaims", ExternalClaimsCodec.encode(externalClaims.snapshot()));
    tag.put("RouteCursors", RouteCursorCodec.encode(routeCursors.snapshot()));
    tag.put("Buffers", BufferStateCodec.encode(buffers.snapshot(), buffers.deployments()));
    tag.put(
        "Networks",
        NetworkStateCodec.encode(
            networks.values().stream().map(NetworkPermissions::snapshot).toList()));
    return tag;
  }

  private static Set<String> worldFields(int schema) {
    var fields = new java.util.HashSet<>(Set.of("Schema", "World", "Devices"));
    if (schema >= 2) fields.add("Networks");
    if (schema >= 3) fields.add("Nodes");
    if (schema >= 4) fields.add("Transfers");
    if (schema >= 6) fields.add("ExternalClaims");
    if (schema >= 7) fields.add("RouteCursors");
    if (schema >= 8) fields.add("Buffers");
    if (schema >= 12) fields.add("MachinePresets");
    return fields;
  }

  private static void require(CompoundTag tag, String field, int type) {
    if (!tag.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type registry field: " + field);
  }
}
