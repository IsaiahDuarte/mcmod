package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProgramEvaluatorTest {
  private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), OUT = new UUID(0, 3);

  private static ProgramIr.Rule rule(String source) {
    var result = FriendlyCompiler.compile(source);
    assertTrue(result.ok(), result.diagnostics()::toString);
    return result.program().rules().getFirst();
  }

  private static ProgramEvaluator.Host host(Map<String, List<UUID>> labels, long stock) {
    return new ProgramEvaluator.Host() {
      @Override
      public List<UUID> machines(String label) {
        return labels.getOrDefault(label, List.of());
      }

      @Override
      public long stock(ResourceFilter filter) {
        return stock;
      }
    };
  }

  @Test
  void loopsConditionsAndPlacesStageExactCommandsInOrder() {
    var rule =
        rule(
            """
            every 10 ticks {
              for m in machines("in") { keep m stocked with 64 of item("raw_iron") }
              move item("iron_ingot") from machines("out") to storage limit 32
              move item("coal") from storage to machines("in")
              if stock(item("log")) < 8 { craft 4 of item("oak_planks") }
              if stock(item("log")) >= 8 { log "enough" }
            }
            """);
    var commands =
        ProgramEvaluator.invoke(rule, host(Map.of("in", List.of(A, B), "out", List.of(OUT)), 3));
    assertEquals(6, commands.size(), "Two keeps, three moves and one craft");
    assertEquals(A, ((ProgramCommand.Keep) commands.get(0)).machine());
    assertEquals(B, ((ProgramCommand.Keep) commands.get(1)).machine());
    var export = (ProgramCommand.Move) commands.get(2);
    assertEquals(OUT, export.from());
    assertNull(export.to());
    assertEquals(32, export.limit());
    assertEquals(A, ((ProgramCommand.Move) commands.get(3)).to());
    assertEquals(B, ((ProgramCommand.Move) commands.get(4)).to());
    assertEquals(new ProgramCommand.Craft("minecraft:oak_planks", 4), commands.get(5));
    assertFalse(commands.contains(new ProgramCommand.Log("enough")));
    assertTrue(
        ProgramEvaluator.invoke(rule, host(Map.of(), 3)).stream()
            .noneMatch(c -> c instanceof ProgramCommand.Keep),
        "An empty label is a visible no-op");
  }

  @Test
  void boundsAndHostFailuresTrapAndPublishNothing() {
    var many = new ArrayList<UUID>();
    for (int i = 0; i < ProgramEvaluator.MAX_MACHINES_PER_LABEL + 1; i++) many.add(new UUID(1, i));
    var keep = rule("every 10 ticks { keep machines(\"m\") stocked with 1 of item(\"stone\") }");
    assertThrows(
        ProgramEvaluator.Trap.class,
        () -> ProgramEvaluator.invoke(keep, host(Map.of("m", many), 0)));
    var sixtyFour = many.subList(0, 64);
    var nested =
        rule(
            "every 10 ticks { for a in machines(\"m\") { for b in machines(\"m\") { keep b stocked with 1 of item(\"stone\") } } }");
    var trap =
        assertThrows(
            ProgramEvaluator.Trap.class,
            () -> ProgramEvaluator.invoke(nested, host(Map.of("m", sixtyFour), 0)));
    assertTrue(
        trap.getMessage().contains("commands") || trap.getMessage().contains("steps"),
        trap.getMessage());
    var failing =
        new ProgramEvaluator.Host() {
          @Override
          public List<UUID> machines(String label) {
            throw new IllegalStateException("offline");
          }

          @Override
          public long stock(ResourceFilter filter) {
            throw new IllegalStateException("offline");
          }
        };
    assertThrows(ProgramEvaluator.Trap.class, () -> ProgramEvaluator.invoke(keep, failing));
    var condition = rule("every 10 ticks { if stock(item(\"stone\")) == 0 { log \"x\" } }");
    assertThrows(ProgramEvaluator.Trap.class, () -> ProgramEvaluator.invoke(condition, failing));
  }
}
