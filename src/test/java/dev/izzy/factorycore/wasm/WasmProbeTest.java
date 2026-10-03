package dev.izzy.factorycore.wasm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WasmProbeTest {
  static byte[] guest() throws java.io.IOException {
    return Files.readAllBytes(Path.of(System.getProperty("factorycore.test.guest")));
  }

  @Test
  void realRustGuestPreservesExactI64AcrossHostAndRejectsNegativeAmounts() throws Exception {
    var runtime = new ProbeRuntime(guest(), () -> 0);
    for (long amount : new long[] {0, 1, 9_007_199_254_740_993L, Long.MAX_VALUE}) {
      var result = runtime.invoke("probe_echo", amount);
      assertArrayEquals(new long[] {amount}, result.values());
      assertEquals(List.of(amount), result.committed());
    }
    for (long amount : new long[] {-1, Long.MIN_VALUE}) {
      var result = runtime.invoke("probe_echo", amount);
      assertArrayEquals(new long[] {-1}, result.values());
      assertTrue(result.committed().isEmpty());
    }
  }

  @Test
  void runawayGuestAndHostFloodAreInterruptedAndFailedInstancesCannotResume() throws Exception {
    var infinite = new ProbeRuntime(guest(), () -> 0);
    assertEquals(
        "instructions",
        assertThrows(ProbeRuntime.LimitException.class, () -> infinite.invoke("probe_loop"))
            .getMessage());
    assertThrows(IllegalStateException.class, () -> infinite.invoke("probe_echo", 1));
    var flood = new ProbeRuntime(guest(), () -> 0);
    assertEquals(
        "host calls",
        assertThrows(ProbeRuntime.LimitException.class, () -> flood.invoke("probe_host_loop"))
            .getMessage());
    assertEquals(17, flood.hostCalls());
    assertEquals(0, flood.stagedCount());
    long[] time = {0};
    var deadline = new ProbeRuntime(guest(), () -> time[0] += 1_000_000_000L);
    assertEquals(
        "deadline",
        assertThrows(ProbeRuntime.LimitException.class, () -> deadline.invoke("probe_echo", 1))
            .getMessage());
  }

  @Test
  void memoryGrowthHonorsDeclaredMaximumAndTrapsDiscardStagedEffects() throws Exception {
    var runtime = new ProbeRuntime(guest(), () -> 0);
    int initial = runtime.memoryPages();
    assertArrayEquals(new long[] {initial}, runtime.invoke("probe_grow", 32 - initial).values());
    assertEquals(32, runtime.memoryPages());
    assertArrayEquals(new long[] {-1}, runtime.invoke("probe_grow", 1).values());
    assertEquals(32, runtime.memoryPages());
    assertThrows(RuntimeException.class, () -> runtime.invoke("probe_trap_after_record"));
    assertEquals(1, runtime.hostCalls());
    assertEquals(0, runtime.stagedCount());
    assertThrows(IllegalStateException.class, () -> runtime.invoke("probe_echo", 1));
  }

  @Test
  void declarationBombsMalformedImportsAndUnsupportedFeaturesAreRejectedBeforeParsing()
      throws Exception {
    for (byte[] section :
        new byte[][] {
          {1, 5, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 15},
          {5, 3, 1, 0, 1}, // Unbounded memory.
          {5, 4, 1, 1, 1, 33},
          {10, 7, 1, 5, 1, (byte) 255, 127, 127, 11}, // Huge locals.
          {1, 2, 1, 0x5f}, // GC type.
          {8, 1, 0}, // Implicit start.
          {2, 4, 1, 1, 'x', 0},
          {10, 6, 1, 4, 0, (byte) 0xfd, 0, 11}, // SIMD.
          {10, 7, 1, 5, 0, 14, (byte) 255, 127, 11}, // Branch vector bomb.
          {4, 4, 1, 0x70, 0, (byte) 255}
        }) {
      assertThrows(IllegalArgumentException.class, () -> ProbePreflight.validate(module(section)));
    }
    byte[] real = guest();
    for (int size : new int[] {0, 7, 16, real.length - 1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ProbeRuntime(Arrays.copyOf(real, size), () -> 0));
    }
    assertThrows(IllegalArgumentException.class, () -> ProbePreflight.validate(new byte[65537]));
    // Even malicious custom-section contents are stripped without decoding their vectors.
    assertArrayEquals(
        module(new byte[0]),
        ProbePreflight.validate(module(new byte[] {0, 5, -1, -1, -1, -1, 15})));
  }

  @Test
  void recursiveGuestHitsCallLimitBeforeJavaStackExhaustion() {
    byte[] recursive =
        module(
            new byte[] {
              1, 4, 1, 0x60, 0, 0, 3, 2, 1, 0, 7, 7, 1, 3, 'r', 'u', 'n', 0, 0, 10, 6, 1, 4, 0,
              0x10, 0, 11
            });
    var runtime = new ProbeRuntime(recursive, () -> 0);
    assertEquals(
        "call stack",
        assertThrows(ProbeRuntime.LimitException.class, () -> runtime.invoke("run")).getMessage());
  }

  @Test
  void validNestedCallsCannotAccumulateUnboundedOperandStack() {
    var code = new ByteArrayOutputStream();
    code.write(2);
    for (int function = 0; function < 2; function++) {
      var body = new ByteArrayOutputStream();
      body.write(0);
      int operands = function == 0 ? 2000 : 3000;
      for (int i = 0; i < operands; i++) {
        body.writeBytes(new byte[] {0x41, 0});
      }
      if (function == 0) {
        body.writeBytes(new byte[] {0x10, 1});
      }
      for (int i = 0; i < operands; i++) {
        body.write(0x1a);
      }
      body.write(11);
      unsigned(code, body.size());
      code.writeBytes(body.toByteArray());
    }
    var sections = new ByteArrayOutputStream();
    sections.writeBytes(
        new byte[] {1, 4, 1, 0x60, 0, 0, 3, 3, 2, 0, 0, 7, 7, 1, 3, 'r', 'u', 'n', 0, 0, 10});
    unsigned(sections, code.size());
    sections.writeBytes(code.toByteArray());
    var runtime = new ProbeRuntime(module(sections.toByteArray()), () -> 0);
    assertEquals(
        "operand stack",
        assertThrows(ProbeRuntime.LimitException.class, () -> runtime.invoke("run")).getMessage());
  }

  private static void unsigned(ByteArrayOutputStream output, int value) {
    do {
      int digit = value & 127;
      value >>>= 7;
      output.write(value == 0 ? digit : digit | 128);
    } while (value != 0);
  }

  private static byte[] module(byte[] section) {
    byte[] bytes = new byte[8 + section.length];
    System.arraycopy(new byte[] {0, 97, 115, 109, 1, 0, 0, 0}, 0, bytes, 0, 8);
    System.arraycopy(section, 0, bytes, 8, section.length);
    return bytes;
  }
}
