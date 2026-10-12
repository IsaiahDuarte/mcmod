package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.DeviceRegistry;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Strict reference-only schema; holders retain rejected tags without reinterpretation. */
public final class EnergyBankHandleData {
  public static final String FIELD = "FactoryCoreEnergyBank";

  private EnergyBankHandleData() {}

  public static DeviceRegistry.Handle read(Tag raw) {
    if (raw == null) return null;
    if (!(raw instanceof CompoundTag data)
        || !Set.of("Schema", "World", "Backing", "Generation").equals(data.getAllKeys())
        || !data.contains("Schema", Tag.TAG_INT)
        || data.getInt("Schema") != 1
        || !data.hasUUID("World")
        || !data.hasUUID("Backing")
        || !data.contains("Generation", Tag.TAG_LONG))
      throw new IllegalArgumentException("Unsupported or malformed bank lease");
    return new DeviceRegistry.Handle(
        data.getUUID("World"), data.getUUID("Backing"), data.getLong("Generation"));
  }

  public static CompoundTag write(DeviceRegistry.Handle handle) {
    var data = new CompoundTag();
    data.putInt("Schema", 1);
    data.putUUID("World", handle.world());
    data.putUUID("Backing", handle.backing());
    data.putLong("Generation", handle.generation());
    return data;
  }
}
