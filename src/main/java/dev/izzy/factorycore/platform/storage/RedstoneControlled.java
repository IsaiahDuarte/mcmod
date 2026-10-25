package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.RedstoneGate;
import net.minecraft.nbt.CompoundTag;

/**
 * C2: an Interface whose preset obeys a {@link RedstoneGate}. The block feeds input changes; the
 * owner wakes its preset loop when a held preset may run. The gate is saved in the entity under
 * {@link #FIELD} (Schema 1: Control name, Powered, Pulse); missing or malformed data is IGNORE.
 */
public interface RedstoneControlled {
  String FIELD = "FactoryCoreRedstone";

  RedstoneGate redstone();

  /** Wakes the preset loop after the input or the control changed. */
  void redstoneWake();

  static void save(RedstoneGate gate, CompoundTag tag) {
    if (gate.control() == RedstoneGate.Control.IGNORE && !gate.powered()) return;
    var data = new CompoundTag();
    data.putInt("Schema", 1);
    data.putString("Control", gate.control().name());
    data.putBoolean("Powered", gate.powered());
    data.putBoolean("Pulse", gate.pulsePending());
    tag.put(FIELD, data);
  }

  static void load(RedstoneGate gate, CompoundTag tag) {
    var control = RedstoneGate.Control.IGNORE;
    boolean powered = false, pulse = false;
    if (tag.contains(FIELD, CompoundTag.TAG_COMPOUND)) {
      var data = tag.getCompound(FIELD);
      try {
        if (data.getInt("Schema") == 1) {
          control = RedstoneGate.Control.valueOf(data.getString("Control"));
          powered = data.getBoolean("Powered");
          pulse = data.getBoolean("Pulse");
        }
      } catch (IllegalArgumentException malformed) {
        control = RedstoneGate.Control.IGNORE;
      }
    }
    gate.restore(control, powered, pulse);
  }
}
