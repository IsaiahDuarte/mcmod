package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiler for the friendly language (D05 version 1) into {@link ProgramIr}. Grammar, limits and
 * semantics are frozen in ADR 0035. Compilation is pure and bounded; it never touches the world.
 */
public final class FriendlyCompiler {
  public static final int MAX_SOURCE = 16_384;
  public static final int MAX_STATEMENTS = 512;
  public static final int MAX_DEPTH = 8;
  public static final int MAX_DIAGNOSTICS = 16;
  public static final int MAX_STRING = 128;
  public static final long DEFAULT_LIMIT = 64;
  public static final long MAX_LIMIT = 4_096;

  /** One-based source location and a player-facing message. */
  public record Diagnostic(int line, int column, String message) {}

  /** Exactly one of {@code program} and a nonempty {@code diagnostics} list is meaningful. */
  public record Result(ProgramIr.Program program, List<Diagnostic> diagnostics) {
    public Result {
      diagnostics = List.copyOf(diagnostics);
    }

    public boolean ok() {
      return program != null;
    }
  }

  private enum Kind {
    WORD,
    INT,
    STRING,
    SYMBOL,
    END
  }

  private record Token(Kind kind, String text, long value, int line, int column) {}

  private static final class SyntaxError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Token at;

    SyntaxError(Token at, String message) {
      super(message, null, false, false);
      this.at = at;
    }
  }

  private static final Set<String> KEYWORDS =
      Set.of(
          "every",
          "tick",
          "ticks",
          "second",
          "seconds",
          "as",
          "for",
          "in",
          "machines",
          "keep",
          "stocked",
          "with",
          "of",
          "from",
          "storage",
          "move",
          "to",
          "limit",
          "craft",
          "if",
          "stock",
          "log",
          "item",
          "fluid",
          "tag",
          "energy");

  private final List<Token> tokens = new ArrayList<>();
  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private int position;
  private int statements;

  private FriendlyCompiler() {}

  public static Result compile(String source) {
    var compiler = new FriendlyCompiler();
    if (source.length() > MAX_SOURCE)
      return new Result(
          null, List.of(new Diagnostic(1, 1, "Program is longer than 16,384 characters")));
    try {
      compiler.lex(source);
    } catch (SyntaxError error) {
      return new Result(
          null, List.of(new Diagnostic(error.at.line, error.at.column, error.getMessage())));
    }
    var rules = compiler.program();
    if (!compiler.diagnostics.isEmpty()) return new Result(null, compiler.diagnostics);
    try {
      return new Result(new ProgramIr.Program(rules), List.of());
    } catch (IllegalArgumentException invalid) {
      return new Result(null, List.of(new Diagnostic(1, 1, invalid.getMessage())));
    }
  }

  // ---- Lexing ----

  private void lex(String source) {
    int line = 1, column = 1, index = 0;
    while (index < source.length()) {
      char c = source.charAt(index);
      if (c == '\n') {
        line++;
        column = 1;
        index++;
        continue;
      }
      if (Character.isWhitespace(c)) {
        column++;
        index++;
        continue;
      }
      if (c == '#') {
        while (index < source.length() && source.charAt(index) != '\n') index++;
        continue;
      }
      int startColumn = column;
      if (Character.isLetter(c) || c == '_') {
        int start = index;
        while (index < source.length()
            && (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_'))
          index++;
        column += index - start;
        tokens.add(new Token(Kind.WORD, source.substring(start, index), 0, line, startColumn));
      } else if (Character.isDigit(c)) {
        int start = index;
        while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
        column += index - start;
        var text = source.substring(start, index);
        long value;
        if (text.length() > 9) value = Long.MAX_VALUE;
        else value = Long.parseLong(text);
        tokens.add(new Token(Kind.INT, text, value, line, startColumn));
      } else if (c == '"') {
        var text = new StringBuilder();
        index++;
        column++;
        boolean closed = false;
        while (index < source.length()) {
          char d = source.charAt(index);
          if (d == '\n') break;
          index++;
          column++;
          if (d == '"') {
            closed = true;
            break;
          }
          if (d == '\\' && index < source.length()) {
            char e = source.charAt(index);
            index++;
            column++;
            if (e != '"' && e != '\\')
              throw new SyntaxError(
                  new Token(Kind.STRING, "", 0, line, column - 2), "Unknown escape \\" + e);
            d = e;
          }
          text.append(d);
          if (text.length() > MAX_STRING)
            throw new SyntaxError(
                new Token(Kind.STRING, "", 0, line, startColumn),
                "Text is longer than 128 characters");
        }
        if (!closed)
          throw new SyntaxError(
              new Token(Kind.STRING, "", 0, line, startColumn),
              "Text is missing its closing quote");
        tokens.add(new Token(Kind.STRING, text.toString(), 0, line, startColumn));
      } else {
        String symbol = null;
        for (var candidate : List.of("<=", ">=", "==", "{", "}", "(", ")", "<", ">"))
          if (source.startsWith(candidate, index)) {
            symbol = candidate;
            break;
          }
        if (symbol == null)
          throw new SyntaxError(
              new Token(Kind.SYMBOL, "", 0, line, column), "Unexpected character '" + c + "'");
        index += symbol.length();
        column += symbol.length();
        tokens.add(new Token(Kind.SYMBOL, symbol, 0, line, startColumn));
      }
    }
    tokens.add(new Token(Kind.END, "", 0, line, column));
  }

  // ---- Parsing helpers ----

  private Token peek() {
    return tokens.get(position);
  }

  private Token next() {
    var token = tokens.get(position);
    if (token.kind != Kind.END) position++;
    return token;
  }

  private boolean at(String text) {
    var token = peek();
    return (token.kind == Kind.WORD || token.kind == Kind.SYMBOL) && token.text.equals(text);
  }

  private Token expect(String text) {
    if (!at(text))
      throw new SyntaxError(peek(), "Expected '" + text + "' but found " + describe(peek()));
    return next();
  }

  private static String describe(Token token) {
    return switch (token.kind) {
      case END -> "the end of the program";
      case STRING -> "text \"" + token.text + "\"";
      default -> "'" + token.text + "'";
    };
  }

  private long amount(String what) {
    var token = peek();
    if (token.kind != Kind.INT) throw new SyntaxError(token, "Expected a whole number for " + what);
    next();
    if (token.value < 1 || token.value > ProgramIr.MAX_AMOUNT)
      throw new SyntaxError(token, what + " must be between 1 and " + ProgramIr.MAX_AMOUNT);
    return token.value;
  }

  private String string(String what) {
    var token = peek();
    if (token.kind != Kind.STRING) throw new SyntaxError(token, "Expected quoted text for " + what);
    next();
    return token.text;
  }

  private void error(Token at, String message) {
    if (diagnostics.size() < MAX_DIAGNOSTICS)
      diagnostics.add(new Diagnostic(at.line, at.column, message));
  }

  /** Skips to the end of the current statement or block so later errors can still be reported. */
  private void recover() {
    int depth = 0;
    while (peek().kind != Kind.END) {
      if (at("{")) depth++;
      if (at("}")) {
        if (depth == 0) return;
        depth--;
      }
      next();
      if (depth == 0 && peek().kind == Kind.WORD && isStatementStart(peek().text)) return;
    }
  }

  private static boolean isStatementStart(String word) {
    return Set.of("for", "keep", "move", "craft", "if", "log", "every").contains(word);
  }

  // ---- Grammar ----

  private List<ProgramIr.Rule> program() {
    var rules = new ArrayList<ProgramIr.Rule>();
    var keys = new HashSet<String>();
    while (peek().kind != Kind.END && diagnostics.size() < MAX_DIAGNOSTICS) {
      var start = peek();
      try {
        var rule = rule(rules.size() + 1);
        if (!keys.add(rule.key()))
          error(start, "Another rule already uses the key \"" + rule.key() + "\"");
        else if (rules.size() == ProgramIr.MAX_RULES)
          error(start, "A program has at most 64 rules");
        else rules.add(rule);
      } catch (SyntaxError syntax) {
        error(syntax.at, syntax.getMessage());
        while (peek().kind != Kind.END && !at("every")) next();
      }
    }
    return rules;
  }

  private ProgramIr.Rule rule(int ordinal) {
    if (!at("every"))
      throw new SyntaxError(peek(), "A program is made of rules starting with 'every'");
    next();
    var count = peek();
    long interval = amount("the interval");
    long ticks;
    if (at("tick") || at("ticks")) ticks = interval;
    else if (at("second") || at("seconds")) ticks = interval * 20;
    else throw new SyntaxError(peek(), "Expected 'ticks' or 'seconds' after the interval");
    next();
    if (ticks < ProgramIr.MIN_INTERVAL || ticks > ProgramIr.MAX_INTERVAL)
      throw new SyntaxError(count, "Rules run every 10 ticks to 1 hour (72,000 ticks)");
    String key = "rule-" + ordinal;
    if (at("as")) {
      next();
      var token = peek();
      key = string("the rule name");
      if (key.isEmpty() || key.length() > 64)
        throw new SyntaxError(token, "Rule names are 1–64 characters");
    }
    var body = block(new HashSet<>(), 1);
    return new ProgramIr.Rule(key, (int) ticks, body);
  }

  private List<ProgramIr.Statement> block(Set<String> variables, int depth) {
    if (depth > MAX_DEPTH) throw new SyntaxError(peek(), "Blocks nest at most 8 deep");
    expect("{");
    var body = new ArrayList<ProgramIr.Statement>();
    while (!at("}")) {
      if (peek().kind == Kind.END)
        throw new SyntaxError(peek(), "A block is missing its closing '}'");
      var start = peek();
      try {
        if (++statements > MAX_STATEMENTS)
          throw new SyntaxError(start, "A program has at most 512 statements");
        body.add(statement(variables, depth));
      } catch (SyntaxError syntax) {
        error(syntax.at, syntax.getMessage());
        if (diagnostics.size() >= MAX_DIAGNOSTICS) throw syntax;
        recover();
      }
    }
    expect("}");
    return body;
  }

  private ProgramIr.Statement statement(Set<String> variables, int depth) {
    var token = peek();
    if (token.kind != Kind.WORD)
      throw new SyntaxError(token, "Expected a statement but found " + describe(token));
    switch (token.text) {
      case "for" -> {
        next();
        var name = peek();
        if (name.kind != Kind.WORD || KEYWORDS.contains(name.text))
          throw new SyntaxError(name, "Expected a machine variable name after 'for'");
        next();
        if (variables.contains(name.text))
          throw new SyntaxError(name, "'" + name.text + "' is already a machine variable here");
        expect("in");
        var label = machines();
        var inner = new HashSet<>(variables);
        inner.add(name.text);
        return new ProgramIr.For(name.text, label, block(inner, depth + 1));
      }
      case "keep" -> {
        next();
        var target = target(variables);
        expect("stocked");
        expect("with");
        long amount = amount("the stock target");
        expect("of");
        var filter = filter();
        if (at("from")) {
          next();
          expect("storage");
        }
        return new ProgramIr.Keep(target, filter, amount);
      }
      case "move" -> {
        next();
        var filter = filter();
        expect("from");
        var fromToken = peek();
        var from = place(variables);
        expect("to");
        var to = place(variables);
        // ADR 0062 (D2): either side may be a machine; storage to storage moves nothing.
        if (from instanceof ProgramIr.Storage && to instanceof ProgramIr.Storage)
          throw new SyntaxError(
              fromToken, "A move needs a machine on at least one side, not storage to storage");
        long limit = DEFAULT_LIMIT;
        if (at("limit")) {
          next();
          var limitToken = peek();
          limit = amount("the limit");
          if (limit > MAX_LIMIT) throw new SyntaxError(limitToken, "A move limit is at most 4,096");
        }
        return new ProgramIr.Move(filter, from, to, limit);
      }
      case "craft" -> {
        next();
        long amount = amount("the craft amount");
        expect("of");
        var itemToken = peek();
        expect("item");
        expect("(");
        var id = registry(string("the item ID"), itemToken);
        expect(")");
        return new ProgramIr.Craft(id, amount);
      }
      case "if" -> {
        next();
        expect("stock");
        expect("(");
        var filter = filter();
        expect(")");
        var comparison = comparison();
        long amount =
            peek().kind == Kind.INT && peek().value == 0 ? zero() : amount("the comparison");
        return new ProgramIr.If(filter, comparison, amount, block(variables, depth + 1));
      }
      case "log" -> {
        next();
        return new ProgramIr.Log(string("the log message"));
      }
      default -> throw new SyntaxError(token, "Unknown statement '" + token.text + "'");
    }
  }

  private long zero() {
    next();
    return 0;
  }

  private ProgramIr.Comparison comparison() {
    var token = next();
    return switch (token.text) {
      case "<" -> ProgramIr.Comparison.LESS;
      case "<=" -> ProgramIr.Comparison.LESS_EQUAL;
      case ">" -> ProgramIr.Comparison.GREATER;
      case ">=" -> ProgramIr.Comparison.GREATER_EQUAL;
      case "==" -> ProgramIr.Comparison.EQUAL;
      default -> throw new SyntaxError(token, "Expected a comparison: <, <=, >, >= or ==");
    };
  }

  private String machines() {
    expect("machines");
    expect("(");
    var token = peek();
    var label = string("the machine label");
    if (label.isEmpty() || label.length() > 64)
      throw new SyntaxError(token, "Machine labels are 1–64 characters");
    expect(")");
    return label;
  }

  private ProgramIr.Target target(Set<String> variables) {
    if (at("machines")) return new ProgramIr.Label(machines());
    var token = peek();
    if (token.kind != Kind.WORD || KEYWORDS.contains(token.text))
      throw new SyntaxError(token, "Expected a machine variable or machines(\"label\")");
    next();
    if (!variables.contains(token.text))
      throw new SyntaxError(
          token, "'" + token.text + "' is not a machine variable from an enclosing 'for'");
    return new ProgramIr.Variable(token.text);
  }

  private ProgramIr.Place place(Set<String> variables) {
    if (at("storage")) {
      next();
      return new ProgramIr.Storage();
    }
    return new ProgramIr.Machine(target(variables));
  }

  private static String registry(String id, Token at) {
    if (!id.contains(":")) id = "minecraft:" + id;
    try {
      new ResourceKey(ResourceKind.ITEM, id, new byte[0]);
    } catch (IllegalArgumentException invalid) {
      throw new SyntaxError(at, "\"" + id + "\" is not a valid registry ID");
    }
    return id;
  }

  private ResourceFilter filter() {
    var token = peek();
    if (at("energy")) {
      next();
      return new ResourceFilter(
          ResourceKind.ENERGY,
          ResourceFilter.Mode.INCLUDE,
          List.of(new ResourceFilter.Registry(ResourceKey.ENERGY.registryId())));
    }
    ResourceKind kind;
    boolean tag = false;
    if (at("item")) kind = ResourceKind.ITEM;
    else if (at("fluid")) kind = ResourceKind.FLUID;
    else if (at("tag")) {
      kind = ResourceKind.ITEM;
      tag = true;
    } else
      throw new SyntaxError(token, "Expected item(\"id\"), fluid(\"id\"), tag(\"id\") or energy");
    next();
    expect("(");
    var id = registry(string("the resource ID"), token);
    expect(")");
    return new ResourceFilter(
        kind,
        ResourceFilter.Mode.INCLUDE,
        List.of(tag ? new ResourceFilter.Tag(id) : new ResourceFilter.Registry(id)));
  }
}
