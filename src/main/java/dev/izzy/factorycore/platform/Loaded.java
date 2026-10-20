package dev.izzy.factorycore.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * World lookups that never load a chunk. Factory Core must not load chunks for a lookup, so every
 * read of a remote position goes through these and treats {@code null} as "not loaded".
 */
public final class Loaded {
  private Loaded() {}

  /** The level key for a stored dimension id such as {@code minecraft:overworld}. */
  public static ResourceKey<Level> dimension(String id) {
    return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
  }

  /** The chunk holding {@code position}, or null when it is not loaded. */
  public static LevelChunk chunk(Level level, BlockPos position) {
    return level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
  }

  /** The block entity at {@code position}, or null when absent or its chunk is not loaded. */
  public static BlockEntity blockEntity(Level level, BlockPos position) {
    var chunk = chunk(level, position);
    return chunk == null
        ? null
        : chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
  }
}
