package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Checks shared by every leased holder's operator repair and adoption. Each holder keeps its own
 * lease-specific conditions and refusal order; these are the parts that are identical.
 */
public final class LeaseRecovery {
  private LeaseRecovery() {}

  /**
   * True when {@code holder} is not removed and is the block entity actually loaded at its position
   * (its chunk loaded, nothing loaded in its place); only then may it repair or adopt.
   */
  public static boolean loadedHere(BlockEntity holder) {
    return holder.getLevel() instanceof ServerLevel server
        && !holder.isRemoved()
        && Loaded.chunk(server, holder.getBlockPos()) != null
        && server.getBlockEntity(holder.getBlockPos()) == holder;
  }

  /** The previous canonical holder's place; tell the network it changed once adoption commits. */
  public record OldHolder(ServerLevel level, BlockPos position) {
    public void changed() {
      WorldNetworks.changed(level, position);
    }
  }

  /**
   * Adoption may only take a lease over from a canonical holder whose chunk is loaded and which no
   * longer references the lease ({@code stillReferences} is false for whatever is loaded there), so
   * a lease is never held twice. Throws {@link IllegalStateException} otherwise.
   */
  public static OldHolder releasedOldHolder(
      MinecraftServer server,
      NodeRegistry.Position canonical,
      Predicate<BlockEntity> stillReferences) {
    return released(server, canonical.dimension(), BlockPos.of(canonical.block()), stillReferences);
  }

  public static OldHolder releasedOldHolder(
      MinecraftServer server,
      DeviceRegistry.Location canonical,
      Predicate<BlockEntity> stillReferences) {
    return released(
        server, canonical.dimension(), BlockPos.of(canonical.position()), stillReferences);
  }

  private static OldHolder released(
      MinecraftServer server,
      String dimension,
      BlockPos position,
      Predicate<BlockEntity> stillReferences) {
    var level = server.getLevel(Loaded.dimension(dimension));
    if (level == null || Loaded.chunk(level, position) == null)
      throw new IllegalStateException("Old canonical holder must be loaded");
    var entity = level.getBlockEntity(position);
    if (entity != null && stillReferences.test(entity))
      throw new IllegalStateException("Old canonical holder still references the lease");
    return new OldHolder(level, position);
  }
}
