package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RedstoneGateTest {
  @Test
  void ignoreAlwaysRunsAndPoweredFollowsTheInput() {
    var gate = new RedstoneGate();
    assertTrue(gate.admit() && gate.chain());
    gate.control(RedstoneGate.Control.POWERED);
    assertFalse(gate.admit());
    gate.observe(true);
    assertTrue(gate.admit() && gate.admit() && gate.chain());
    gate.control(RedstoneGate.Control.UNPOWERED);
    assertFalse(gate.admit());
    gate.observe(false);
    assertTrue(gate.admit() && gate.chain());
  }

  @Test
  void aPulseRunsOnceAndHoldingItHighDoesNotRepeat() {
    var gate = new RedstoneGate();
    gate.control(RedstoneGate.Control.PULSE);
    assertFalse(gate.admit());
    gate.observe(true);
    assertTrue(gate.admit());
    assertFalse(gate.chain());
    gate.observe(true);
    assertFalse(gate.admit(), "Held high: no second run");
    gate.observe(false);
    assertFalse(gate.admit(), "Falling edge is not a pulse");
    gate.observe(true);
    gate.observe(false);
    gate.observe(true);
    assertTrue(gate.admit());
    assertFalse(gate.admit(), "Pulses while busy collapse to one");
  }

  @Test
  void changingTheControlDropsAPendingPulseAndRestoreKeepsOne() {
    var gate = new RedstoneGate();
    gate.control(RedstoneGate.Control.PULSE);
    gate.observe(true);
    gate.control(RedstoneGate.Control.POWERED);
    gate.control(RedstoneGate.Control.PULSE);
    assertFalse(gate.admit());
    var restored = new RedstoneGate();
    restored.restore(RedstoneGate.Control.PULSE, true, true);
    assertTrue(restored.powered() && restored.admit());
    restored.restore(RedstoneGate.Control.POWERED, false, true);
    assertFalse(restored.pulsePending());
  }
}
