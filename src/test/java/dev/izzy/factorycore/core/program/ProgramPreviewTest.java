package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProgramPreviewTest {
  private static ProgramEvaluator.Host host(long stock, List<UUID> machines) {
    return new ProgramEvaluator.Host() {
      @Override
      public List<UUID> machines(String label) {
        return machines;
      }

      @Override
      public long stock(ResourceFilter filter) {
        return stock;
      }
    };
  }

  @Test
  void resolvesTargetsAndConditionsForOneRuleWithoutPublishingCommands() {
    var source =
        "every 10 ticks as \"stock\" { for m in machines(\"fuel\") { keep m stocked with 16 of item(\"coal\") from storage } }\n"
            + "every 20 ticks as \"craft\" { if stock(item(\"oak_planks\")) < 8 { craft 4 of item(\"oak_planks\") } }";
    var program = FriendlyCompiler.compile(source).program();
    var machine = UUID.randomUUID();
    var first = ProgramPreview.evaluate(program, 0, host(0, List.of(machine)));
    assertEquals(0, first.rule());
    assertEquals(2, first.total());
    assertTrue(first.lines().get(1).contains("at \"fuel\" #1"), first.lines().get(1));
    assertFalse(first.lines().get(1).contains(machine.toString()), "No internal machine IDs");
    assertTrue(first.lines().get(1).contains("16 item minecraft:coal"));
    assertTrue(
        ProgramPreview.evaluate(program, 1, host(0, List.of()))
            .lines()
            .get(1)
            .contains("Craft 4 minecraft:oak_planks"));
    assertTrue(
        ProgramPreview.evaluate(program, 1, host(8, List.of()))
            .lines()
            .get(1)
            .contains("No actions"));
  }

  @Test
  void boundedOutputAndTrapsNeverPresentPartOfAFailedInvocationAsActions() {
    var many = new StringBuilder("every 10 ticks { ");
    for (int index = 0; index < 20; index++) many.append("log \"line\" ");
    var program = FriendlyCompiler.compile(many.append("}").toString()).program();
    var preview = ProgramPreview.evaluate(program, 0, host(0, List.of()));
    assertEquals(8, preview.lines().size());
    assertTrue(preview.lines().getLast().contains("total 20"));
    var loop =
        FriendlyCompiler.compile(
                "every 10 ticks { log \"before\" for m in machines(\"x\") { log \"machine\" } }")
            .program();
    var failed =
        ProgramPreview.evaluate(loop, 0, host(0, Collections.nCopies(65, UUID.randomUUID())));
    assertEquals(2, failed.lines().size());
    assertTrue(failed.lines().getLast().contains("Preview refused"));
    assertFalse(failed.lines().stream().anyMatch(line -> line.equals("Log: before")));
  }
}
