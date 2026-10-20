package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.core.network.NodeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** The one mapping from a level and block position to a portable network position. */
public final class Positions {
  private Positions() {}

  public static NodeRegistry.Position of(Level level, BlockPos position) {
    return new NodeRegistry.Position(level.dimension().location().toString(), position.asLong());
  }
}
