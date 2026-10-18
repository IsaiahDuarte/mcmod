package dev.izzy.factorycore.core.wasm;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.io.ByteArrayOutputStream;
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
  void preflightRejectsProbeImportsAndOversizedArtifacts() throws Exception {
    var probe = Files.readAllBytes(Path.of(System.getProperty("factorycore.test.guest")));
    var rejected =
        assertThrows(IllegalArgumentException.class, () -> WasmPreflight.validate(probe));
    assertTrue(rejected.getMessage().contains("import"), rejected.getMessage());
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
    section(module, 5, new byte[] {1, 1, 1, 1});
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
}
