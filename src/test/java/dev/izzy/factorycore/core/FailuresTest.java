package dev.izzy.factorycore.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FailuresTest {
  @Test
  void messageFallsBackToTheClassName() {
    assertEquals("bad range", Failures.message(new IllegalArgumentException("bad range")));
    assertEquals("NullPointerException", Failures.message(new NullPointerException()));
  }
}
