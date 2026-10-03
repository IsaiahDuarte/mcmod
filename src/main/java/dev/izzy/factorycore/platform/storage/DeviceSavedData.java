package dev.izzy.factorycore.platform.storage;

import com.mojang.logging.LogUtils;
import dev.izzy.factorycore.core.resource.LedgerStateCodec;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
  private static final Logger LOGGER = LogUtils.getLogger();
  private final DeviceRegistry registry;
  private final CompoundTag rejected;
  private final String diagnostic;

  private DeviceSavedData(DeviceRegistry.State state, CompoundTag rejected, String diagnostic) {
    this.rejected = rejected;
    this.diagnostic = diagnostic;
    registry =
        state == null ? null : DeviceRegistry.restore(state, this::setDirty, UUID::randomUUID);
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
        new DeviceRegistry.State(UUID.randomUUID(), java.util.List.of()), null, "");
  }

  public DeviceRegistry registry() {
    if (registry == null)
      throw new IllegalStateException("Device registry quarantined: " + diagnostic);
    return registry;
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
      if (tag.getInt("Schema") != 1 || !tag.hasUUID("World"))
        throw new IllegalArgumentException("Unknown registry schema/world identity");
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
      return new DeviceSavedData(new DeviceRegistry.State(tag.getUUID("World"), records), null, "");
    } catch (RuntimeException invalid) {
      String message =
          invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
      return new DeviceSavedData(
          null, tag.copy(), message.substring(0, Math.min(message.length(), 512)));
    }
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    if (rejected != null) return rejected.copy();
    var state = registry().snapshot();
    tag.putInt("Schema", 1);
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
    return tag;
  }

  private static void require(CompoundTag tag, String field, int type) {
    if (!tag.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type registry field: " + field);
  }
}
