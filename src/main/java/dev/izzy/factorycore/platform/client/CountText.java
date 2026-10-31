package dev.izzy.factorycore.platform.client;

import java.math.BigInteger;

/**
 * Short slot counts, at most four characters: exact below 1,000, then one decimal while the leading
 * part is under ten ({@code 1.0k}, {@code 9.9M}) and whole units after ({@code 989k}). Digits are
 * truncated, never rounded up, so a count is never overstated; the tooltip keeps the exact value.
 */
final class CountText {
  private static final String[] SUFFIX = {"", "k", "M", "G", "T", "P", "E", "Z", "Y"};
  private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

  private CountText() {}

  /**
   * Q99 (M125): a fluid amount in mB as whole buckets with a "B" suffix ({@code 1.5kB} for 1,500
   * buckets), truncated like {@link #compact}; under one bucket it reads {@code <1B}.
   */
  static String buckets(String millibuckets) {
    BigInteger amount = new BigInteger(millibuckets);
    if (amount.signum() <= 0) return amount + "B";
    if (amount.compareTo(THOUSAND) < 0) return "<1B";
    return compact(amount.divide(THOUSAND).toString()) + "B";
  }

  static String compact(String decimal) {
    BigInteger number = new BigInteger(decimal);
    if (number.signum() < 0) return number.toString();
    BigInteger tenths = BigInteger.ZERO;
    int step = 0;
    while (number.compareTo(THOUSAND) >= 0 && step < SUFFIX.length - 1) {
      tenths = number.mod(THOUSAND).divide(BigInteger.valueOf(100));
      number = number.divide(THOUSAND);
      step++;
    }
    if (step > 0 && number.compareTo(BigInteger.TEN) < 0)
      return number + "." + tenths + SUFFIX[step];
    return number + SUFFIX[step];
  }
}
