package dev.izzy.factorycore.core.resource;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Where the known staging of a legacy record (saved without endpoint context) may settle (ADR
 * 0047). A source backing that is a canonical returnable holder wins, because returning owned
 * staging needs no grant; otherwise a canonical deliverable destination may receive it. Foreign or
 * unknown backings are never guessed, so a record with neither yields {@code null}.
 *
 * @param returning true to return staging to its source, false to deliver it to its destination
 * @param backing the canonical backing that receives the staging
 */
public record LegacySettlement(boolean returning, UUID backing) {
  public LegacySettlement {
    Objects.requireNonNull(backing);
  }

  /**
   * Classifies by identity only; it does not check that the holder is loaded or permitted.
   *
   * @param returnable canonical holders staging may return to (cells, banks, Buffer-mode
   *     Interfaces)
   * @param deliverable canonical holders staging may be delivered to (cells and banks)
   */
  public static LegacySettlement of(
      StagedTransfer.Snapshot record, Predicate<UUID> returnable, Predicate<UUID> deliverable) {
    Objects.requireNonNull(record);
    if (returnable.test(record.source())) return new LegacySettlement(true, record.source());
    return deliverable.test(record.destination())
        ? new LegacySettlement(false, record.destination())
        : null;
  }
}
