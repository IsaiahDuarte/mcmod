package dev.izzy.factorycore.platform.storage;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** The unified Interface's mode (ADR 0059); each is backed by an existing device entity. */
public enum InterfaceKind implements StringRepresentable {
  /** The container joins network storage ({@link ConnectorEntity}). */
  STORAGE,
  /** A labelled machine port with presets ({@link MachineInterfaceEntity}). */
  MACHINE,
  /** Its own nine-slot hopper buffer ({@link BufferedInterfaceEntity}). */
  BUFFER;

  @Override
  public String getSerializedName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
