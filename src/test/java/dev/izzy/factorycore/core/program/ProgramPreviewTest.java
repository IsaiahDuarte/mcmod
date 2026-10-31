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
        "every 0.5 seconds called stock:\n    for each m in fuel:\n        keep 16 coal in m\nevery second called \"craft\":\n    if storage has less than 8 oak planks:\n        craft 4 oak planks\n";
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
    var many = new StringBuilder("every 10 ticks:\n");
    for (int index = 0; index < 20; index++) many.append("    say \"line\"\n");
    var program = FriendlyCompiler.compile(many.toString()).program();
    var preview = ProgramPreview.evaluate(program, 0, host(0, List.of()));
    assertEquals(8, preview.lines().size());
    assertTrue(preview.lines().getLast().contains("total 20"));
    var loop =
        FriendlyCompiler.compile(
                "every 0.5 seconds:\n    say \"before\"\n    for each m in x:\n        say \"machine\"\n")
            .program();
    var failed =
        ProgramPreview.evaluate(loop, 0, host(0, Collections.nCopies(65, UUID.randomUUID())));
    assertEquals(2, failed.lines().size());
    assertTrue(failed.lines().getLast().contains("Preview refused"));
    assertFalse(failed.lines().stream().anyMatch(line -> line.equals("Log: before")));
  }

  /** Regression: the preview's naming wrapper dropped machine reads, so `has` previews trapped. */
  @Test
  void previewForwardsMachineContentsAndRedstoneReads() {
    var machine = UUID.randomUUID();
    var program =
        FriendlyCompiler.compile(
                "every 0.5 seconds:\n    if f has less than 8 coal and f is powered:\n        turn on alarm\n")
            .program();
    var host =
        new ProgramEvaluator.Host() {
          @Override
          public List<UUID> machines(String label) {
            return List.of(machine);
          }

          @Override
          public long stock(ResourceFilter filter) {
            return 0;
          }

          @Override
          public ProgramEvaluator.Contents contents(UUID id, ResourceFilter filter) {
            return new ProgramEvaluator.Contents(2, 1);
          }

          @Override
          public boolean powered(UUID id) {
            return true;
          }
        };
    var lines = ProgramPreview.evaluate(program, 0, host).lines();
    assertTrue(
        lines.stream().anyMatch(line -> line.contains("Emit redstone 15 on \"alarm\"")),
        lines.toString());
  }
}
