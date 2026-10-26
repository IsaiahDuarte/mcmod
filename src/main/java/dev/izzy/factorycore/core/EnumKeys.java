package dev.izzy.factorycore.core;

import java.util.Locale;

/**
 * Stable keys for enums that travel in packets, persist in saves or name a lang entry: the
 * constant's name in lower case ({@code MACHINE_FULL} is {@code machine_full}). Renaming a constant
 * changes its key, so a persisted or translated enum keeps its constant names.
 */
public final class EnumKeys {
  /** The longest key any keyed enum may have; packets read keys bounded by it. */
  public static final int MAX_LENGTH = 32;

  private EnumKeys() {}

  public static String key(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  /** The constant whose key is {@code key}, or null for an unknown or overlong key. */
  public static <E extends Enum<E>> E parse(Class<E> type, String key) {
    if (key == null || key.isEmpty() || key.length() > MAX_LENGTH) return null;
    for (var value : type.getEnumConstants()) if (key(value).equals(key)) return value;
    return null;
  }
}
