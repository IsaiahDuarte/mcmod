package dev.izzy.factorycore.platform;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Shared block properties, so every machine breaks, sounds and needs a pickaxe the same way. */
public final class DeviceProperties {
  private DeviceProperties() {}

  /** Metal machine blocks: iron-like hardness, need the correct tool to drop. */
  public static BlockBehaviour.Properties machine() {
    return BlockBehaviour.Properties.of()
        .strength(3.5f)
        .requiresCorrectToolForDrops()
        .sound(SoundType.METAL);
  }
}
