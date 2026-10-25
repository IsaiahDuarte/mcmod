package dev.izzy.factorycore.platform.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * B3: a storage cell's partition, carried on the cell item so it moves with the cell and survives
 * upgrades (which copy the item's components). Like a Storage-mode insert filter it limits what the
 * network puts into the cell; what the cell holds stays listed and withdrawable. Stored in the
 * {@link FilterCodec} form; a malformed value fails to load rather than widening the cell.
 */
public record CellPartition(ResourceFilter filter) {
  public CellPartition {
    Objects.requireNonNull(filter);
    if (filter.mode() == ResourceFilter.Mode.ALL)
      throw new IllegalArgumentException("A partition must select something");
  }

  public static final Codec<CellPartition> CODEC =
      CompoundTag.CODEC.comapFlatMap(
          tag -> {
            try {
              return DataResult.success(new CellPartition(FilterCodec.decode(tag, "cell")));
            } catch (IllegalArgumentException invalid) {
              return DataResult.error(() -> "Invalid cell partition: " + invalid.getMessage());
            }
          },
          value -> FilterCodec.encode(value.filter()));

  public static final StreamCodec<ByteBuf, CellPartition> STREAM_CODEC =
      ByteBufCodecs.COMPOUND_TAG.map(
          tag -> new CellPartition(FilterCodec.decode(tag, "cell")),
          value -> FilterCodec.encode(value.filter()));
}
