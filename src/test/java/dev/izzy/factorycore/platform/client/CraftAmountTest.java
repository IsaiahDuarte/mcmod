package dev.izzy.factorycore.platform.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CraftAmountTest {
  private static String error(String text) {
    return CraftAmount.parse(text, 4096).error();
  }

  @Test
  void typedAmountsWithinTheLimitParse() {
    assertEquals(37, CraftAmount.parse("37", 4096).amount());
    assertEquals(37, CraftAmount.parse(" 37 ", 4096).amount());
    assertEquals(1, CraftAmount.parse("1", 4096).amount());
    assertEquals(4096, CraftAmount.parse("4,096", 4096).amount());
    assertEquals(7, CraftAmount.parse("007", 4096).amount());
  }

  @Test
  void zeroOverLimitAndNonNumericInputAreRefusedWithAReason() {
    assertEquals("empty", error(""));
    assertEquals("empty", error("   "));
    assertEquals("zero", error("0"));
    assertEquals("zero", error("00000"));
    assertEquals("too_many", error("4097"));
    assertEquals("too_many", error("99999999999999999999"));
    assertEquals("invalid", error("-5"));
    assertEquals("invalid", error("3.5"));
    assertEquals("invalid", error("ten"));
    assertEquals("invalid", error("1e3"));
    assertFalse(CraftAmount.parse("0", 4096).valid());
  }
}
