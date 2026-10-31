package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SignalRuleTest {
  private static SignalRule rule(
      SignalRule.Compare compare, long threshold, SignalRule.Output output) {
    return new SignalRule(SignalRule.Condition.STOCK, compare, threshold, output);
  }

  @Test
  void fullOutputSwitchesAtTheThreshold() {
    var atLeast = rule(SignalRule.Compare.AT_LEAST, 64, SignalRule.Output.FULL);
    assertEquals(0, atLeast.strength(63));
    assertEquals(15, atLeast.strength(64));
    assertEquals(15, atLeast.strength(Long.MAX_VALUE));
    var below = rule(SignalRule.Compare.BELOW, 64, SignalRule.Output.FULL);
    assertEquals(15, below.strength(0));
    assertEquals(15, below.strength(63));
    assertEquals(0, below.strength(64));
  }

  @Test
  void proportionalOutputRisesTowardTheThresholdLikeAComparator() {
    var atLeast = rule(SignalRule.Compare.AT_LEAST, 150, SignalRule.Output.PROPORTIONAL);
    assertEquals(0, atLeast.strength(0));
    assertEquals(1, atLeast.strength(1), "any stock lights at least 1");
    assertEquals(7, atLeast.strength(75));
    assertEquals(14, atLeast.strength(149));
    assertEquals(15, atLeast.strength(150));
    assertEquals(15, atLeast.strength(Long.MAX_VALUE), "no overflow on huge stock");
    var below = rule(SignalRule.Compare.BELOW, 150, SignalRule.Output.PROPORTIONAL);
    assertEquals(15, below.strength(0));
    assertEquals(0, below.strength(150));
    var huge = rule(SignalRule.Compare.AT_LEAST, Long.MAX_VALUE, SignalRule.Output.PROPORTIONAL);
    assertEquals(7, huge.strength(Long.MAX_VALUE / 2));
  }

  @Test
  void equalIsOnOnlyAtTheThresholdAndAlwaysFull() {
    var equal = rule(SignalRule.Compare.EQUAL, 64, SignalRule.Output.PROPORTIONAL);
    assertEquals(0, equal.strength(63));
    assertEquals(15, equal.strength(64));
    assertEquals(0, equal.strength(65));
    assertEquals(15, rule(SignalRule.Compare.EQUAL, 0, SignalRule.Output.FULL).strength(0));
    assertEquals(0, rule(SignalRule.Compare.EQUAL, 0, SignalRule.Output.FULL).strength(1));
    assertTrue(SignalRule.Condition.ENERGY.counts() && !SignalRule.Condition.JOB.counts());
  }

  @Test
  void edgeCases() {
    assertEquals(
        15, rule(SignalRule.Compare.AT_LEAST, 0, SignalRule.Output.PROPORTIONAL).strength(0));
    assertEquals(0, rule(SignalRule.Compare.BELOW, 0, SignalRule.Output.FULL).strength(0));
    assertEquals(15, SignalRule.DEFAULT.strength(true));
    assertEquals(0, SignalRule.DEFAULT.strength(false));
    assertThrows(
        IllegalArgumentException.class,
        () -> rule(SignalRule.Compare.BELOW, -1, SignalRule.Output.FULL));
    assertThrows(IllegalArgumentException.class, () -> SignalRule.DEFAULT.strength(-1));
  }
}
