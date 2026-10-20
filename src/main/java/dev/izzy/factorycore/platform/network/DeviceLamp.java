package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Client-visible status lamp for powered/networked devices. It is presentation only: the server
 * derives it from authoritative device state at most once per {@link #PERIOD} ticks per device, and
 * no gameplay decision ever reads it back. Each value has a distinct glyph (none, lit symbol, two
 * bars, exclamation mark) so meaning never depends on color alone.
 */
public enum DeviceLamp implements StringRepresentable {
  /** Idle, unpaired or not yet connected; no overlay. */
  OFF("off"),
  /** Online and doing its job. */
  ACTIVE("active"),
  /** Deliberately paused by a player (Program Controller). */
  PAUSED("paused"),
  /** Needs attention: invalid network, missing power, trapped program or broken link. */
  FAULT("fault");

  public static final EnumProperty<DeviceLamp> PROPERTY =
      EnumProperty.create("lamp", DeviceLamp.class);

  /** Ticks between server lamp refreshes; a bounded one-lookup-per-second cost per device. */
  public static final int PERIOD = 20;

  private final String name;

  DeviceLamp(String name) {
    this.name = name;
  }

  @Override
  public String getSerializedName() {
    return name;
  }

  /** Lamp for a network node's routing status; transient rebuilding keeps the current lamp. */
  public static DeviceLamp of(NetworkTopology.Status status, DeviceLamp current) {
    return switch (status) {
      case VALID -> ACTIVE;
      case REBUILDING -> current;
      case UNLOADED, UNKNOWN -> OFF;
      default -> FAULT;
    };
  }

  /** True on this device's staggered refresh tick. */
  public static boolean due(Level level, BlockPos position) {
    return Math.floorMod(level.getGameTime() + position.hashCode(), PERIOD) == 0;
  }

  /**
   * True when {@code next} replaces {@code previous} with only a different lamp. Such changes are
   * cosmetic and must not trigger topology rebuilds or drops.
   */
  public static boolean onlyLampChanged(BlockState previous, BlockState next) {
    return previous.getBlock() == next.getBlock()
        && previous.hasProperty(PROPERTY)
        && previous.setValue(PROPERTY, next.getValue(PROPERTY)) == next;
  }

  /**
   * Publishes {@code lamp} to clients without neighbor or shape updates. Server only; does nothing
   * if the block at {@code position} changed or already shows the lamp.
   */
  public static void show(Level level, BlockPos position, BlockState expected, DeviceLamp lamp) {
    if (level.isClientSide()) return;
    var state = level.getBlockState(position);
    if (state.getBlock() != expected.getBlock()
        || !state.hasProperty(PROPERTY)
        || state.getValue(PROPERTY) == lamp) return;
    level.setBlock(
        position, state.setValue(PROPERTY, lamp), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
  }
}
