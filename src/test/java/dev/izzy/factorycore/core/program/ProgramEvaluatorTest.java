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

  @Test
  void machineToMachineMovesPairEverySourceWithEveryOtherTargetWithinTheBound() {
    var rule =
        rule(
            "every 10 ticks { move item(\"iron_ingot\") from machines(\"out\") to machines(\"in\") limit 16 }");
    var commands =
        ProgramEvaluator.invoke(rule, host(Map.of("out", List.of(OUT, A), "in", List.of(A, B)), 0));
    var filter = ((ProgramCommand.Move) commands.getFirst()).filter();
    assertEquals(
        List.of(
            new ProgramCommand.Move(filter, OUT, A, 16),
            new ProgramCommand.Move(filter, OUT, B, 16),
            new ProgramCommand.Move(filter, A, B, 16)),
        commands,
        "Stable source-major order; a machine never moves to itself");
    var sources = new ArrayList<UUID>();
    var targets = new ArrayList<UUID>();
    for (int i = 0; i < 9; i++) {
      if (i < 8) sources.add(new UUID(2, i));
      targets.add(new UUID(3, i));
    }
    assertEquals(
        64,
        ProgramEvaluator.invoke(rule, host(Map.of("out", sources, "in", targets.subList(0, 8)), 0))
            .size(),
        "8 x 8 pairs is exactly the bound");
    assertThrows(
        ProgramEvaluator.Trap.class,
        () -> ProgramEvaluator.invoke(rule, host(Map.of("out", sources, "in", targets), 0)),
        "8 x 9 pairs exceed the 64-pair bound");
    assertThrows(
        IllegalArgumentException.class, () -> new ProgramCommand.Move(filter, null, null, 1));
  }

  /** Counts host reads; stock is 5 for every filter, each machine holds 3 matching. */
  private static final class Counting implements ProgramEvaluator.Host {
    int stockReads, contentReads;

    @Override
    public List<UUID> machines(String label) {
      return label.equals("two") ? List.of(A, B) : List.of(OUT);
    }

    @Override
    public long stock(ResourceFilter filter) {
      stockReads++;
      return 5;
    }

    @Override
    public ProgramEvaluator.Contents contents(UUID machine, ResourceFilter filter) {
      contentReads++;
      return new ProgramEvaluator.Contents(3, 1);
    }
  }

  private static List<ProgramCommand> logs(String condition, Counting host) {
    return ProgramEvaluator.invoke(
        rule("every 10 ticks { if " + condition + " { log \"yes\" } else { log \"no\" } }"), host);
  }

  @Test
  void eachOperatorShortCircuitsAndHasSumsEverySelectedMachine() {
    var yes = List.<ProgramCommand>of(new ProgramCommand.Log("yes"));
    var no = List.<ProgramCommand>of(new ProgramCommand.Log("no"));
    var host = new Counting();
    assertEquals(yes, logs("stock(item(\"a\")) == 5", host));
    assertEquals(no, logs("not stock(item(\"a\")) == 5", host));
    assertEquals(yes, logs("machines(\"two\") has == 6 of item(\"a\")", host), "3 + 3");
    assertEquals(2, host.contentReads, "One contents read per machine");

    host = new Counting();
    assertEquals(
        no, logs("stock(item(\"a\")) < 1 and machines(\"two\") has > 0 of item(\"a\")", host));
    assertEquals(1, host.stockReads);
    assertEquals(0, host.contentReads, "A false left side skips the right of and");

    host = new Counting();
    assertEquals(
        yes, logs("stock(item(\"a\")) > 1 or machines(\"two\") has > 0 of item(\"a\")", host));
    assertEquals(0, host.contentReads, "A true left side skips the right of or");

    host = new Counting();
    assertEquals(
        yes,
        logs(
            "not (stock(item(\"a\")) < 1 or stock(item(\"b\")) < 1) and stock(item(\"c\")) >= 5",
            host),
        "not binds tighter than and; parentheses group");
    assertEquals(3, host.stockReads);
  }

  @Test
  void elseIfChainsPickTheFirstTrueBranchAndHasNeedsAMachineHost() {
    var rule =
        rule(
            """
            every 10 ticks {
              for m in machines("one") {
                if m has < 1 of item("coal") { log "empty" }
                else if m has < 8 of item("coal") { log "low" }
                else { log "full" }
              }
            }
            """);
    assertEquals(
        List.of(new ProgramCommand.Log("low")), ProgramEvaluator.invoke(rule, new Counting()));
    assertThrows(
        ProgramEvaluator.Trap.class,
        () -> ProgramEvaluator.invoke(rule, host(Map.of("one", List.of(A)), 0)),
        "A host that cannot read machines traps the invocation");
  }
}
