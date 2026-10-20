package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * The strict reading rules every persisted codec shares: unknown keys and missing or mistyped
 * fields reject the whole record (it is then quarantined with its original tag), never default.
 * Mojang {@code Codec}s ignore unknown keys, which is why these codecs read NBT directly.
 */
final class Nbt {
  private Nbt() {}

  /** Rejects any key outside {@code allowed}. */
  static void fields(CompoundTag tag, Set<String> allowed) {
    if (!allowed.containsAll(tag.getAllKeys()))
      throw new IllegalArgumentException("Unknown registry fields");
  }

  /** Rejects a missing {@code field} or one of another NBT {@code type}; {@code what} names it. */
  static void require(CompoundTag tag, String field, int type, String what) {
    if (!tag.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type " + what + " field: " + field);
  }

  /** A cell or bank slot location: {@code {Dimension, Position, Slot}}. */
  static CompoundTag location(DeviceRegistry.Location location) {
    var tag = new CompoundTag();
    tag.putString("Dimension", location.dimension());
    tag.putLong("Position", location.position());
    tag.putInt("Slot", location.slot());
    return tag;
  }

  /** Strict inverse of {@link #location(DeviceRegistry.Location)}. */
  static DeviceRegistry.Location location(CompoundTag tag, String what) {
    fields(tag, Set.of("Dimension", "Position", "Slot"));
    require(tag, "Dimension", Tag.TAG_STRING, what);
    require(tag, "Position", Tag.TAG_LONG, what);
    require(tag, "Slot", Tag.TAG_INT, what);
    return new DeviceRegistry.Location(
        tag.getString("Dimension"), tag.getLong("Position"), tag.getInt("Slot"));
  }
}
