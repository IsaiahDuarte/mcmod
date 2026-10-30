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

  @Test
  void expressionsWithPrecedenceAndParentheses() {
    assertEquals(14, CraftAmount.parse("2+3*4", 4096).amount());
    assertEquals(20, CraftAmount.parse("(2+3)*4", 4096).amount());
    assertEquals(576, CraftAmount.parse("64 * 9", 4096).amount());
    assertEquals(3, CraftAmount.parse("10-7", 4096).amount());
    assertEquals(32, CraftAmount.parse("64/2", 4096).amount());
    assertEquals(4, CraftAmount.parse("((((((((4))))))))", 4096).amount(), "8 levels");
    assertEquals("invalid", error("(((((((((4)))))))))"), "9 levels are refused");
    assertEquals("fraction", error("7/2"), "Division must come out whole");
    assertEquals("invalid", error("4/0"));
    assertEquals("zero", error("3-3"));
    assertEquals("zero", error("2-5"), "A negative result is not an amount");
    assertEquals("too_many", error("64*65"));
    assertEquals("invalid", error("(4"));
    assertEquals("invalid", error("4)"));
    assertEquals("invalid", error("4+"));
    assertEquals("invalid", error("*4"));
    assertEquals("invalid", error("1".repeat(20) + "+" + "1".repeat(20)), "Over 32 characters");
  }

  @Test
  void overflowIsTooManyNotAWrongNumber() {
    assertEquals("too_many", error("9999999999999*9999999999999"));
    assertEquals("too_many", error("1099511627776*1099511627776"));
    assertEquals("too_many", error("99999999999999"), "Fourteen digits");
    assertEquals("too_many", error("1000000*1000000"));
  }

  @Test
  void equalsMeansReachThisManyStored() {
    var target = CraftAmount.parse("=500", 4096);
    assertTrue(target.valid() && target.target());
    assertEquals(500, target.amount());
    assertEquals(10_000, CraftAmount.parse("= 100*100", 4096).amount(), "Targets may pass the cap");
    assertFalse(CraftAmount.parse("500", 4096).target());
    assertEquals("empty", error("="));
    assertEquals("zero", error("=0"));
    assertEquals("too_many", error("=2000000000"));
    assertTrue(CraftAmount.parse("=junk", 4096).target(), "Still marked as a target when refused");
  }
}
