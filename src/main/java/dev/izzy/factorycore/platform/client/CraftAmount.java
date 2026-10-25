package dev.izzy.factorycore.platform.client;

/**
 * Parses the Jobs amount box (A1). An amount is a whole number from 1 to {@code max}; anything else
 * names why it was refused, so the screen can say so instead of silently capping.
 */
record CraftAmount(int amount, String error) {
  static CraftAmount parse(String text, int max) {
    String value = text.strip().replace(",", "").replace("_", "");
    if (value.isEmpty()) return new CraftAmount(0, "empty");
    if (!value.chars().allMatch(Character::isDigit)) return new CraftAmount(0, "invalid");
    // Longer than the limit's digits is over the limit, and avoids overflowing int.
    if (value.length() > Integer.toString(max).length())
      return new CraftAmount(0, value.replaceFirst("^0+", "").isEmpty() ? "zero" : "too_many");
    int amount = Integer.parseInt(value);
    if (amount < 1) return new CraftAmount(0, "zero");
    if (amount > max) return new CraftAmount(0, "too_many");
    return new CraftAmount(amount, "");
  }

  boolean valid() {
    return error.isEmpty();
  }
}
