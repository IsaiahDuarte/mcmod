package dev.izzy.factorycore.platform.session;

import dev.izzy.factorycore.core.EnumKeys;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Writes keyed enums ({@link EnumKeys}) to packets by key, and reads them back with a bounded
 * string read. An unknown key is refused rather than shown raw or mapped to a default, so a client
 * and server that disagree fail at the packet instead of on screen.
 */
public final class WireEnums {
  private WireEnums() {}

  /** Writes {@code value}'s key, or an empty key for null. */
  public static void write(FriendlyByteBuf buffer, Enum<?> value) {
    buffer.writeUtf(value == null ? "" : EnumKeys.key(value), EnumKeys.MAX_LENGTH);
  }

  /** Reads a value written by {@link #write}; an empty key is null. */
  public static <E extends Enum<E>> E readNullable(FriendlyByteBuf buffer, Class<E> type) {
    var key = buffer.readUtf(EnumKeys.MAX_LENGTH);
    if (key.isEmpty()) return null;
    var value = EnumKeys.parse(type, key);
    if (value == null)
      throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " " + key);
    return value;
  }

  /** Reads a value that must be present. */
  public static <E extends Enum<E>> E read(FriendlyByteBuf buffer, Class<E> type) {
    var value = readNullable(buffer, type);
    if (value == null) throw new IllegalArgumentException("Missing " + type.getSimpleName());
    return value;
  }
}
