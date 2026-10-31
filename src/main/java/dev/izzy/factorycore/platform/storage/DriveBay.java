package dev.izzy.factorycore.platform.storage;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * Q72: what a drive bay's lamp shows, presentation only (gameplay always reads the entity). Each
 * state has its own glyph as well as colour: none, one lit lamp, two amber lamps, two red lamps
 * with a red fill bar, or a dark lamp.
 */
public enum DriveBay implements StringRepresentable {
  /** No cell. */
  EMPTY,
  /** A cell on a working network with room left. */
  ONLINE,
  /** At least three quarters full. */
  NEAR_FULL,
  /** No room left. */
  FULL,
  /** A cell the network cannot use now: the drive is offline or the cell is faulty. */
  OFFLINE;

  /** The lamp for a bay. An infinite cell is never full. */
  public static DriveBay of(
      boolean installed, boolean online, long stored, long capacity, boolean infinite) {
    if (!installed) return EMPTY;
    if (!online) return OFFLINE;
    if (infinite || capacity <= 0) return ONLINE;
    if (stored >= capacity) return FULL;
    // 4 × stored ≥ 3 × capacity, without overflow for any long capacity.
    if (stored >= capacity - capacity / 4) return NEAR_FULL;
    return ONLINE;
  }

  @Override
  public String getSerializedName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
