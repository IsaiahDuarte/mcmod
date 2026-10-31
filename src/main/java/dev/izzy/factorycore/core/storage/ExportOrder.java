package dev.izzy.factorycore.core.storage;

import java.util.random.RandomGenerator;

/**
 * Q74: which filter slot an Exporter with several "Only these" slots serves next. Each pass starts
 * at a slot and sends the stocked slot nearest after it (cyclically): {@link #IN_ORDER} always
 * starts at the first slot, {@link #ROUND_ROBIN} after the slot it last sent, {@link #RANDOM} at a
 * random one. A move that delivers nothing starts the next pass after its slot, so a slot the
 * machine refuses never blocks the others.
 */
public enum ExportOrder {
  IN_ORDER,
  ROUND_ROBIN,
  RANDOM;

  /** The slot the pass after a delivered move of {@code sent} starts from. */
  public int after(int sent, int slots, RandomGenerator random) {
    if (slots <= 0) return 0;
    return switch (this) {
      case IN_ORDER -> 0;
      case ROUND_ROBIN -> (sent + 1) % slots;
      case RANDOM -> random.nextInt(slots);
    };
  }

  /**
   * How far {@code slot} lies after {@code start}; a pass sends the stocked slot with the least.
   */
  public static int distance(int slot, int start, int slots) {
    return Math.floorMod(slot - start, slots);
  }

  public ExportOrder next() {
    var values = values();
    return values[(ordinal() + 1) % values.length];
  }
}
