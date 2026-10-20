package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A10 production limits on host work, host queries and program logs. */
class ProgramLimitsTest {
  private static ProgramIr.Rule rule(String source) {
    var result = FriendlyCompiler.compile(source);
    assertTrue(result.ok(), result.diagnostics()::toString);
    return result.program().rules().getFirst();
  }

  /** Host that charges {@code pagesPerStock} work units to {@code budget} for every stock read. */
  private static ProgramEvaluator.Host charging(
      HostBudget budget, int machines, int pagesPerStock, int[] stockCalls) {
    var ids = IntStream.range(0, machines).mapToObj(i -> new UUID(7, i)).toList();
    return new ProgramEvaluator.Host() {
      @Override
      public List<UUID> machines(String label) {
        budget.charge(1 + ids.size() / 16, "machines");
        return ids;
      }

      @Override
      public long stock(ResourceFilter filter) {
        stockCalls[0]++;
        for (int page = 0; page < pagesPerStock; page++) budget.charge(1, "stock queries");
        return 0;
      }
    };
  }

  private static String checks(int count) {
    var source = new StringBuilder("every 10 ticks {\n");
    for (int i = 0; i < count; i++)
      source.append("  if stock(item(\"stone\")) < 1 { craft 1 of item(\"stone\") }\n");
    return source.append("}\n").toString();
  }

  @Test
  void hostWorkBudgetTrapsAnInvocationWhoseQueriesTogetherExceedIt() {
    int[] calls = {0};
    var trap =
        assertThrows(
            ProgramEvaluator.Trap.class,
            () ->
                ProgramEvaluator.evaluate(
                    rule(checks(7)), charging(new HostBudget(), 0, 38, calls)));
    assertTrue(trap.getMessage().contains("host work budget of 256"), trap.getMessage());
    assertEquals(7, calls[0], "Each query stayed under its own page bound; their sum did not");

    var budget = new HostBudget();
    calls[0] = 0;
    var outcome = ProgramEvaluator.evaluate(rule(checks(6)), charging(budget, 0, 38, calls));
    assertEquals(6, outcome.commands().size(), "Six reads (228 units) fit the budget");
    assertEquals(228, budget.used());
  }

  @Test
  void hostBudgetRefusesNegativeWorkAndReportsTheExhaustingQuery() {
    var budget = new HostBudget(4);
    budget.charge(3, "a");
    assertThrows(IllegalArgumentException.class, () -> budget.charge(-1, "b"));
    var trap = assertThrows(ProgramEvaluator.Trap.class, () -> budget.charge(2, "stock queries"));
    assertTrue(trap.getMessage().endsWith("exceeded by stock queries"));
    assertEquals(4, budget.used(), "An exhausted budget stays exhausted");
    assertThrows(ProgramEvaluator.Trap.class, () -> budget.charge(1, "again"));
    assertThrows(IllegalArgumentException.class, () -> new HostBudget(0));
  }

  @Test
  void friendlyRulesAreLimitedTo512HostQueriesBeforeTheirStepBound() {
    var source = new StringBuilder("every 10 ticks {\n  for m in machines(\"all\") {\n");
    for (int i = 0; i < 9; i++) source.append("    if stock(item(\"stone\")) < 0 { log \"x\" }\n");
    source.append("  }\n}\n");
    int[] calls = {0};
    var trap =
        assertThrows(
            ProgramEvaluator.Trap.class,
            () ->
                ProgramEvaluator.evaluate(
                    rule(source.toString()),
                    charging(new HostBudget(Integer.MAX_VALUE), 64, 0, calls)));
    assertTrue(trap.getMessage().contains("512 host queries"), trap.getMessage());
    assertEquals(ProgramEvaluator.MAX_HOST_CALLS - 1, calls[0], "One label query plus 511 reads");
  }

  @Test
  void repeatedLinesAreCountedInsteadOfEvictingEarlierDiagnostics() {
    var log = new ProgramLog();
    log.add("trap smelt: boom");
    for (int i = 0; i < 500; i++) log.add("move minecraft:coal: busy");
    assertEquals(List.of("trap smelt: boom", "move minecraft:coal: busy (×500)"), log.lines());
    log.add("other");
    log.add("move minecraft:coal: busy");
    assertEquals("move minecraft:coal: busy", log.lines().getLast(), "Only immediate repeats");

    var longLine = "x".repeat(400);
    log.add(longLine);
    log.add(longLine);
    var last = log.lines().getLast();
    assertTrue(last.length() <= ProgramLog.MAX_LENGTH && last.endsWith(" (×2)"), last);
    for (int i = 0; i < 40; i++) log.add("line " + i);
    assertEquals(ProgramLog.MAX_LINES, log.lines().size());
    assertEquals("line 39", log.lines().getLast());
  }

  @Test
  void eachInvocationKeepsAtMostEightGuestLinesAndReportsTheRest() {
    var log = new ProgramLog();
    var lines = new ArrayList<String>();
    for (int i = 0; i < 256; i++) lines.add("spam " + i);
    assertEquals(248, log.addInvocation("noisy", lines));
    var kept = log.lines();
    assertEquals(9, kept.size());
    assertEquals("noisy: spam 0", kept.getFirst());
    assertEquals("noisy: 248 more log lines suppressed (limit 8)", kept.getLast());
    assertEquals(0, log.addInvocation("quiet", Collections.nCopies(3, "ok")));
    assertEquals("quiet: ok (×3)", log.lines().getLast());
    log.clear();
    assertTrue(log.lines().isEmpty());
  }
}
