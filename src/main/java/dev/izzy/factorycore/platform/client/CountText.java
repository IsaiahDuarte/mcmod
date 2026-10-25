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
