package dev.izzy.factorycore.platform.network;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;

/**
 * Q29 (QD5): a cable's colour. A dyed cable joins only cables of its own colour or uncoloured ones;
 * every other networked block ignores colour. Names are save format (block state).
 */
public enum CableColor implements StringRepresentable {
  NONE(null),
  WHITE(DyeColor.WHITE),
  ORANGE(DyeColor.ORANGE),
  MAGENTA(DyeColor.MAGENTA),
  LIGHT_BLUE(DyeColor.LIGHT_BLUE),
  YELLOW(DyeColor.YELLOW),
  LIME(DyeColor.LIME),
  PINK(DyeColor.PINK),
  GRAY(DyeColor.GRAY),
  LIGHT_GRAY(DyeColor.LIGHT_GRAY),
  CYAN(DyeColor.CYAN),
  PURPLE(DyeColor.PURPLE),
  BLUE(DyeColor.BLUE),
  BROWN(DyeColor.BROWN),
  GREEN(DyeColor.GREEN),
  RED(DyeColor.RED),
  BLACK(DyeColor.BLACK);

  /** The stripe of an uncoloured cable, matching the art's SIGNAL colour. */
  public static final int UNCOLOURED_TINT = 0x36C5F0;

  private final DyeColor dye;

  CableColor(DyeColor dye) {
    this.dye = dye;
  }

  /** The dye, or null for an uncoloured cable. */
  public DyeColor dye() {
    return dye;
  }

  public static CableColor of(DyeColor dye) {
    for (var color : values()) if (color.dye == dye) return color;
    throw new IllegalArgumentException("Unknown dye " + dye);
  }

  /** True when cables of these two colours connect. */
  public boolean joins(CableColor other) {
    return this == NONE || other == NONE || this == other;
  }

  /** The stripe tint, RGB. */
  public int tint() {
    return dye == null ? UNCOLOURED_TINT : dye.getTextureDiffuseColor() & 0xFFFFFF;
  }

  @Override
  public String getSerializedName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
