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
            "every 0.5 seconds:\n    for each m in \"in\":\n        keep 64 raw iron in m\n    move 32 iron ingot from out to storage\n    move coal from storage to \"in\"\n    if storage has less than 8 log:\n        craft 4 oak planks\n    if storage has at least 8 log:\n        say \"enough\"\n");
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
    var keep = rule("every 0.5 seconds:\n    keep 1 stone in m\n");
    assertThrows(
        ProgramEvaluator.Trap.class,
        () -> ProgramEvaluator.invoke(keep, host(Map.of("m", many), 0)));
    var sixtyFour = many.subList(0, 64);
    var nested =
        rule(
            "every 0.5 seconds:\n    for each a in m:\n        for each b in m:\n            keep 1 stone in b\n");
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
    var condition =
        rule("every 0.5 seconds:\n    if storage has exactly 0 stone:\n        say \"x\"\n");
    assertThrows(ProgramEvaluator.Trap.class, () -> ProgramEvaluator.invoke(condition, failing));
  }

  @Test
  void machineToMachineMovesPairEverySourceWithEveryOtherTargetWithinTheBound() {
    var rule = rule("every 0.5 seconds:\n    move 16 iron ingot from out to \"in\"\n");
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
        rule(
            "every 10 ticks:\n    if "
                + condition
                + ":\n        say \"yes\"\n    otherwise:\n        say \"no\"\n"),
        host);
  }

  @Test
  void eachOperatorShortCircuitsAndHasSumsEverySelectedMachine() {
    var yes = List.<ProgramCommand>of(new ProgramCommand.Log("yes"));
    var no = List.<ProgramCommand>of(new ProgramCommand.Log("no"));
    var host = new Counting();
    assertEquals(yes, logs("storage has exactly 5 a", host));
    assertEquals(no, logs("not storage has exactly 5 a", host));
    assertEquals(yes, logs("two has exactly 6 a", host), "3 + 3");
    assertEquals(2, host.contentReads, "One contents read per machine");

    host = new Counting();
    assertEquals(no, logs("storage has less than 1 a and two has more than 0 a", host));
    assertEquals(1, host.stockReads);
    assertEquals(0, host.contentReads, "A false left side skips the right of and");

    host = new Counting();
    assertEquals(yes, logs("storage has more than 1 a or two has more than 0 a", host));
    assertEquals(0, host.contentReads, "A true left side skips the right of or");

    host = new Counting();
    assertEquals(
        yes,
        logs(
            "not (storage has less than 1 a or storage has less than 1 b) and storage has at least 5 c",
            host),
        "not binds tighter than and; parentheses group");
    assertEquals(3, host.stockReads);
  }

  @Test
  void elseIfChainsPickTheFirstTrueBranchAndHasNeedsAMachineHost() {
    var rule =
        rule(
            "every 0.5 seconds:\n    for each m in one:\n        if m has less than 1 coal:\n            say \"empty\"\n        otherwise if m has less than 8 coal:\n            say \"low\"\n        otherwise:\n            say \"full\"\n");
    assertEquals(
        List.of(new ProgramCommand.Log("low")), ProgramEvaluator.invoke(rule, new Counting()));
    assertThrows(
        ProgramEvaluator.Trap.class,
        () -> ProgramEvaluator.invoke(rule, host(Map.of("one", List.of(A)), 0)),
        "A host that cannot read machines traps the invocation");
  }

  @Test
  void redstoneIsTrueWhenAnySelectedMachineIsPoweredAndStopsReading() {
    var reads = new int[1];
    var host =
        new ProgramEvaluator.Host() {
          @Override
          public List<UUID> machines(String label) {
            return List.of(A, B, OUT);
          }

          @Override
          public long stock(ResourceFilter filter) {
            return 0;
          }

          @Override
          public boolean powered(UUID machine) {
            reads[0]++;
            return machine.equals(B);
          }
        };
    var rule =
        rule(
            "every 0.5 seconds:\n    if b is powered:\n        turn on lamp\n    otherwise:\n        turn off lamp\n");
    assertEquals(List.of(new ProgramCommand.Emit("lamp", 15)), ProgramEvaluator.invoke(rule, host));
    assertEquals(2, reads[0], "Stops at the first powered machine");
  }
}
