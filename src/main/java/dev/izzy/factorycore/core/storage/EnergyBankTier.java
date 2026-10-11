package dev.izzy.factorycore.core.storage;

/** Balance version one; capacities are FE and each direction has its own FE/tick ceiling. */
public enum EnergyBankTier {
  STARTER(100000, 1000),
  EXPANDED(1000000, 4000),
  ADVANCED(10000000, 16000);

  private final long capacity;
  private final long rate;

  EnergyBankTier(long capacity, long rate) {
    this.capacity = capacity;
    this.rate = rate;
  }

  public long capacity() {
    return capacity;
  }

  public long rate() {
    return rate;
  }

  public int level() {
    return ordinal();
  }

  public EnergyBankTier upgrade(int targetLevel) {
    if (targetLevel != level() + 1 || targetLevel >= values().length)
      throw new IllegalArgumentException("Apply finite energy-bank upgrades sequentially");
    return values()[targetLevel];
  }
}
