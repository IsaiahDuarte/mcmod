package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class FriendlyCompilerTest {
  private static final String SMELTERS =
      """
      # Keep smelters fed and empty their output.
      every 10 ticks as "smelt" {
          for machine in machines("smelter_in") {
              keep machine stocked with 64 of item("minecraft:raw_iron") from storage
          }
          move item("minecraft:iron_ingot") from machines("smelter_out") to storage limit 64
      }
      every 5 seconds {
          if stock(tag("logs")) < 32 {
              craft 16 of item("oak_planks")
              log "Low on logs"
          }
      }
      """;

  private static ProgramIr.Program ok(String source) {
    var result = FriendlyCompiler.compile(source);
    assertTrue(result.ok(), () -> "Expected success: " + result.diagnostics());
    return result.program();
  }

  private static FriendlyCompiler.Diagnostic error(String source) {
    var result = FriendlyCompiler.compile(source);
    assertFalse(result.ok(), "Expected a diagnostic");
    assertFalse(result.diagnostics().isEmpty());
    return result.diagnostics().getFirst();
  }

  @Test
  void specExampleCompilesToStableRulesWithExactIr() {
    var program = ok(SMELTERS);
    assertEquals(2, program.rules().size());
    var smelt = program.rules().get(0);
    assertEquals("smelt", smelt.key());
    assertEquals(10, smelt.interval());
    var loop = (ProgramIr.For) smelt.body().get(0);
    assertEquals("machine", loop.variable());
    assertEquals("smelter_in", loop.label());
    var keep = (ProgramIr.Keep) loop.body().get(0);
    assertEquals(new ProgramIr.Variable("machine"), keep.target());
    assertEquals(64, keep.amount());
    assertEquals(
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Registry("minecraft:raw_iron"))),
        keep.filter());
    var move = (ProgramIr.Move) smelt.body().get(1);
    assertEquals(new ProgramIr.Machine(new ProgramIr.Label("smelter_out")), move.from());
    assertEquals(new ProgramIr.Storage(), move.to());
    var second = program.rules().get(1);
    assertEquals("rule-2", second.key(), "Unnamed rules get ordinal keys");
    assertEquals(100, second.interval(), "Seconds are 20 ticks");
    var condition = (ProgramIr.If) second.body().get(0);
    assertEquals(new ResourceFilter.Tag("minecraft:logs"), condition.filter().terms().getFirst());
    assertEquals(ProgramIr.Comparison.LESS, condition.comparison());
    assertEquals(new ProgramIr.Craft("minecraft:oak_planks", 16), condition.body().get(0));
    assertEquals(new ProgramIr.Log("Low on logs"), condition.body().get(1));
  }

  @Test
  void defaultsAndEnergyAndFluidFilters() {
    var program =
        ok(
            "every 20 ticks { move energy from storage to machines(\"bank\") \n move fluid(\"water\") from machines(\"pump\") to storage }");
    var energy = (ProgramIr.Move) program.rules().getFirst().body().get(0);
    assertEquals(ResourceKind.ENERGY, energy.filter().kind());
    assertEquals(FriendlyCompiler.DEFAULT_LIMIT, energy.limit());
    var fluid = (ProgramIr.Move) program.rules().getFirst().body().get(1);
    assertEquals(ResourceKind.FLUID, fluid.filter().kind());
    assertTrue(FriendlyCompiler.compile("").ok(), "An empty program is valid");
  }

  @Test
  void diagnosticsPointAtTheProblem() {
    var unknown = error("every 10 ticks {\n  smelt everything\n}");
    assertEquals(2, unknown.line());
    assertEquals(3, unknown.column());
    assertTrue(unknown.message().contains("Unknown statement"));
    var unbound = error("every 10 ticks { keep m stocked with 4 of item(\"stone\") }");
    assertTrue(unbound.message().contains("not a machine variable"), unbound.message());
    assertEquals(23, unbound.column());
    assertTrue(error("every 1 ticks { }").message().contains("10 ticks"));
    assertTrue(
        error("every 10 ticks as \"a\" { }\nevery 10 ticks as \"a\" { }")
            .message()
            .contains("already uses"));
    assertTrue(error("every 10 ticks { log \"unterminated }").message().contains("closing quote"));
    assertTrue(error("every 10 ticks { log \"x\"").message().contains("closing '}'"));
    assertTrue(
        error("every 10 ticks { move item(\"stone\") from storage to storage }")
            .message()
            .contains("not storage to storage"));
    assertTrue(
        error("every 10 ticks { move item(\"stone\") from storage to machines(\"m\") limit 5000 }")
            .message()
            .contains("4,096"));
    assertTrue(
        error("every 10 ticks { craft 0 of item(\"stone\") }").message().contains("between 1"));
    assertTrue(error("every 10 ticks { log \"x\" } stray").message().contains("'every'"));
    assertTrue(
        error("every 10 ticks { for m in machines(\"a\") { for m in machines(\"b\") { } } }")
            .message()
            .contains("already a machine variable"));
    assertTrue(
        error("every 10 ticks { craft 1 of item(\"Bad Id\") }").message().contains("registry ID"));
    assertTrue(error("every 10 ticks { log \"\\q\" }").message().contains("escape"));
    assertTrue(error("x".repeat(FriendlyCompiler.MAX_SOURCE + 1)).message().contains("16,384"));
  }

  @Test
  void recoveryReportsSeveralErrorsButCapsTheList() {
    var result =
        FriendlyCompiler.compile(
            "every 10 ticks {\n bogus\n keep q stocked with 1 of item(\"stone\")\n log \"fine\"\n}");
    assertEquals(2, result.diagnostics().size(), result.diagnostics().toString());
    var many = new StringBuilder("every 10 ticks {\n");
    // Each malformed statement starts a new statement, so recovery reports it separately.
    for (int i = 0; i < 40; i++) many.append(" log ").append(i).append('\n');
    many.append("}");
    assertEquals(
        FriendlyCompiler.MAX_DIAGNOSTICS,
        FriendlyCompiler.compile(many.toString()).diagnostics().size());
  }

  @Test
  void depthAndStatementBoundsReject() {
    var deep = new StringBuilder("every 10 ticks {");
    for (int i = 0; i < 9; i++) deep.append(" if stock(item(\"stone\")) > 0 {");
    for (int i = 0; i < 9; i++) deep.append(" }");
    deep.append(" }");
    assertTrue(error(deep.toString()).message().contains("8 deep"));
    var wide = new StringBuilder("every 10 ticks {");
    for (int i = 0; i < FriendlyCompiler.MAX_STATEMENTS + 1; i++) wide.append(" log \"x\"");
    wide.append(" }");
    var result =
        FriendlyCompiler.compile(
            wide.length() <= FriendlyCompiler.MAX_SOURCE ? wide.toString() : wide.substring(0, 0));
    if (wide.length() <= FriendlyCompiler.MAX_SOURCE)
      assertTrue(result.diagnostics().getFirst().message().contains("512"));
  }

  @Test
  void sideAndSlotsParseOnMachineEndpointsOnlyAndKeepPlainEndpointsV1() {
    var result =
        FriendlyCompiler.compile(
            """
            every 20 ticks as "f" {
              move item("coal") from storage to machines("f") side north slots 1 limit 8
              move item("iron_ingot") from machines("f") side down slots 2..3 to storage
              move item("stone") from machines("a") slots 4 to machines("b")
              move item("stone") from machines("a") to machines("b")
            }
            """);
    assertTrue(result.ok(), result.diagnostics()::toString);
    var body = result.program().rules().getFirst().body();
    assertEquals(
        new ProgramIr.MachineAt(new ProgramIr.Label("f"), ProgramIr.Side.NORTH, 1, 1),
        ((ProgramIr.Move) body.get(0)).to());
    assertEquals(
        new ProgramIr.MachineAt(new ProgramIr.Label("f"), ProgramIr.Side.DOWN, 2, 3),
        ((ProgramIr.Move) body.get(1)).from());
    assertEquals(
        new ProgramIr.MachineAt(new ProgramIr.Label("a"), null, 4, 4),
        ((ProgramIr.Move) body.get(2)).from());
    assertEquals(
        new ProgramIr.Machine(new ProgramIr.Label("a")),
        ((ProgramIr.Move) body.get(3)).from(),
        "No side or slots keeps the v1 place");
    assertTrue(
        error("every 10 ticks { move item(\"a\") from storage side up to machines(\"m\") }")
            .message()
            .contains("Storage has no side"));
    assertTrue(
        error("every 10 ticks { move item(\"a\") from storage to machines(\"m\") side left }")
            .message()
            .contains("down, up, north"));
    assertTrue(
        error("every 10 ticks { move item(\"a\") from storage to machines(\"m\") slots 3..1 }")
            .message()
            .contains("lower slot"));
    assertTrue(
        error("every 10 ticks { move item(\"a\") from storage to machines(\"m\") slots 1024 }")
            .message()
            .contains("at most 1023"));
    assertTrue(
        error("every 10 ticks { for up in machines(\"m\") { log \"x\" } }")
            .message()
            .contains("'up' is a reserved word"));
  }

  @Test
  void conditionsBindNotThenAndThenOrAndAPlainStockIfStaysV1() {
    var plain =
        FriendlyCompiler.compile("every 10 ticks { if stock(item(\"a\")) < 3 { log \"x\" } }");
    assertInstanceOf(ProgramIr.If.class, plain.program().rules().getFirst().body().getFirst());
    var mixed =
        FriendlyCompiler.compile(
            "every 10 ticks { if not stock(item(\"a\")) < 1 or stock(item(\"b\")) < 2 and stock(item(\"c\")) < 3 { log \"x\" } }");
    assertTrue(mixed.ok(), mixed.diagnostics()::toString);
    var branch = (ProgramIr.Branch) mixed.program().rules().getFirst().body().getFirst();
    var or = (ProgramIr.Or) branch.condition();
    assertInstanceOf(ProgramIr.Not.class, or.left());
    assertInstanceOf(ProgramIr.And.class, or.right());
    var many = new StringBuilder("stock(item(\"a\")) < 1");
    for (int i = 0; i < 17; i++) many.append(" or stock(item(\"a\")) < 1");
    assertTrue(
        error("every 10 ticks { if " + many + " { log \"x\" } }").message().contains("at most 16"));
    assertTrue(
        error("every 10 ticks { if storage { log \"x\" } }")
            .message()
            .contains("Expected a condition"));
  }
}
