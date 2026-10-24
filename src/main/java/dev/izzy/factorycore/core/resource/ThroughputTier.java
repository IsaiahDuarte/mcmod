package dev.izzy.factorycore.core.resource;

/**
 * Paid per-device throughput tier (ADR 0054). A tier raises only a device's own per-tick ceiling
 * and the largest single scheduled request; it never changes {@code FairScheduler} limits. Larger
 * requests are charged proportionally more scheduler credits, so an upgraded factory is throttled
 * by the same shared budget as everyone else.
 *
 * <p>Units: items, mB or FE per server tick, each direction independently.
 */
public enum ThroughputTier {
  BASE(128, 1_000, 1_000, 64, 1),
  ADVANCED(256, 4_000, 8_000, 256, 1),
  ENDGAME(1_024, 16_000, 32_000, 576, 4);

  /** Scheduler credits charged per begun base quantum; equals the historical fixed task cost. */
  public static final int CREDITS_PER_QUANTUM = 16;

  private final int items;
  private final int fluid;
  private final int energy;
  private final int buffer;
  private final int linkScale;

  ThroughputTier(int items, int fluid, int energy, int buffer, int linkScale) {
    this.items = items;
    this.fluid = fluid;
    this.energy = energy;
    this.buffer = buffer;
    this.linkScale = linkScale;
  }

  /** Storage- and Machine-mode Interface maximum configurable rate for this tier. */
  public int portRate(ResourceKind kind) {
    return switch (kind) {
      case ITEM -> items;
      case FLUID -> fluid;
      case ENERGY -> energy;
    };
  }

  /**
   * Buffer-mode Interface maximum items per tick per direction (576 is the whole nine-slot tray).
   */
  public int bufferRate() {
    return buffer;
  }

  /** Multiplier on the ADR 0050 per-pair wireless caps when both link endpoints carry this tier. */
  public int linkScale() {
    return linkScale;
  }

  /** The tier the given upgrade item produces from this one, or null when it does not apply. */
  public ThroughputTier upgradeWith(ThroughputTier item, boolean link) {
    if (link) return this == BASE && item == ENDGAME ? ENDGAME : null;
    return item.ordinal() == ordinal() + 1 ? item : null;
  }

  /** Pre-upgrade bounded host operation size: 128 items or 1,000 mB/FE. */
  public static long baseQuantum(ResourceKind kind) {
    return BASE.portRate(kind);
  }

  /** Absolute largest single scheduled request; also the largest configurable port rate. */
  public static long maxQuantum(ResourceKind kind) {
    return ENDGAME.portRate(kind);
  }

  /**
   * Scheduler cost of one request: {@link #CREDITS_PER_QUANTUM} per begun base quantum, so a
   * request at or below the base quantum costs exactly what it did before upgrades existed.
   */
  public static int credits(ResourceKind kind, long amount) {
    if (amount < 0 || amount > maxQuantum(kind))
      throw new IllegalArgumentException("Request exceeds the largest bounded quantum");
    long quantum = baseQuantum(kind);
    long quanta = Math.max(1, (amount + quantum - 1) / quantum);
    return Math.toIntExact(quanta * CREDITS_PER_QUANTUM);
  }

  /** Stable persisted name; unknown names are rejected so newer data is preserved for recovery. */
  public static ThroughputTier parse(String name) {
    for (var tier : values()) if (tier.name().equals(name)) return tier;
    throw new IllegalArgumentException("Unknown throughput tier " + name);
  }
}
