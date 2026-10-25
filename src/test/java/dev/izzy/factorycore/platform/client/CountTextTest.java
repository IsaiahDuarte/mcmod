package dev.izzy.factorycore.platform.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CountTextTest {
  @Test
  void boundariesStayShortAndNeverRoundUp() {
    assertEquals("0", CountText.compact("0"));
    assertEquals("999", CountText.compact("999"));
    assertEquals("1.0k", CountText.compact("1000"));
    assertEquals("9.9k", CountText.compact("9999"));
    assertEquals("10k", CountText.compact("10000"));
    assertEquals("989k", CountText.compact("989000"));
    assertEquals("999k", CountText.compact("999999"));
    assertEquals("1.0M", CountText.compact("1000000"));
    assertEquals("9.2E", CountText.compact(Long.toString(Long.MAX_VALUE)));
  }

  @Test
  void hugeValuesKeepTheLargestSuffix() {
    var text = CountText.compact("1" + "0".repeat(30));
    assertTrue(text.endsWith("Y"), text);
  }
}
