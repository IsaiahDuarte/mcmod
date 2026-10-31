package dev.izzy.factorycore.platform.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * Q72: what a cell held when it last left a drive, for its tooltip (a client cannot read the
 * ledger). {@code stored} and {@code types} are the ledger's totals and {@code capacity} its size
 * (−1 for infinite); {@code entries} are the largest of its first catalog page, at most {@link
 * #SHOWN}. It is a snapshot: a cell's contents change only while it sits in a drive, and leaving
 * one writes a new summary.
 */
public record CellSummary(long stored, long capacity, int types, List<Entry> entries)
    implements TooltipComponent {
  /** Stored resources a tooltip shows. */
  public static final int SHOWN = 8;

  /** One stored resource: its registry ID, kind and amount (items, or mB). */
  public record Entry(String id, boolean fluid, long amount) {
    public Entry {
      if (id.length() > 256 || amount < 0) throw new IllegalArgumentException("Cell entry");
    }

    static final Codec<Entry> CODEC =
        RecordCodecBuilder.create(
            entry ->
                entry
                    .group(
                        Codec.string(0, 256).fieldOf("id").forGetter(Entry::id),
                        Codec.BOOL.fieldOf("fluid").forGetter(Entry::fluid),
                        Codec.LONG.fieldOf("amount").forGetter(Entry::amount))
                    .apply(entry, Entry::new));

    static final StreamCodec<ByteBuf, Entry> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256),
            Entry::id,
            ByteBufCodecs.BOOL,
            Entry::fluid,
            ByteBufCodecs.VAR_LONG,
            Entry::amount,
            Entry::new);
  }

  public CellSummary {
    if (stored < 0 || capacity < -1 || types < 0 || entries.size() > SHOWN)
      throw new IllegalArgumentException("Cell summary");
    entries = List.copyOf(entries);
  }

  public boolean infinite() {
    return capacity < 0;
  }

  /** Percent full, 0–100 (0 for an infinite cell). */
  public int percent() {
    if (infinite() || capacity == 0) return 0;
    return (int) Math.min(100, stored * 100 / capacity);
  }

  public static final Codec<CellSummary> CODEC =
      RecordCodecBuilder.create(
          summary ->
              summary
                  .group(
                      Codec.LONG.fieldOf("stored").forGetter(CellSummary::stored),
                      Codec.LONG.fieldOf("capacity").forGetter(CellSummary::capacity),
                      Codec.INT.fieldOf("types").forGetter(CellSummary::types),
                      Entry.CODEC
                          .sizeLimitedListOf(SHOWN)
                          .fieldOf("entries")
                          .forGetter(CellSummary::entries))
                  .apply(summary, CellSummary::new));

  public static final StreamCodec<ByteBuf, CellSummary> STREAM_CODEC =
      StreamCodec.composite(
          ByteBufCodecs.VAR_LONG,
          CellSummary::stored,
          ByteBufCodecs.VAR_LONG,
          CellSummary::capacity,
          ByteBufCodecs.VAR_INT,
          CellSummary::types,
          Entry.STREAM_CODEC.apply(ByteBufCodecs.list(SHOWN)),
          CellSummary::entries,
          CellSummary::new);

  /** The summary of the ledger behind {@code handle}, or null when it cannot be read. */
  static CellSummary of(DeviceRegistry registry, DeviceRegistry.Handle handle) {
    var owner = registry.ownership(handle.backing());
    if (owner == null || registry.validate(handle) != DeviceRegistry.Failure.OK) return null;
    var definition = owner.definition();
    var page = registry.catalog(handle, null, ResourceLedger.CATALOG_PAGE_SIZE);
    var rows = new ArrayList<>(page.entries());
    rows.removeIf(row -> row.stock().total() <= 0);
    rows.sort(
        Comparator.comparingLong((ResourceLedger.CatalogEntry row) -> row.stock().total())
            .reversed());
    var entries = new ArrayList<Entry>(SHOWN);
    for (var row : rows.subList(0, Math.min(SHOWN, rows.size())))
      entries.add(
          new Entry(
              row.key().registryId(), row.key().kind() == ResourceKind.FLUID, row.stock().total()));
    return new CellSummary(
        owner.total(), definition.infinite() ? -1 : definition.capacity(), owner.types(), entries);
  }
}
