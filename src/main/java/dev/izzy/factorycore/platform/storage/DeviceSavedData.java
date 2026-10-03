package dev.izzy.factorycore.platform.storage;

import com.mojang.logging.LogUtils;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
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
  private final CompoundTag rejected;
  private final String diagnostic;

  private DeviceSavedData(
      DeviceRegistry.State state,
      List<NetworkPermissions.State> networkStates,
      CompoundTag rejected,
      String diagnostic) {
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
  }

  public static DeviceSavedData get(MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("Device registry belongs to the server thread");
    return open(
        server.overworld().getDataStorage(),
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat"));
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
        new DeviceRegistry.State(UUID.randomUUID(), List.of()), List.of(), null, "");
  }

  public DeviceRegistry registry() {
    if (registry == null)
      throw new IllegalStateException("Device registry quarantined: " + diagnostic);
    return registry;
  }

  /** Trusted server commissioning only; this creates neither stock nor a physical lease/scope. */
  public UUID createNetwork(UUID owner) {
    registry().world();
    Objects.requireNonNull(owner);
    if (networks.size() == MAX_NETWORKS) throw new IllegalStateException("Network count limit");
    UUID id = UUID.randomUUID();
    if (networks.containsKey(id) || registry.containsBacking(id))
      throw new IllegalStateException("Network identity collision");
    networks.put(id, restoreNetwork(new NetworkPermissions.State(id, owner, 1, Map.of())));
    setDirty();
    return id;
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
    if (networks.containsKey(id))
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
      if ((schema != 1 && schema != 2) || !tag.hasUUID("World"))
        throw new IllegalArgumentException("Unknown registry schema/world identity");
      NetworkStateCodec.fields(
          tag,
          schema == 1
              ? Set.of("Schema", "World", "Devices")
              : Set.of("Schema", "World", "Devices", "Networks"));
      var networkStates =
          schema == 1 ? List.<NetworkPermissions.State>of() : NetworkStateCodec.decode(tag);
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
      var loaded =
          new DeviceSavedData(
              new DeviceRegistry.State(tag.getUUID("World"), records), networkStates, null, "");
      if (schema == 1) loaded.setDirty();
      return loaded;
    } catch (RuntimeException invalid) {
      String message =
          invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
      return new DeviceSavedData(
          null, List.of(), tag.copy(), message.substring(0, Math.min(message.length(), 512)));
    }
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    if (rejected != null) return rejected.copy();
    var state = registry().snapshot();
    tag.putInt("Schema", 2);
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
    tag.put(
        "Networks",
        NetworkStateCodec.encode(
            networks.values().stream().map(NetworkPermissions::snapshot).toList()));
    return tag;
  }

  private static void require(CompoundTag tag, String field, int type) {
    if (!tag.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type registry field: " + field);
  }
}
