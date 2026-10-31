package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.program.FriendlyCompiler.Diagnostic;
import dev.izzy.factorycore.core.program.FriendlyCompiler.Result;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compiler for sentence programs (ADR 0068) into {@link ProgramIr}, used through {@link
 * FriendlyCompiler}. Indentation makes blocks; item names resolve through a {@link Vocabulary}.
 * Errors say what was expected, with a hint or an example, and each line reports at most one.
 * Compilation is pure and bounded by the {@link FriendlyCompiler} limits.
 */
final class SentenceCompiler {
  private enum Kind {
    WORD,
    NUMBER,
    STRING,
    SYMBOL,
    END
  }

  private record Token(Kind kind, String text, double number, int line, int column) {
    String word() {
      return kind == Kind.WORD ? text.toLowerCase(Locale.ROOT) : "";
    }
  }

  /** One source line and the lines indented under it. */
  private static final class Node {
    final int number;
    final List<Token> tokens;
    final List<Node> children = new ArrayList<>();

    Node(int number, List<Token> tokens) {
      this.number = number;
      this.tokens = tokens;
    }

    boolean opens() {
      for (var token : tokens) if (token.kind == Kind.SYMBOL && token.text.equals(":")) return true;
      return false;
    }
  }

  private static final class SentenceError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Token at;
    final String hint;
    final String example;

    SentenceError(Token at, String message, String hint, String example) {
      super(message, null, false, false);
      this.at = at;
      this.hint = hint;
      this.example = example;
    }
  }

  public static final Set<String> KEYWORDS =
      Set.of(
          "every",
          "when",
          "called",
          "tick",
          "ticks",
          "second",
          "seconds",
          "minute",
          "minutes",
          "move",
          "from",
          "to",
          "keep",
          "in",
          "craft",
          "if",
          "otherwise",
          "for",
          "each",
          "turn",
          "on",
          "off",
          "set",
          "redstone",
          "say",
          "storage",
          "everything",
          "items",
          "fluids",
          "fluid",
          "energy",
          "except",
          "all",
          "has",
          "is",
          "powered",
          "unpowered",
          "not",
          "and",
          "or",
          "less",
          "more",
          "fewer",
          "than",
          "at",
          "least",
          "most",
          "exactly",
          "no",
          "any",
          "top",
          "bottom",
          "side",
          "up",
          "down",
          "north",
          "south",
          "east",
          "west",
          "slot",
          "slots",
          "tag");

  /** Words that end an item name. */
  private static final Set<String> PHRASE_END = Set.of("from", "to", "in", "except", "and", "or");

  private static final String MOVE_EXAMPLE = "move coal from storage to furnace";
  private static final String KEEP_EXAMPLE = "keep 8 coal in furnace side";
  private static final String IF_EXAMPLE = "if furnace has less than 8 coal:";

  private final Vocabulary vocabulary;
  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private List<Token> tokens = List.of();
  private int position;
  private int statements;
  private int operators;

  private SentenceCompiler(Vocabulary vocabulary) {
    this.vocabulary = vocabulary;
  }

  static Result compile(String source, Vocabulary vocabulary) {
    var compiler = new SentenceCompiler(vocabulary);
    if (source.length() > FriendlyCompiler.MAX_SOURCE)
      return new Result(
          null, List.of(new Diagnostic(1, 1, "Program is longer than 16,384 characters")));
    var roots = compiler.tree(source);
    var rules = new ArrayList<ProgramIr.Rule>();
    var keys = new HashSet<String>();
    for (var root : roots) {
      if (compiler.full()) break;
      try {
        var rule = compiler.rule(root, rules.size() + 1);
        if (!keys.add(rule.key()))
          compiler.error(
              root.tokens.getFirst(),
              "Another rule is already called \"" + rule.key() + "\"",
              "Give each rule its own name after 'called'.",
              null);
        else if (rules.size() == ProgramIr.MAX_RULES)
          compiler.error(root.tokens.getFirst(), "A program has at most 64 rules", null, null);
        else rules.add(rule);
      } catch (SentenceError error) {
        compiler.error(error);
      }
    }
    if (!compiler.diagnostics.isEmpty()) return new Result(null, compiler.diagnostics);
    if (rules.isEmpty())
      return new Result(
          null,
          List.of(
              new Diagnostic(
                  1, 1, "This program has no rules", null, "every second: " + MOVE_EXAMPLE)));
    try {
      return new Result(new ProgramIr.Program(rules), List.of());
    } catch (IllegalArgumentException invalid) {
      return new Result(null, List.of(new Diagnostic(1, 1, invalid.getMessage())));
    }
  }

  // ---- Diagnostics ----

  private boolean full() {
    return diagnostics.size() >= FriendlyCompiler.MAX_DIAGNOSTICS;
  }

  private void error(Token at, String message, String hint, String example) {
    if (!full()) diagnostics.add(new Diagnostic(at.line, at.column, message, hint, example));
  }

  private void error(SentenceError error) {
    error(error.at, error.getMessage(), error.hint, error.example);
  }

  private static SentenceError fail(Token at, String message) {
    return new SentenceError(at, message, null, null);
  }

  private static SentenceError expected(Token at, String what, String example) {
    return new SentenceError(
        at, "I expected " + what + " but found " + describe(at), null, example);
  }

  private static String describe(Token token) {
    return switch (token.kind) {
      case END -> "the end of the line";
      case STRING -> "\"" + token.text + "\"";
      default -> "'" + token.text + "'";
    };
  }

  // ---- Lines and indentation ----

  /** One block while reading lines: the exact indentation of its lines and where they go. */
  private record Frame(String indent, List<Node> lines) {}

  private List<Node> tree(String source) {
    var roots = new ArrayList<Node>();
    var frames = new ArrayDeque<Frame>();
    frames.push(new Frame("", roots));
    boolean skipping = false;
    var lines = source.split("\n", -1);
    for (int index = 0; index < lines.length && !full(); index++) {
      var text = lines[index];
      List<Token> lineTokens;
      try {
        lineTokens = lex(text, index + 1);
      } catch (SentenceError error) {
        error(error);
        continue;
      }
      if (lineTokens.size() == 1) continue;
      int width = 0;
      while (width < text.length() && (text.charAt(width) == ' ' || text.charAt(width) == '\t'))
        width++;
      var indent = text.substring(0, width);
      var first = lineTokens.getFirst();
      // After a misplaced line, skip to the next rule so one mistake gives one error.
      if (skipping && !indent.isEmpty()) continue;
      skipping = false;
      boolean tabsAndSpaces = mixed(frames, indent);
      boolean dedented = false;
      while (frames.size() > 1 && !indent.startsWith(frames.peek().indent)) {
        frames.pop();
        dedented = true;
      }
      var frame = frames.peek();
      var node = new Node(index + 1, lineTokens);
      if (indent.equals(frame.indent)) {
        frame.lines.add(node);
        continue;
      }
      if (!dedented && !frame.lines.isEmpty()) {
        var parent = frame.lines.getLast();
        if (parent.opens()) {
          var block = new Frame(indent, parent.children);
          frames.push(block);
          block.lines.add(node);
          continue;
        }
        error(
            first,
            "This line is indented, but the line above it does not end with ':'",
            "Only lines ending with ':' (every, when, if, otherwise, for each) start a block.",
            null);
      } else
        error(
            first,
            roots.isEmpty()
                ? "A rule starts at the left edge, with no spaces before it"
                : "This line's indentation does not line up with the lines above it",
            tabsAndSpaces
                ? "Use only spaces or only tabs to indent."
                : "Indent every line of a block by the same amount.",
            null);
      skipping = true;
    }
    return roots;
  }

  /** Whether this indentation and the open blocks use both tabs and spaces. */
  private static boolean mixed(ArrayDeque<Frame> frames, String indent) {
    var all = new StringBuilder(indent);
    for (var frame : frames) all.append(frame.indent);
    return all.indexOf("\t") >= 0 && all.indexOf(" ") >= 0;
  }

  private static List<Token> lex(String text, int line) {
    var out = new ArrayList<Token>();
    int index = 0;
    while (index < text.length()) {
      char c = text.charAt(index);
      int column = index + 1;
      if (Character.isWhitespace(c)) {
        index++;
      } else if (c == '#') {
        break;
      } else if (Character.isDigit(c)) {
        int start = index;
        while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        if (index + 1 < text.length()
            && text.charAt(index) == '.'
            && Character.isDigit(text.charAt(index + 1))) {
          index++;
          while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
        }
        var digits = text.substring(start, index);
        double value = digits.length() > 12 ? Double.MAX_VALUE : Double.parseDouble(digits);
        out.add(new Token(Kind.NUMBER, digits, value, line, column));
      } else if (Character.isLetter(c) || c == '_' || c == '*') {
        int start = index;
        while (index < text.length()) {
          char d = text.charAt(index);
          boolean namespace =
              d == ':'
                  && index + 1 < text.length()
                  && (Character.isLetter(text.charAt(index + 1))
                      || text.charAt(index + 1) == '_'
                      || text.charAt(index + 1) == '*');
          if (Character.isLetterOrDigit(d) || d == '_' || d == '*' || d == '/' || namespace)
            index++;
          else break;
        }
        out.add(new Token(Kind.WORD, text.substring(start, index), 0, line, column));
      } else if (c == '"') {
        var value = new StringBuilder();
        index++;
        boolean closed = false;
        while (index < text.length()) {
          char d = text.charAt(index++);
          if (d == '"') {
            closed = true;
            break;
          }
          if (d == '\\' && index < text.length()) {
            char e = text.charAt(index++);
            if (e != '"' && e != '\\')
              throw fail(new Token(Kind.STRING, "", 0, line, index - 1), "Unknown escape \\" + e);
            d = e;
          }
          value.append(d);
          if (value.length() > FriendlyCompiler.MAX_STRING)
            throw fail(
                new Token(Kind.STRING, "", 0, line, column), "Text is longer than 128 characters");
        }
        if (!closed)
          throw new SentenceError(
              new Token(Kind.STRING, "", 0, line, column),
              "This text is missing its closing quote",
              "Put a \" at the end of the text.",
              null);
        out.add(new Token(Kind.STRING, value.toString(), 0, line, column));
      } else {
        String symbol = null;
        for (var candidate : List.of("<=", ">=", "==", ":", "(", ")", "<", ">"))
          if (text.startsWith(candidate, index)) {
            symbol = candidate;
            break;
          }
        if (c == '{' || c == '}')
          throw new SentenceError(
              new Token(Kind.SYMBOL, "", 0, line, column),
              "Programs don't use braces",
              "End a line with ':' and indent the lines that belong to it.",
              "every second:");
        if (symbol == null)
          throw fail(
              new Token(Kind.SYMBOL, "", 0, line, column), "I don't understand '" + c + "' here");
        index += symbol.length();
        out.add(new Token(Kind.SYMBOL, symbol, 0, line, column));
      }
    }
    out.add(new Token(Kind.END, "", 0, line, text.length() + 1));
    return out;
  }

  // ---- Token cursor (one line at a time) ----

  private void begin(Node node) {
    tokens = node.tokens;
    position = 0;
  }

  private Token peek() {
    return tokens.get(position);
  }

  private Token next() {
    var token = tokens.get(position);
    if (token.kind != Kind.END) position++;
    return token;
  }

  private boolean at(String word) {
    var token = peek();
    return token.kind == Kind.SYMBOL ? token.text.equals(word) : token.word().equals(word);
  }

  private boolean atColon() {
    return at(":");
  }

  private Token expect(String word, String example) {
    if (!at(word)) throw expected(peek(), "'" + word + "'", example);
    return next();
  }

  private void endOfLine(String example) {
    if (peek().kind != Kind.END)
      throw new SentenceError(
          peek(),
          "I didn't expect " + describe(peek()) + " here; the sentence was already complete",
          null,
          example);
  }

  private long whole(String what, long min, long max) {
    var token = peek();
    if (token.kind != Kind.NUMBER || token.text.contains("."))
      throw expected(token, "a whole number for " + what, null);
    next();
    if (token.number < min || token.number > max)
      throw fail(
          token,
          capital(what)
              + " must be from "
              + String.format(Locale.ROOT, "%,d", min)
              + " to "
              + String.format(Locale.ROOT, "%,d", max));
    return (long) token.number;
  }

  private static String capital(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  // ---- Rules ----

  private ProgramIr.Rule rule(Node node, int ordinal) {
    begin(node);
    var start = peek();
    String key = "rule-" + ordinal;
    long ticks;
    ProgramIr.Condition when = null;
    if (at("every")) {
      next();
      double count = 1;
      var countToken = peek();
      if (countToken.kind == Kind.NUMBER) {
        next();
        count = countToken.number;
      }
      long unit;
      if (at("tick") || at("ticks")) unit = 1;
      else if (at("second") || at("seconds")) unit = 20;
      else if (at("minute") || at("minutes")) unit = 1_200;
      else throw expected(peek(), "ticks, seconds or minutes", "every 5 seconds:");
      next();
      double exact = count * unit;
      if (exact != Math.rint(exact))
        throw new SentenceError(
            countToken,
            "That interval is not a whole number of ticks",
            "1 second is 20 ticks.",
            null);
      if (exact < ProgramIr.MIN_INTERVAL || exact > ProgramIr.MAX_INTERVAL)
        throw new SentenceError(
            countToken.kind == Kind.NUMBER ? countToken : start,
            "Rules run every half second (10 ticks) to every hour",
            null,
            "every second:");
      ticks = (long) exact;
    } else if (at("when")) {
      next();
      var target = target(Set.of());
      expect("is", "when button is powered:");
      if (at("powered")) when = new ProgramIr.RedstoneCondition(target);
      else if (at("unpowered")) when = new ProgramIr.Not(new ProgramIr.RedstoneCondition(target));
      else throw expected(peek(), "'powered' or 'unpowered'", "when button is powered:");
      next();
      ticks = ProgramIr.MIN_INTERVAL;
    } else
      throw new SentenceError(
          start,
          "A rule starts with 'every' or 'when', not " + describe(start),
          null,
          "every second: " + MOVE_EXAMPLE);
    if (at("called")) {
      next();
      var name = peek();
      if (name.kind != Kind.WORD && name.kind != Kind.STRING)
        throw expected(name, "a name after 'called'", "every second called fuel:");
      next();
      key = name.text;
      if (key.isEmpty() || key.length() > 64) throw fail(name, "Rule names are 1–64 characters");
    }
    var body = body(node, new HashSet<>(), 1, "every second: " + MOVE_EXAMPLE);
    if (when != null) body = List.of(new ProgramIr.Branch(when, body, List.of()));
    return new ProgramIr.Rule(key, (int) ticks, body);
  }

  /**
   * After a block header: either one sentence after the {@code :} on the same line, or the lines
   * indented under it.
   */
  private List<ProgramIr.Statement> body(
      Node node, Set<String> variables, int depth, String example) {
    if (!atColon()) throw expected(peek(), "':' at the end of the line", example);
    var colon = next();
    if (depth > FriendlyCompiler.MAX_DEPTH) throw fail(colon, "Blocks nest at most 8 deep");
    if (peek().kind != Kind.END) {
      if (!node.children.isEmpty())
        throw new SentenceError(
            peek(),
            "This line has a sentence after ':' and indented lines below it",
            "Put the sentence on its own indented line.",
            null);
      var tail = new Node(node.number, tokens.subList(position, tokens.size()));
      return List.of(statementLine(tail, List.of(), variables, depth));
    }
    if (node.children.isEmpty())
      throw new SentenceError(
          colon,
          "Nothing comes after this ':'",
          "Indent the lines that belong to it under this line.",
          null);
    return block(node.children, variables, depth);
  }

  private List<ProgramIr.Statement> block(List<Node> lines, Set<String> variables, int depth) {
    var body = new ArrayList<ProgramIr.Statement>();
    for (int index = 0; index < lines.size() && !full(); index++) {
      var line = lines.get(index);
      try {
        // An 'if' takes the 'otherwise' lines that follow it.
        int otherwise = index + 1;
        begin(line);
        if (at("if"))
          while (otherwise < lines.size() && first(lines.get(otherwise)).equals("otherwise"))
            otherwise++;
        body.add(statementLine(line, lines.subList(index + 1, otherwise), variables, depth));
        index = otherwise - 1;
      } catch (SentenceError error) {
        error(error);
      }
    }
    return body;
  }

  private static String first(Node node) {
    return node.tokens.getFirst().word();
  }

  private ProgramIr.Statement statementLine(
      Node node, List<Node> otherwise, Set<String> variables, int depth) {
    begin(node);
    var start = peek();
    if (++statements > FriendlyCompiler.MAX_STATEMENTS)
      throw fail(start, "A program has at most 512 statements");
    return switch (start.word()) {
      case "move" -> move(variables);
      case "keep" -> keep(variables);
      case "craft" -> craft();
      case "if" -> conditional(node, otherwise, variables, depth);
      case "for" -> loop(node, variables, depth);
      case "turn", "set" -> redstone(variables);
      case "say" -> say();
      case "otherwise" ->
          throw new SentenceError(
              start, "'otherwise' must come right after an 'if' block", null, null);
      default ->
          throw new SentenceError(
              start,
              "I don't know a sentence that starts with " + describe(start),
              "Sentences start with move, keep, craft, if, for each, turn on, turn off, set or"
                  + " say.",
              MOVE_EXAMPLE);
    };
  }

  // ---- Sentences ----

  private ProgramIr.Statement move(Set<String> variables) {
    next();
    long limit = FriendlyCompiler.DEFAULT_LIMIT;
    if (at("all")) {
      next();
      limit = FriendlyCompiler.MAX_LIMIT;
    } else if (peek().kind == Kind.NUMBER)
      limit = whole("the amount", 1, FriendlyCompiler.MAX_LIMIT);
    var filter = stuff("what to move", MOVE_EXAMPLE);
    expect("from", MOVE_EXAMPLE);
    var fromToken = peek();
    var from = place(variables, MOVE_EXAMPLE);
    expect("to", MOVE_EXAMPLE);
    var to = place(variables, MOVE_EXAMPLE);
    endOfLine(MOVE_EXAMPLE);
    if (from instanceof ProgramIr.Storage && to instanceof ProgramIr.Storage)
      throw new SentenceError(
          fromToken,
          "A move needs a machine on at least one side, not storage to storage",
          null,
          MOVE_EXAMPLE);
    return new ProgramIr.Move(filter, from, to, limit);
  }

  private ProgramIr.Statement keep(Set<String> variables) {
    next();
    long amount = whole("the amount to keep", 1, ProgramIr.MAX_AMOUNT);
    var filter = stuff("what to keep", KEEP_EXAMPLE);
    expect("in", KEEP_EXAMPLE);
    var placeToken = peek();
    var place = place(variables, KEEP_EXAMPLE);
    endOfLine(KEEP_EXAMPLE);
    return switch (place) {
      case ProgramIr.Storage storage ->
          throw new SentenceError(
              placeToken,
              "'keep' fills a machine, not storage",
              "To make more when storage runs low, use: if storage has less than 64 oak planks:"
                  + " craft 64 oak planks",
              KEEP_EXAMPLE);
      case ProgramIr.Machine machine -> new ProgramIr.Keep(machine.target(), filter, amount);
      case ProgramIr.MachineAt at -> new ProgramIr.KeepAt(at, filter, amount);
    };
  }

  private ProgramIr.Statement craft() {
    next();
    long amount = whole("the amount to craft", 1, ProgramIr.MAX_AMOUNT);
    var token = peek();
    var filter = stuff("the item to craft", "craft 64 oak planks");
    endOfLine("craft 64 oak planks");
    if (filter.kind() != ResourceKind.ITEM
        || filter.terms().size() != 1
        || !(filter.terms().getFirst() instanceof ResourceFilter.Registry registry))
      throw new SentenceError(
          token, "'craft' needs one item, not a group of items", null, "craft 64 oak planks");
    return new ProgramIr.Craft(registry.id(), amount);
  }

  private ProgramIr.Statement say() {
    next();
    var token = peek();
    if (token.kind != Kind.STRING) throw expected(token, "text in quotes", "say \"Low on coal\"");
    next();
    endOfLine("say \"Low on coal\"");
    return new ProgramIr.Log(token.text);
  }

  private ProgramIr.Statement redstone(Set<String> variables) {
    var verb = next();
    String example = "turn on alarm";
    int strength;
    String label;
    if (verb.word().equals("turn")) {
      boolean on;
      if (at("on")) on = true;
      else if (at("off")) on = false;
      else throw expected(peek(), "'on' or 'off'", example);
      next();
      label = label(variables, example);
      strength = on ? 15 : 0;
    } else {
      example = "set alarm redstone to 8";
      label = label(variables, example);
      expect("redstone", example);
      expect("to", example);
      strength = (int) whole("the redstone strength", 0, 15);
    }
    endOfLine(example);
    return new ProgramIr.Emit(label, strength);
  }

  private String label(Set<String> variables, String example) {
    var token = peek();
    var target = target(variables);
    if (target instanceof ProgramIr.Label label) return label.label();
    throw new SentenceError(
        token,
        "Redstone is set by label, not by a 'for each' name",
        "Use the label of the Signal Interfaces.",
        example);
  }

  private ProgramIr.Statement loop(Node node, Set<String> variables, int depth) {
    var example = "for each furnace in furnaces:";
    next();
    expect("each", example);
    var name = peek();
    if (name.kind != Kind.WORD || KEYWORDS.contains(name.word()))
      throw new SentenceError(
          name,
          name.kind == Kind.WORD
              ? "'" + name.text + "' is a word of the language; pick another name"
              : "I expected a name after 'for each' but found " + describe(name),
          null,
          example);
    next();
    if (variables.contains(name.text))
      throw fail(name, "'" + name.text + "' is already used by an outer 'for each'");
    expect("in", example);
    var labelToken = peek();
    var target = target(Set.of());
    if (!(target instanceof ProgramIr.Label label))
      throw fail(labelToken, "'for each' goes over a label");
    var inner = new HashSet<>(variables);
    inner.add(name.text);
    return new ProgramIr.For(name.text, label.label(), body(node, inner, depth + 1, example));
  }

  private ProgramIr.Statement conditional(
      Node node, List<Node> otherwise, Set<String> variables, int depth) {
    next();
    operators = 0;
    var condition = check(variables);
    var then = body(node, variables, depth + 1, IF_EXAMPLE);
    if (otherwise.isEmpty()) {
      if (condition instanceof ProgramIr.StockCondition stock)
        return new ProgramIr.If(stock.filter(), stock.comparison(), stock.amount(), then);
      return new ProgramIr.Branch(condition, then, List.of());
    }
    var line = otherwise.getFirst();
    begin(line);
    next();
    List<ProgramIr.Statement> rest;
    if (at("if")) {
      if (depth + 1 > FriendlyCompiler.MAX_DEPTH) throw fail(peek(), "Blocks nest at most 8 deep");
      if (++statements > FriendlyCompiler.MAX_STATEMENTS)
        throw fail(peek(), "A program has at most 512 statements");
      rest =
          List.of(conditional(line, otherwise.subList(1, otherwise.size()), variables, depth + 1));
    } else {
      if (otherwise.size() > 1)
        throw new SentenceError(
            otherwise.get(1).tokens.getFirst(),
            "Only 'otherwise if' lines may come before the last 'otherwise'",
            null,
            null);
      rest = body(line, variables, depth + 1, "otherwise:");
    }
    return new ProgramIr.Branch(condition, then, rest);
  }

  // ---- Conditions ----

  private void operator(Token at) {
    if (++operators > ProgramIr.MAX_OPERATORS)
      throw fail(at, "A condition has at most 16 and/or/not words");
  }

  private ProgramIr.Condition check(Set<String> variables) {
    var left = conjunction(variables);
    while (at("or")) {
      operator(next());
      left = new ProgramIr.Or(left, conjunction(variables));
    }
    return left;
  }

  private ProgramIr.Condition conjunction(Set<String> variables) {
    var left = part(variables);
    while (at("and")) {
      operator(next());
      left = new ProgramIr.And(left, part(variables));
    }
    return left;
  }

  private ProgramIr.Condition part(Set<String> variables) {
    if (at("not")) {
      operator(next());
      return new ProgramIr.Not(part(variables));
    }
    if (at("(")) {
      next();
      var inner = check(variables);
      expect(")", null);
      return inner;
    }
    if (at("storage")) {
      next();
      expect("has", "if storage has less than 64 oak planks:");
      var comparison = comparison();
      return new ProgramIr.StockCondition(
          stuff("what storage has", IF_EXAMPLE), comparison.comparison, comparison.amount);
    }
    var token = peek();
    if (token.kind != Kind.WORD && token.kind != Kind.STRING)
      throw expected(token, "a check, like 'furnace has less than 8 coal'", IF_EXAMPLE);
    var target = target(variables);
    if (at("is")) {
      next();
      if (at("powered")) {
        next();
        return new ProgramIr.RedstoneCondition(target);
      }
      if (at("unpowered")) {
        operator(next());
        return new ProgramIr.Not(new ProgramIr.RedstoneCondition(target));
      }
      throw expected(peek(), "'powered' or 'unpowered'", "if button is powered:");
    }
    if (!at("has")) {
      if (isFace(peek().word()) || at("slot") || at("slots"))
        throw new SentenceError(
            peek(),
            "A 'has' check counts the whole machine; it cannot name a side or slots",
            null,
            IF_EXAMPLE);
      throw expected(peek(), "'has' or 'is'", IF_EXAMPLE);
    }
    next();
    var comparison = comparison();
    return new ProgramIr.HasCondition(
        target,
        comparison.comparison,
        comparison.amount,
        stuff("what the machine has", IF_EXAMPLE));
  }

  private record Compare(ProgramIr.Comparison comparison, long amount) {}

  private Compare comparison() {
    var token = peek();
    if (at("no")) {
      next();
      return new Compare(ProgramIr.Comparison.EQUAL, 0);
    }
    if (at("any")) {
      next();
      return new Compare(ProgramIr.Comparison.GREATER, 0);
    }
    ProgramIr.Comparison comparison;
    if (token.kind == Kind.SYMBOL) {
      comparison =
          switch (token.text) {
            case "<" -> ProgramIr.Comparison.LESS;
            case "<=" -> ProgramIr.Comparison.LESS_EQUAL;
            case ">" -> ProgramIr.Comparison.GREATER;
            case ">=" -> ProgramIr.Comparison.GREATER_EQUAL;
            case "==" -> ProgramIr.Comparison.EQUAL;
            default -> throw comparisonError(token);
          };
      next();
    } else if (at("less") || at("fewer") || at("more")) {
      comparison = at("more") ? ProgramIr.Comparison.GREATER : ProgramIr.Comparison.LESS;
      next();
      expect("than", IF_EXAMPLE);
    } else if (at("at")) {
      next();
      if (at("least")) comparison = ProgramIr.Comparison.GREATER_EQUAL;
      else if (at("most")) comparison = ProgramIr.Comparison.LESS_EQUAL;
      else throw expected(peek(), "'least' or 'most'", "if furnace has at least 8 coal:");
      next();
    } else if (at("exactly")) {
      next();
      comparison = ProgramIr.Comparison.EQUAL;
    } else throw comparisonError(token);
    return new Compare(comparison, whole("the amount", 0, ProgramIr.MAX_AMOUNT));
  }

  private static SentenceError comparisonError(Token token) {
    return new SentenceError(
        token,
        "I expected a comparison but found " + describe(token),
        "Use less than, more than, at least, at most, exactly, no or any.",
        IF_EXAMPLE);
  }

  // ---- Places ----

  private static boolean isFace(String word) {
    return switch (word) {
      case "top", "bottom", "side", "up", "down", "north", "south", "east", "west" -> true;
      default -> false;
    };
  }

  private ProgramIr.Target target(Set<String> variables) {
    var token = peek();
    if (token.kind == Kind.STRING) {
      next();
      if (token.text.isEmpty() || token.text.length() > 64)
        throw fail(token, "Machine labels are 1–64 characters");
      return new ProgramIr.Label(token.text);
    }
    // Labels sit in fixed places, so any word but 'storage' can be one (even 'top' or 'powered').
    if (token.kind != Kind.WORD || token.word().equals("storage") || token.text.contains(":"))
      throw new SentenceError(
          token,
          "I expected a machine label but found " + describe(token),
          "Write the label you gave the machine with the Label Tool, in quotes if it has spaces.",
          null);
    next();
    if (token.text.length() > 64) throw fail(token, "Machine labels are 1–64 characters");
    return variables.contains(token.text)
        ? new ProgramIr.Variable(token.text)
        : new ProgramIr.Label(token.text);
  }

  private ProgramIr.Place place(Set<String> variables, String example) {
    if (at("storage")) {
      next();
      if (isFace(peek().word()) || at("slot") || at("slots"))
        throw new SentenceError(
            peek(), "Storage has no sides or slots; only a machine does", null, example);
      return new ProgramIr.Storage();
    }
    var target = target(variables);
    ProgramIr.Side side = null;
    if (isFace(peek().word())) {
      side =
          switch (next().word()) {
            case "top", "up" -> ProgramIr.Side.UP;
            case "bottom", "down" -> ProgramIr.Side.DOWN;
            case "side", "north" -> ProgramIr.Side.NORTH;
            case "south" -> ProgramIr.Side.SOUTH;
            case "east" -> ProgramIr.Side.EAST;
            default -> ProgramIr.Side.WEST;
          };
    }
    int first = -1, last = -1;
    if (at("slot")) {
      next();
      first = (int) whole("the slot number", 0, ProgramIr.MAX_SLOT);
      last = first;
    } else if (at("slots")) {
      next();
      first = (int) whole("the first slot", 0, ProgramIr.MAX_SLOT);
      last = first;
      // "slots 0 to 3": a number after 'to' continues the range; a label ends the place.
      if (at("to") && tokens.get(position + 1).kind == Kind.NUMBER) {
        next();
        var token = peek();
        last = (int) whole("the last slot", 0, ProgramIr.MAX_SLOT);
        if (last < first) throw fail(token, "A slot range runs from the lower slot to the higher");
      }
    }
    return side == null && first < 0
        ? new ProgramIr.Machine(target)
        : new ProgramIr.MachineAt(target, side, first, last);
  }

  // ---- What to move: names, groups and patterns ----

  private ResourceFilter stuff(String what, String example) {
    var token = peek();
    if (at("energy")) {
      next();
      if (at("except")) throw fail(peek(), "energy cannot take except");
      return new ResourceFilter(
          ResourceKind.ENERGY,
          ResourceFilter.Mode.INCLUDE,
          List.of(new ResourceFilter.Registry(ResourceKey.ENERGY.registryId())));
    }
    var first = term(what, example);
    if (!at("except"))
      return new ResourceFilter(first.kind, ResourceFilter.Mode.INCLUDE, List.of(first.term));
    var excluded = new ArrayList<ResourceFilter.Term>();
    while (at("except")) {
      var keyword = next();
      if (excluded.size() == ResourceFilter.Except.MAX_EXCLUDED)
        throw fail(keyword, "A filter has at most 8 terms");
      var start = peek();
      var next = term("what to leave out", example);
      if (next.kind != first.kind)
        throw fail(start, "Everything after 'except' must be the same kind (items or fluids)");
      excluded.add(next.term);
    }
    if (token.kind == Kind.END) throw expected(token, what, example);
    return new ResourceFilter(
        first.kind,
        ResourceFilter.Mode.INCLUDE,
        List.of(new ResourceFilter.Except(first.term, excluded)));
  }

  private record Term(ResourceKind kind, ResourceFilter.Term term) {}

  private Term term(String what, String example) {
    if (at("everything") || at("items")) {
      next();
      return new Term(ResourceKind.ITEM, new ResourceFilter.Glob("*"));
    }
    if (at("fluids")) {
      next();
      return new Term(ResourceKind.FLUID, new ResourceFilter.Glob("*"));
    }
    if (at("tag")) {
      next();
      var token = peek();
      if (token.kind != Kind.WORD && token.kind != Kind.STRING)
        throw expected(
            token, "a tag name after 'tag'", "move tag c:ingots from furnace to storage");
      next();
      var id = token.text.toLowerCase(Locale.ROOT);
      if (id.indexOf('*') >= 0) throw fail(token, "A tag name cannot use '*'");
      // Without a mod name: the common (c:) tag when there is one, else Minecraft's.
      if (id.indexOf(':') < 0)
        id = (vocabulary.itemTags().contains("c:" + id) ? "c:" : "minecraft:") + id;
      registry(id, token);
      return new Term(ResourceKind.ITEM, new ResourceFilter.Tag(id));
    }
    var kind = ResourceKind.ITEM;
    if (at("fluid")) {
      next();
      kind = ResourceKind.FLUID;
    }
    var start = peek();
    var words = new ArrayList<String>();
    if (start.kind == Kind.STRING) {
      next();
      words.add(start.text.strip().toLowerCase(Locale.ROOT));
    } else
      while (peek().kind == Kind.WORD && !PHRASE_END.contains(peek().word()))
        words.add(next().word());
    if (words.isEmpty() || words.getFirst().isEmpty()) throw expected(start, what, example);
    return new Term(kind, resolve(String.join(" ", words), kind, start));
  }

  /**
   * What {@code phrase} names under {@code vocabulary}, or null when it names nothing or more than
   * one thing. The printer uses it to check that a friendly name reads back exactly.
   */
  static ResourceFilter.Term name(String phrase, ResourceKind kind, Vocabulary vocabulary) {
    try {
      return new SentenceCompiler(vocabulary)
          .resolve(phrase, kind, new Token(Kind.WORD, phrase, 0, 1, 1));
    } catch (SentenceError unknown) {
      return null;
    }
  }

  /** ADR 0068 name resolution: ID path, then English name, then common tag, then a guess list. */
  private ResourceFilter.Term resolve(String phrase, ResourceKind kind, Token at) {
    var path = phrase.replace(' ', '_');
    if (path.indexOf('*') >= 0) return glob(path, at);
    if (path.indexOf(':') >= 0) {
      if (kind == ResourceKind.ITEM
          && !vocabulary.items().contains(path)
          && vocabulary.itemTags().contains(path)) return new ResourceFilter.Tag(path);
      if (!vocabulary.empty()
          && !(kind == ResourceKind.ITEM ? vocabulary.items() : vocabulary.fluids()).contains(path))
        throw unknown(phrase, kind, at);
      return registry(path, at);
    }
    if (vocabulary.empty()) return registry("minecraft:" + path, at);
    var ids = kind == ResourceKind.ITEM ? vocabulary.items() : vocabulary.fluids();
    for (var candidate : singular(path)) {
      var match = byPath(ids, candidate, at);
      if (match != null) return new ResourceFilter.Registry(match);
    }
    if (kind == ResourceKind.ITEM) {
      for (var candidate : singular(path)) {
        var named = vocabulary.names().get(candidate.replace('_', ' '));
        if (named != null) return new ResourceFilter.Registry(named);
      }
      for (var namespace : List.of("c:", "minecraft:"))
        if (vocabulary.itemTags().contains(namespace + path))
          return new ResourceFilter.Tag(namespace + path);
    }
    throw unknown(phrase, kind, at);
  }

  /** The phrase as written, then with a plural ending removed. */
  private static List<String> singular(String path) {
    var forms = new ArrayList<String>();
    forms.add(path);
    if (path.endsWith("ies")) forms.add(path.substring(0, path.length() - 3) + "y");
    if (path.endsWith("es")) forms.add(path.substring(0, path.length() - 2));
    if (path.endsWith("s")) forms.add(path.substring(0, path.length() - 1));
    return forms;
  }

  /** The one ID with this path: Minecraft's first, else the only mod that has it. */
  private static String byPath(Set<String> ids, String path, Token at) {
    var vanilla = "minecraft:" + path;
    if (ids.contains(vanilla)) return vanilla;
    var matches =
        ids.stream().filter(id -> id.substring(id.indexOf(':') + 1).equals(path)).sorted().toList();
    if (matches.size() > 1)
      throw new SentenceError(
          at,
          "More than one mod has '" + path.replace('_', ' ') + "'",
          "Write the one you mean with its mod: "
              + String.join(" or ", matches.subList(0, Math.min(3, matches.size())))
              + ".",
          null);
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private SentenceError unknown(String phrase, ResourceKind kind, Token at) {
    var names = new ArrayList<String>();
    for (var id : kind == ResourceKind.ITEM ? vocabulary.items() : vocabulary.fluids())
      names.add(id.substring(id.indexOf(':') + 1).replace('_', ' '));
    if (kind == ResourceKind.ITEM) {
      names.addAll(vocabulary.names().keySet());
      for (var tag : vocabulary.itemTags())
        if (tag.startsWith("c:")) names.add(tag.substring(2).replace('_', ' '));
    }
    int limit = Math.max(2, phrase.length() / 3);
    var close =
        names.stream()
            .distinct()
            .map(name -> java.util.Map.entry(name, distance(phrase, name, limit)))
            .filter(entry -> entry.getValue() <= limit)
            .sorted(
                Comparator.comparing(java.util.Map.Entry<String, Integer>::getValue)
                    .thenComparing(java.util.Map.Entry::getKey))
            .limit(3)
            .map(java.util.Map.Entry::getKey)
            .toList();
    String hint = null;
    if (!close.isEmpty())
      hint =
          "Did you mean "
              + (close.size() == 1
                  ? close.getFirst()
                  : String.join(", ", close.subList(0, close.size() - 1))
                      + " or "
                      + close.getLast())
              + "?";
    return new SentenceError(
        at,
        "I don't know "
            + (kind == ResourceKind.ITEM ? "an item" : "a fluid")
            + " called '"
            + phrase
            + "'",
        hint,
        null);
  }

  /** Edit distance, giving up (returning {@code limit + 1}) once it cannot be within the limit. */
  private static int distance(String a, String b, int limit) {
    if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
    var previous = new int[b.length() + 1];
    var current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) previous[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      int best = current[0];
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
        best = Math.min(best, current[j]);
      }
      if (best > limit) return limit + 1;
      var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  private static ResourceFilter.Term glob(String text, Token at) {
    if (text.chars().filter(c -> c == '*').count() > 1)
      throw fail(at, "A pattern may use at most one '*'");
    int colon = text.indexOf(':');
    if (colon >= 0 && text.substring(0, colon).indexOf('*') >= 0)
      throw fail(at, "'*' goes after the mod name, not in it");
    try {
      return new ResourceFilter.Glob(text);
    } catch (IllegalArgumentException invalid) {
      throw fail(at, "'" + text + "' is not a valid pattern");
    }
  }

  private static ResourceFilter.Term registry(String id, Token at) {
    try {
      new ResourceKey(ResourceKind.ITEM, id, new byte[0]);
    } catch (IllegalArgumentException invalid) {
      throw fail(at, "'" + id + "' is not a valid ID");
    }
    return new ResourceFilter.Registry(id);
  }
}
