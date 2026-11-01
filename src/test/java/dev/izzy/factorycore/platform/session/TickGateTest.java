package dev.izzy.factorycore.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TickGateTest {
  @Test
  void aBurstOfOneRefusesEventsCloserThanThePeriod() {
    var gate = new TickGate(5);
    assertTrue(gate.admit(100));
    assertFalse(gate.admit(104));
    assertTrue(gate.admit(105));
    assertFalse(gate.admit(105));
    gate.reset();
    assertTrue(gate.admit(105));
  }

  @Test
  void quickClicksAfterAQuietSpellAreAllAdmitted() {
    // Q127: the menu command gate. A search, a tool click and a double click in one tick pass.
    var gate = new TickGate(MenuSession.COMMAND_TICKS, MenuSession.COMMAND_BURST);
    for (int event = 0; event < MenuSession.COMMAND_BURST; event++)
      assertTrue(gate.admit(1_000), "event " + event);
    assertFalse(gate.admit(1_000), "the burst is bounded");
    // Credit returns at one per period, and never above the burst.
    assertFalse(gate.admit(1_001));
    assertTrue(gate.admit(1_002));
    assertTrue(gate.admit(1_100));
  }

  @Test
  void theSustainedRateStaysOnePerPeriod() {
    var gate = new TickGate(2, 4);
    int admitted = 0;
    for (int tick = 0; tick < 1_000; tick++)
      for (int spam = 0; spam < 10; spam++) if (gate.admit(tick)) admitted++;
    // 1,000 ticks at one per 2 ticks, plus the initial burst.
    assertEquals(500 + 3, admitted);
  }

  @Test
  void anEmptyAllowanceIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> new TickGate(2, 0));
    assertThrows(IllegalArgumentException.class, () -> new TickGate(0, 4));
  }
}
