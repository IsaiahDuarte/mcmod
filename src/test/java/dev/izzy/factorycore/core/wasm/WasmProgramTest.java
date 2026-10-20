package dev.izzy.factorycore.core.wasm;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramCommand;
import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WasmProgramTest {
  private static final UUID IN_A = new UUID(0, 1), IN_B = new UUID(0, 2), OUT = new UUID(0, 3);

  private static byte[] smelter() throws Exception {
    return Files.readAllBytes(Path.of(System.getProperty("factorycore.test.smelter")));
  }

  private static ProgramEvaluator.Host host(long planks) {
    var labels = Map.of("smelter_in", List.of(IN_A, IN_B), "smelter_out", List.of(OUT));
    return new ProgramEvaluator.Host() {
      @Override
      public List<UUID> machines(String label) {
        return labels.getOrDefault(label, List.of());
      }

      @Override
      public long stock(ResourceFilter filter) {
        return planks;
      }
    };
  }

  /** The real Rust SDK example and its friendly-language twin stage identical commands. */
  @Test
  void rustSdkGuestMatchesFriendlyLanguageCommandForCommand() throws Exception {
    var guest = new WasmProgram(smelter(), System::nanoTime);
    assertEquals(
        List.of(new WasmProgram.RuleSpec("smelt", 10), new WasmProgram.RuleSpec("planks", 100)),
        guest.rules());
    var friendly =
        FriendlyCompiler.compile(
            """
            every 10 ticks as "smelt" {
                for machine in machines("smelter_in") {
                    keep machine stocked with 64 of item("minecraft:raw_iron") from storage
                }
                move item("minecraft:iron_ingot") from machines("smelter_out") to storage limit 64
            }
            every 5 seconds as "planks" {
                if stock(item("minecraft:oak_planks")) < 32 {
                    craft 16 of item("minecraft:oak_planks")
                    log "Low on planks"
                }
            }
            """);
    assertTrue(friendly.ok(), friendly.diagnostics()::toString);
    for (long planks : new long[] {0, 31, 32, 500})
      for (int rule = 0; rule < 2; rule++) {
        var expected = ProgramEvaluator.invoke(friendly.program().rules().get(rule), host(planks));
        var actual = guest.invoke(rule, host(planks));
        assertEquals(expected, actual.commands(), "rule " + rule + " planks " + planks);
        assertTrue(
            actual.instructions() > 0 && actual.instructions() <= WasmProgram.RULE_INSTRUCTIONS);
      }
    assertEquals(3, guest.invoke(0, host(0)).commands().size());
  }

  @Test
  void preflightRejectsForeignImportsAndOversizedArtifacts() {
    var foreign =
        assertThrows(
            IllegalArgumentException.class,
            () -> WasmPreflight.validate(module("probe_record", new byte[] {0x0b})));
    assertTrue(foreign.getMessage().contains("import"), foreign.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> WasmPreflight.validate(new byte[WasmPreflight.MAX_ARTIFACT + 1]));
    assertThrows(
        IllegalArgumentException.class, () -> WasmPreflight.validate(new byte[] {1, 2, 3}));
  }

  // ---- Minimal hand-assembled modules for bound checks ----

  private static void leb(ByteArrayOutputStream out, long value) {
    do {
      int b = (int) (value & 0x7f);
      value >>>= 7;
      out.write(value != 0 ? b | 0x80 : b);
    } while (value != 0);
  }

  private static void section(ByteArrayOutputStream module, int id, byte[] body) {
    module.write(id);
    leb(module, body.length);
    module.writeBytes(body);
  }

  private static void name(ByteArrayOutputStream out, String text) {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    leb(out, bytes.length);
    out.writeBytes(bytes);
  }

  /**
   * Imports factory_v1.register_rule; factory_init registers rule "a" every 10 ticks; factory_rule
   * runs {@code ruleBody} (raw instruction bytes ending with 0x0b).
   */
  private static byte[] module(String importName, byte[] ruleBody) {
    return module(importName, ruleBody, new byte[] {1, 1, 1, 1});
  }

  /** As above with an explicit memory section body (default: one memory, 1 page, max 1). */
  private static byte[] module(String importName, byte[] ruleBody, byte[] memory) {
    var module = new ByteArrayOutputStream();
    module.writeBytes(new byte[] {0, 97, 115, 109, 1, 0, 0, 0});
    var types = new ByteArrayOutputStream();
    leb(types, 3);
    types.writeBytes(new byte[] {0x60, 3, 0x7f, 0x7f, 0x7f, 1, 0x7f}); // (i32 i32 i32) -> i32
    types.writeBytes(new byte[] {0x60, 0, 0}); // () -> ()
    types.writeBytes(new byte[] {0x60, 1, 0x7f, 0}); // (i32) -> ()
    section(module, 1, types.toByteArray());
    var imports = new ByteArrayOutputStream();
    leb(imports, 1);
    name(imports, "factory_v1");
    name(imports, importName);
    imports.writeBytes(new byte[] {0, 0});
    section(module, 2, imports.toByteArray());
    section(module, 3, new byte[] {2, 1, 2});
    section(module, 5, memory);
    var exports = new ByteArrayOutputStream();
    leb(exports, 3);
    name(exports, "memory");
    exports.writeBytes(new byte[] {2, 0});
    name(exports, "factory_init");
    exports.writeBytes(new byte[] {0, 1});
    name(exports, "factory_rule");
    exports.writeBytes(new byte[] {0, 2});
    section(module, 7, exports.toByteArray());
    var code = new ByteArrayOutputStream();
    leb(code, 2);
    // init: register_rule(0, 1, 10); drop
    byte[] init = {0, 0x41, 0, 0x41, 1, 0x41, 10, 0x10, 0, 0x1a, 0x0b};
    leb(code, init.length);
    code.writeBytes(init);
    var rule = new ByteArrayOutputStream();
    rule.write(0);
    rule.writeBytes(ruleBody);
    leb(code, rule.size());
    code.writeBytes(rule.toByteArray());
    section(module, 10, code.toByteArray());
    // data: "a" at address 0
    section(module, 11, new byte[] {1, 0, 0x41, 0, 0x0b, 1, 'a'});
    return module.toByteArray();
  }

  @Test
  void runawayGuestTrapsAtTheInstructionBudgetAndTheInstanceStaysFailed() {
    var loop = module("register_rule", new byte[] {0x03, 0x40, 0x0c, 0, 0x0b, 0x0b});
    var guest = new WasmProgram(loop, System::nanoTime);
    assertEquals(List.of(new WasmProgram.RuleSpec("a", 10)), guest.rules());
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(0, host(0)));
    assertTrue(trap.getMessage().contains("instruction"), trap.getMessage());
    assertTrue(guest.failed());
    assertThrows(IllegalStateException.class, () -> guest.invoke(0, host(0)));
  }

  @Test
  void hostCallOutsideItsPhaseTrapsAndUnknownImportsAreRefused() {
    // factory_rule calls register_rule, which is only allowed during factory_init.
    var late =
        module("register_rule", new byte[] {0x41, 0, 0x41, 1, 0x41, 10, 0x10, 0, 0x1a, 0x0b});
    var guest = new WasmProgram(late, System::nanoTime);
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(0, host(0)));
    assertTrue(trap.getMessage().contains("outside factory_init"), trap.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> new WasmProgram(module("open_file", new byte[] {0x0b}), System::nanoTime),
        "Only factory_v1 functions may be imported");
  }

  private static byte[] counter() throws Exception {
    return Files.readAllBytes(Path.of(System.getProperty("factorycore.test.counter")));
  }

  private static String logged(WasmProgram.Outcome outcome) {
    return ((ProgramCommand.Log) outcome.commands().getFirst()).text();
  }

  @Test
  void persistentStateCommitsPerCallbackAndRestoresIntoAFreshInstance() throws Exception {
    var guest = new WasmProgram(counter(), System::nanoTime);
    assertNull(guest.state(), "A new deployment has no state");
    var first = guest.invoke(0, host(0));
    assertTrue(first.stateChanged());
    assertEquals("1", logged(first));
    assertEquals("2", logged(guest.invoke(0, host(0))));
    var saved = guest.state();
    assertEquals(8, saved.length);
    var restored = new WasmProgram(counter(), System::nanoTime, saved);
    assertEquals("3", logged(restored.invoke(0, host(0))), "Reload continues the count");
    assertEquals(
        2,
        ByteBuffer.wrap(saved).order(ByteOrder.LITTLE_ENDIAN).getLong(),
        "state() returned a copy that later commits do not change");
  }

  @Test
  void overQuotaWriteTrapsAndLeavesCommittedStateUnchanged() throws Exception {
    var guest = new WasmProgram(counter(), System::nanoTime);
    guest.invoke(0, host(0));
    var before = guest.state();
    assertFalse(guest.invoke(1, host(0)).stateChanged(), "No barrier: nothing is written");
    // The host reports barrier stock, so the guest attempts an over-quota write.
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(1, host(1)));
    assertTrue(trap.getMessage().contains("state over 4096 bytes"), trap.getMessage());
    assertArrayEquals(before, guest.state(), "The trapping callback's write is discarded");
    var resumed = new WasmProgram(counter(), System::nanoTime, guest.state());
    assertEquals("2", logged(resumed.invoke(0, host(0))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WasmProgram(counter(), System::nanoTime, new byte[WasmProgram.MAX_STATE + 1]));
  }

  // ---- Bounds formerly proven only against the P1 probe runtime ----

  @Test
  void recursiveGuestHitsTheFrameLimitBeforeTheJavaStack() {
    // factory_rule is function 2 (one i32 argument); it calls itself with that argument.
    var guest =
        new WasmProgram(module("register_rule", new byte[] {0x20, 0, 0x10, 2, 0x0b}), () -> 0);
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(0, host(0)));
    assertTrue(trap.getMessage().contains("call depth"), trap.getMessage());
    assertTrue(guest.failed());
  }

  @Test
  void operandStackIsBoundedAtRuntime() {
    // 4,097 pushes then return: valid Wasm under the 8,192-instruction preflight cap.
    var body = new ByteArrayOutputStream();
    for (int i = 0; i <= WasmProgram.MAX_OPERANDS; i++) body.writeBytes(new byte[] {0x41, 0});
    body.writeBytes(new byte[] {0x0f, 0x0b});
    var guest = new WasmProgram(module("register_rule", body.toByteArray()), () -> 0);
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(0, host(0)));
    assertTrue(trap.getMessage().contains("operand stack"), trap.getMessage());
  }

  @Test
  void memoryGrowthStopsAtTheDeclaredMaximumAndUnboundedMemoryIsRejected() {
    // memory.grow(1) with max 1 page returns -1; the instance stays healthy.
    var grow =
        new WasmProgram(
            module("register_rule", new byte[] {0x41, 1, 0x40, 0, 0x1a, 0x0b}), () -> 0);
    grow.invoke(0, host(0));
    assertEquals(1, grow.memoryPages());
    assertFalse(grow.failed());
    var unbounded =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WasmPreflight.validate(
                    module("register_rule", new byte[] {0x0b}, new byte[] {1, 0, 1})));
    assertTrue(unbounded.getMessage().contains("unbounded"), unbounded.getMessage());
  }

  @Test
  void declarationBombsAndUnsupportedFeaturesAreRejectedBeforeInstantiation() {
    byte[] header = {0, 97, 115, 109, 1, 0, 0, 0};
    // Control: a bare valid header passes preflight, so each rejection below is the section's.
    assertArrayEquals(header, WasmPreflight.validate(header));
    for (byte[] section :
        new byte[][] {
          {1, 5, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 15}, // Type-count bomb.
          {5, 3, 1, 0, 1}, // Unbounded memory.
          {5, 4, 1, 1, 1, 33}, // Memory maximum over the limit.
          {10, 7, 1, 5, 1, (byte) 255, 127, 127, 11}, // Huge locals.
          {1, 2, 1, 0x5f}, // GC type.
          {8, 1, 0}, // Implicit start.
          {2, 4, 1, 1, 'x', 0}, // Malformed import.
          {10, 6, 1, 4, 0, (byte) 0xfd, 0, 11}, // SIMD.
          {10, 7, 1, 5, 0, 14, (byte) 255, 127, 11}, // Branch-vector bomb.
          {4, 4, 1, 0x70, 0, (byte) 255} // Table bomb.
        }) {
      var module = new byte[header.length + section.length];
      System.arraycopy(header, 0, module, 0, header.length);
      System.arraycopy(section, 0, module, header.length, section.length);
      assertThrows(
          IllegalArgumentException.class,
          () -> WasmPreflight.validate(module),
          "Section " + java.util.Arrays.toString(section));
    }
  }

  @Test
  void runawayGuestHitsTheTimeDeadline() {
    var loop = module("register_rule", new byte[] {0x03, 0x40, 0x0c, 0, 0x0b, 0x0b});
    long[] now = {0};
    boolean[] armed = {false};
    // Once armed, every clock read jumps a second: the rule's first metered check is past the
    // deadline. Initialization runs with a still clock.
    var guest = new WasmProgram(loop, () -> armed[0] ? now[0] += 1_000_000_000L : 0);
    armed[0] = true;
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(0, host(0)));
    assertTrue(trap.getMessage().contains("time limit"), trap.getMessage());
    assertTrue(guest.failed());
  }

  @Test
  void hostCallFloodTrapsAtTheHostCallLimit() {
    var guest = new WasmProgram(hostFlood(), () -> 0);
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> guest.invoke(0, host(0)));
    assertTrue(
        trap.getMessage().contains("more than " + WasmProgram.MAX_HOST_CALLS + " host calls"),
        trap.getMessage());
    assertTrue(guest.failed());
  }

  /**
   * Imports register_rule (function 0) and state_read (function 1, stages no command); factory_init
   * (2) registers rule "a"; factory_rule (3) calls state_read in an endless loop.
   */
  private static byte[] hostFlood() {
    var module = new ByteArrayOutputStream();
    module.writeBytes(new byte[] {0, 97, 115, 109, 1, 0, 0, 0});
    var types = new ByteArrayOutputStream();
    leb(types, 4);
    types.writeBytes(new byte[] {0x60, 3, 0x7f, 0x7f, 0x7f, 1, 0x7f}); // register_rule
    types.writeBytes(new byte[] {0x60, 0, 0}); // factory_init
    types.writeBytes(new byte[] {0x60, 1, 0x7f, 0}); // factory_rule
    types.writeBytes(new byte[] {0x60, 2, 0x7f, 0x7f, 1, 0x7f}); // state_read
    section(module, 1, types.toByteArray());
    var imports = new ByteArrayOutputStream();
    leb(imports, 2);
    name(imports, "factory_v1");
    name(imports, "register_rule");
    imports.writeBytes(new byte[] {0, 0});
    name(imports, "factory_v1");
    name(imports, "state_read");
    imports.writeBytes(new byte[] {0, 3});
    section(module, 2, imports.toByteArray());
    section(module, 3, new byte[] {2, 1, 2});
    section(module, 5, new byte[] {1, 1, 1, 1});
    var exports = new ByteArrayOutputStream();
    leb(exports, 3);
    name(exports, "memory");
    exports.writeBytes(new byte[] {2, 0});
    name(exports, "factory_init");
    exports.writeBytes(new byte[] {0, 2});
    name(exports, "factory_rule");
    exports.writeBytes(new byte[] {0, 3});
    section(module, 7, exports.toByteArray());
    var code = new ByteArrayOutputStream();
    leb(code, 2);
    byte[] init = {0, 0x41, 0, 0x41, 1, 0x41, 10, 0x10, 0, 0x1a, 0x0b};
    leb(code, init.length);
    code.writeBytes(init);
    // loop { state_read(0, 1); drop; br 0 }
    byte[] rule = {0, 0x03, 0x40, 0x41, 0, 0x41, 1, 0x10, 1, 0x1a, 0x0c, 0, 0x0b, 0x0b};
    leb(code, rule.length);
    code.writeBytes(rule);
    section(module, 10, code.toByteArray());
    section(module, 11, new byte[] {1, 0, 0x41, 0, 0x0b, 1, 'a'});
    return module.toByteArray();
  }
}
