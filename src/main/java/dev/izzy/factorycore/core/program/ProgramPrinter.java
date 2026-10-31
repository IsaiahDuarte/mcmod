package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Prints a {@link ProgramIr.Program} as a sentence program (ADR 0068) that compiles back to equal
 * IR under the same {@link Vocabulary}. Names are printed as words when they read back exactly,
 * else as IDs. A few brace-only shapes have no sentence spelling (an empty block, a filter of
 * several plain terms); printing them throws {@link IllegalArgumentException} with a reason.
 */
public final class ProgramPrinter {
  private static final String INDENT = "    ";
  private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_/]*");

  /** An ID the lexer reads as one word; others are quoted. */
  private static final Pattern ID = Pattern.compile("[a-z_][a-z0-9_]*:[a-z_][a-z0-9_/]*");

  private static final Set<String> PHRASE_END = Set.of("from", "to", "in", "except", "and", "or");

  /** First words that mean something else at the start of a name. */
  private static final Set<String> LEADING =
      Set.of("everything", "items", "fluids", "fluid", "energy", "all", "no", "any", "tag");

  private final Vocabulary vocabulary;
  private final StringBuilder out = new StringBuilder();

  private ProgramPrinter(Vocabulary vocabulary) {
    this.vocabulary = vocabulary;
  }

  public static String print(ProgramIr.Program program, Vocabulary vocabulary) {
    var printer = new ProgramPrinter(vocabulary);
    int ordinal = 0;
    for (var rule : program.rules()) printer.rule(rule, ++ordinal);
    return printer.out.toString();
  }

  private void rule(ProgramIr.Rule rule, int ordinal) {
    out.append("every ").append(interval(rule.interval()));
    if (!rule.key().equals("rule-" + ordinal)) out.append(" called ").append(label(rule.key()));
    out.append(":\n");
    block(rule.body(), 1);
  }

  private static String interval(int ticks) {
    if (ticks == 1_200) return "minute";
    if (ticks % 1_200 == 0) return ticks / 1_200 + " minutes";
    if (ticks == 20) return "second";
    if (ticks % 20 == 0) return ticks / 20 + " seconds";
    if (ticks % 10 == 0) return ticks / 20 + ".5 seconds";
    return ticks + " ticks";
  }

  private void block(List<ProgramIr.Statement> body, int depth) {
    if (body.isEmpty())
      throw new IllegalArgumentException("An empty block has no sentence spelling");
    for (var statement : body) statement(statement, depth);
  }

  private void line(int depth, String text) {
    out.append(INDENT.repeat(depth)).append(text).append('\n');
  }

  private void statement(ProgramIr.Statement statement, int depth) {
    switch (statement) {
      case ProgramIr.Move move -> {
        String amount =
            move.limit() == FriendlyCompiler.DEFAULT_LIMIT
                ? ""
                : move.limit() == FriendlyCompiler.MAX_LIMIT ? "all " : move.limit() + " ";
        line(
            depth,
            "move "
                + amount
                + stuff(move.filter())
                + " from "
                + place(move.from())
                + " to "
                + place(move.to()));
      }
      case ProgramIr.Keep keep ->
          line(
              depth,
              "keep "
                  + keep.amount()
                  + " "
                  + stuff(keep.filter())
                  + " in "
                  + target(keep.target()));
      case ProgramIr.KeepAt keep ->
          line(
              depth,
              "keep " + keep.amount() + " " + stuff(keep.filter()) + " in " + place(keep.place()));
      case ProgramIr.Craft craft ->
          line(
              depth,
              "craft "
                  + craft.amount()
                  + " "
                  + stuff(
                      new ResourceFilter(
                          ResourceKind.ITEM,
                          ResourceFilter.Mode.INCLUDE,
                          List.of(new ResourceFilter.Registry(craft.item())))));
      case ProgramIr.If check -> {
        line(
            depth,
            "if "
                + condition(
                    new ProgramIr.StockCondition(
                        check.filter(), check.comparison(), check.amount()))
                + ":");
        block(check.body(), depth + 1);
      }
      case ProgramIr.Branch branch -> branch(branch, depth, "if ");
      case ProgramIr.For loop -> {
        line(depth, "for each " + loop.variable() + " in " + label(loop.label()) + ":");
        block(loop.body(), depth + 1);
      }
      case ProgramIr.Emit emit ->
          line(
              depth,
              switch (emit.strength()) {
                case 15 -> "turn on " + label(emit.label());
                case 0 -> "turn off " + label(emit.label());
                default -> "set " + label(emit.label()) + " redstone to " + emit.strength();
              });
      case ProgramIr.Log log -> line(depth, "say " + quote(log.text()));
    }
  }

  private void branch(ProgramIr.Branch branch, int depth, String opener) {
    line(depth, opener + condition(branch.condition()) + ":");
    block(branch.then(), depth + 1);
    var otherwise = branch.otherwise();
    if (otherwise.isEmpty()) return;
    if (otherwise.size() == 1 && otherwise.getFirst() instanceof ProgramIr.Branch inner)
      branch(inner, depth, "otherwise if ");
    else if (otherwise.size() == 1 && otherwise.getFirst() instanceof ProgramIr.If inner) {
      line(
          depth,
          "otherwise if "
              + condition(
                  new ProgramIr.StockCondition(inner.filter(), inner.comparison(), inner.amount()))
              + ":");
      block(inner.body(), depth + 1);
    } else {
      line(depth, "otherwise:");
      block(otherwise, depth + 1);
    }
  }

  // ---- Conditions ----

  private String condition(ProgramIr.Condition condition) {
    return switch (condition) {
      case ProgramIr.Or or -> condition(or.left()) + " or " + grouped(or.right(), true);
      case ProgramIr.And and -> grouped(and.left(), false) + " and " + grouped(and.right(), true);
      case ProgramIr.Not not when not.inner() instanceof ProgramIr.RedstoneCondition redstone ->
          target(redstone.target()) + " is unpowered";
      case ProgramIr.Not not -> "not " + grouped(not.inner(), true);
      case ProgramIr.RedstoneCondition redstone -> target(redstone.target()) + " is powered";
      case ProgramIr.StockCondition stock ->
          "storage has "
              + compare(stock.comparison(), stock.amount())
              + " "
              + stuff(stock.filter());
      case ProgramIr.HasCondition has ->
          target(has.target())
              + " has "
              + compare(has.comparison(), has.amount())
              + " "
              + stuff(has.filter());
    };
  }

  /**
   * A condition inside {@code and} or {@code not}: an {@code or} always needs parentheses, and an
   * {@code and} does on the right of another {@code and} (left to right otherwise).
   */
  private String grouped(ProgramIr.Condition condition, boolean right) {
    boolean wrap = condition instanceof ProgramIr.Or || right && condition instanceof ProgramIr.And;
    return wrap ? "(" + condition(condition) + ")" : condition(condition);
  }

  private static String compare(ProgramIr.Comparison comparison, long amount) {
    return switch (comparison) {
          case LESS -> "less than ";
          case LESS_EQUAL -> "at most ";
          case GREATER -> "more than ";
          case GREATER_EQUAL -> "at least ";
          case EQUAL -> "exactly ";
        }
        + amount;
  }

  // ---- Places and labels ----

  private String place(ProgramIr.Place place) {
    return switch (place) {
      case ProgramIr.Storage storage -> "storage";
      case ProgramIr.Machine machine -> target(machine.target());
      case ProgramIr.MachineAt at -> {
        var text = new StringBuilder(target(at.target()));
        if (at.side() != null)
          text.append(
              switch (at.side()) {
                case UP -> " top";
                case DOWN -> " bottom";
                case NORTH -> " north";
                case SOUTH -> " south";
                case EAST -> " east";
                case WEST -> " west";
              });
        if (at.first() >= 0)
          text.append(
              at.first() == at.last()
                  ? " slot " + at.first()
                  : " slots " + at.first() + " to " + at.last());
        yield text.toString();
      }
    };
  }

  private static String target(ProgramIr.Target target) {
    return switch (target) {
      case ProgramIr.Variable variable -> variable.name();
      case ProgramIr.Label label -> label(label.label());
    };
  }

  /** A label (or rule name) bare when it reads as one word that is not a word of the language. */
  private static String label(String label) {
    return Pattern.matches("[A-Za-z_][A-Za-z0-9_/]*", label)
            && !SentenceCompiler.KEYWORDS.contains(label.toLowerCase(java.util.Locale.ROOT))
        ? label
        : quote(label);
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  // ---- What moves ----

  private String stuff(ResourceFilter filter) {
    if (filter.kind() == ResourceKind.ENERGY) return "energy";
    if (filter.mode() != ResourceFilter.Mode.INCLUDE || filter.terms().size() != 1)
      throw new IllegalArgumentException("This filter has no sentence spelling");
    var term = filter.terms().getFirst();
    if (term instanceof ResourceFilter.Except except) {
      var text = new StringBuilder(term(except.include(), filter.kind()));
      for (var excluded : except.excluded())
        text.append(" except ").append(term(excluded, filter.kind()));
      return text.toString();
    }
    return term(term, filter.kind());
  }

  private String term(ResourceFilter.Term term, ResourceKind kind) {
    boolean fluid = kind == ResourceKind.FLUID;
    return switch (term) {
      case ResourceFilter.Glob glob when glob.pattern().equals("*") ->
          fluid ? "fluids" : "everything";
      case ResourceFilter.Glob glob -> (fluid ? "fluid " : "") + glob.pattern();
      case ResourceFilter.Tag tag -> {
        var words = words(tag.id());
        yield words != null && term.equals(SentenceCompiler.name(words, kind, vocabulary))
            ? words
            : "tag " + tag.id();
      }
      case ResourceFilter.Registry registry -> {
        var words = words(registry.id());
        String name =
            words != null && term.equals(SentenceCompiler.name(words, kind, vocabulary))
                ? words
                : ID.matcher(registry.id()).matches() ? registry.id() : quote(registry.id());
        yield (fluid ? "fluid " : "") + name;
      }
      default -> throw new IllegalArgumentException("This filter has no sentence spelling");
    };
  }

  /** An ID's path as plain words, or null when the words would read as something else. */
  private static String words(String id) {
    var path = id.substring(id.indexOf(':') + 1);
    var words = path.split("_", -1);
    for (var word : words)
      if (word.isEmpty() || !WORD.matcher(word).matches() || PHRASE_END.contains(word)) return null;
    if (LEADING.contains(words[0])) return null;
    return String.join(" ", words);
  }
}
