package dev.izzy.factorycore.platform.client;

/**
 * Parses the Jobs amount box (A1, Q64). An amount is a whole number from 1 to {@code max}, or an
 * expression of whole numbers with {@code + - * /} and parentheses whose value is one (division
 * must come out exact). {@code =N} asks for whatever is needed to reach {@code N} stored ({@link
 * #target()}); then {@code N} may be up to {@link #MAX_TARGET}. Anything else names why it was
 * refused, so the screen can say so instead of silently capping.
 */
record CraftAmount(int amount, String error, boolean target) {
  /** Longest expression read; the box allows no more. */
  static final int MAX_LENGTH = 32;

  /** Largest {@code =N} target. */
  static final int MAX_TARGET = 1_000_000_000;

  /** Intermediate values beyond this are "too many" (and cannot overflow a long). */
  private static final long LIMIT = 1L << 40;

  CraftAmount(int amount, String error) {
    this(amount, error, false);
  }

  static CraftAmount parse(String text, int max) {
    String value = text.strip().replace(",", "").replace("_", "");
    boolean target = value.startsWith("=");
    if (target) value = value.substring(1).strip();
    if (value.isEmpty()) return new CraftAmount(0, "empty", target);
    if (value.length() > MAX_LENGTH) return new CraftAmount(0, "invalid", target);
    long result;
    try {
      var parser = new Parser(value);
      result = parser.sum();
      if (parser.at != value.length()) return new CraftAmount(0, "invalid", target);
    } catch (Refused refused) {
      return new CraftAmount(0, refused.getMessage(), target);
    } catch (ArithmeticException overflow) {
      return new CraftAmount(0, "too_many", target);
    }
    if (result < 1) return new CraftAmount(0, "zero", target);
    if (result > (target ? MAX_TARGET : max)) return new CraftAmount(0, "too_many", target);
    return new CraftAmount((int) result, "", target);
  }

  boolean valid() {
    return error.isEmpty();
  }

  /** A refusal reason while parsing. */
  private static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Refused(String reason) {
      super(reason, null, false, false);
    }
  }

  /** sum := product (('+' | '-') product)*; product := atom (('*' | '/') atom)*. */
  private static final class Parser {
    final String text;
    int at, depth;

    Parser(String text) {
      this.text = text;
    }

    char peek() {
      while (at < text.length() && text.charAt(at) == ' ') at++;
      return at < text.length() ? text.charAt(at) : 0;
    }

    long sum() {
      long value = product();
      for (char c = peek(); c == '+' || c == '-'; c = peek()) {
        at++;
        long right = product();
        value = bounded(c == '+' ? value + right : value - right);
      }
      return value;
    }

    long product() {
      long value = atom();
      for (char c = peek(); c == '*' || c == '/'; c = peek()) {
        at++;
        long right = atom();
        if (c == '*') value = bounded(Math.multiplyExact(value, right));
        else {
          if (right == 0) throw new Refused("invalid");
          if (value % right != 0) throw new Refused("fraction");
          value /= right;
        }
      }
      return value;
    }

    long atom() {
      char c = peek();
      if (c == '(') {
        if (++depth > 8) throw new Refused("invalid");
        at++;
        long value = sum();
        if (peek() != ')') throw new Refused("invalid");
        at++;
        depth--;
        return value;
      }
      int start = at;
      while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
      if (at == start) throw new Refused("invalid");
      // More digits than any allowed value has is too many, and cannot overflow.
      if (at - start > 13) throw new Refused("too_many");
      return bounded(Long.parseLong(text.substring(start, at)));
    }

    static long bounded(long value) {
      if (Math.abs(value) > LIMIT) throw new Refused("too_many");
      return value;
    }
  }
}
