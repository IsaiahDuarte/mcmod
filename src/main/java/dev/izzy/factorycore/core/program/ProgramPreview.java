package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One bounded, read-only invocation; it never submits commands, reserves stock or charges FE. */
public final class ProgramPreview {
  public static final int MAX_LINES = 8;

  public record Result(int rule, int total, List<String> lines) {
    public Result {
      lines = List.copyOf(lines);
    }
  }

  private ProgramPreview() {}

  public static Result evaluate(ProgramIr.Program program, int index, ProgramEvaluator.Host host) {
    if (program.rules().isEmpty()) return new Result(0, 0, List.of("No rules in this draft"));
    int selected = Math.min(Math.max(index, 0), program.rules().size() - 1);
    var rule = program.rules().get(selected);
    var lines = new ArrayList<String>();
    lines.add(
        "Rule "
            + (selected + 1)
            + "/"
            + program.rules().size()
            + ": "
            + rule.key()
            + " ("
            + rule.interval()
            + " ticks)");
    // Players know machines by local label, so name each target "label" #n, not its internal ID.
    var names = new HashMap<UUID, String>();
    ProgramEvaluator.Host naming =
        new ProgramEvaluator.Host() {
          @Override
          public List<UUID> machines(String label) {
            var found = host.machines(label);
            for (int i = 0; i < found.size(); i++)
              names.putIfAbsent(found.get(i), "\"" + label + "\" #" + (i + 1));
            return found;
          }

          @Override
          public long stock(ResourceFilter filter) {
            return host.stock(filter);
          }
        };
    try {
      var commands = ProgramEvaluator.invoke(rule, naming);
      if (commands.isEmpty()) lines.add("No actions at the current stock and local labels");
      for (var command : commands) {
        if (lines.size() == MAX_LINES - 1 && commands.size() > MAX_LINES - 1) {
          lines.add("More actions omitted; total " + commands.size());
          break;
        }
        var line = describe(command, names);
        lines.add(line.length() > 192 ? line.substring(0, 189) + "..." : line);
      }
    } catch (ProgramEvaluator.Trap failure) {
      var line = "Preview refused: " + failure.getMessage();
      lines.add(line.length() > 192 ? line.substring(0, 189) + "..." : line);
    }
    return new Result(selected, program.rules().size(), lines);
  }

  private static String filter(ResourceFilter filter) {
    var term = filter.terms().isEmpty() ? "all" : filter.terms().getFirst();
    return filter.kind().name().toLowerCase(Locale.ROOT)
        + " "
        + (term instanceof ResourceFilter.Registry registry
            ? registry.id()
            : term instanceof ResourceFilter.Tag tag ? "#" + tag.id() : term.toString());
  }

  private static String describe(ProgramCommand command, Map<UUID, String> names) {
    return switch (command) {
      case ProgramCommand.Keep keep ->
          "Keep "
              + keep.amount()
              + " "
              + filter(keep.filter())
              + " at "
              + names.getOrDefault(keep.machine(), String.valueOf(keep.machine()));
      case ProgramCommand.Move move ->
          "Move <= "
              + move.limit()
              + " "
              + filter(move.filter())
              + " "
              + (move.from() == null
                  ? "storage"
                  : names.getOrDefault(move.from(), String.valueOf(move.from())))
              + " -> "
              + (move.to() == null
                  ? "storage"
                  : names.getOrDefault(move.to(), String.valueOf(move.to())));
      case ProgramCommand.Craft craft -> "Craft " + craft.amount() + " " + craft.item();
      case ProgramCommand.Log log -> "Log: " + log.text();
    };
  }
}
