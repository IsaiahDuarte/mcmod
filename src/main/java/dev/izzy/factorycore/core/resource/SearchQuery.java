package dev.izzy.factorycore.core.resource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ergonomics Q61: the terminal search language. Words must all match (AND); {@code |} is OR, {@code
 * !} is NOT and parentheses group. A word matches the name or registry ID; {@code "quoted text"}
 * keeps its spaces; {@code @mod}, {@code #tag}, {@code $tooltip} and {@code *id} match only that
 * part; {@code >64}, {@code >=64}, {@code <10}, {@code <=10} and {@code =0} compare the total.
 * Precedence, loosest first: OR, AND, NOT. Parsing never throws: a malformed query keeps its tokens
 * up to the fault, an {@link #error()} code and its position, and matches nothing.
 *
 * <p>Count terms need the summed total, which a search only knows once every source is read, so
 * matching is three-valued: {@link #mayMatch} treats counts as unknown and is used while pages
 * arrive; {@link #matches} settles them with the total.
 */
public final class SearchQuery {
  public static final int MAX_LENGTH = 128, MAX_TERMS = 32, MAX_DEPTH = 8;

  /** Largest count literal, in digits. */
  private static final int MAX_DIGITS = 30;

  public enum TokenKind {
    TEXT,
    QUOTED,
    MOD,
    TAG,
    TOOLTIP,
    ID,
    COUNT,
    NOT,
    OR,
    OPEN,
    CLOSE,
    ERROR
  }

  /** One token: its kind, its span {@code [start, end)} in the text, and its value. */
  public record Token(TokenKind kind, int start, int end, String value) {}

  /** What terms are matched against. Tags and tooltip lines are asked for only when needed. */
  public interface Subject {
    String name();

    String id();

    Iterable<String> tags();

    Iterable<String> tooltip();
  }

  private sealed interface Node permits Term, Count, Not, All, Any {}

  private record Term(TokenKind kind, String text) implements Node {}

  private record Count(String operator, BigInteger amount) implements Node {}

  private record Not(Node node) implements Node {}

  private record All(List<Node> nodes) implements Node {}

  private record Any(List<Node> nodes) implements Node {}

  private static final SearchQuery BLANK = new SearchQuery(List.of(), null, null, -1, false);

  private final List<Token> tokens;
  private final Node root;
  private final String error;
  private final int errorAt;
  private final boolean counts;

  private SearchQuery(List<Token> tokens, Node root, String error, int errorAt, boolean counts) {
    this.tokens = List.copyOf(tokens);
    this.root = root;
    this.error = error;
    this.errorAt = errorAt;
    this.counts = counts;
  }

  public static SearchQuery parse(String text) {
    if (text.isBlank()) return BLANK;
    if (text.length() > MAX_LENGTH)
      return new SearchQuery(List.of(), null, "too_long", MAX_LENGTH, false);
    var tokens = new ArrayList<Token>();
    var fault = lex(text, tokens);
    if (fault != null) return new SearchQuery(tokens, null, fault, tokens.getLast().start(), false);
    var parser = new Parser(tokens, text.length());
    try {
      var root = parser.or(0);
      if (parser.at < tokens.size()) throw parser.fail("unexpected", tokens.get(parser.at).start());
      return new SearchQuery(tokens, root, null, -1, parser.counts);
    } catch (Fault fault2) {
      return new SearchQuery(mark(tokens, fault2.at), null, fault2.code, fault2.at, false);
    }
  }

  /** The tokens, for colouring; after a fault, those from it on are marked ERROR. */
  public List<Token> tokens() {
    return tokens;
  }

  /** Null when the query is well formed; else a short code such as {@code "missing_close"}. */
  public String error() {
    return error;
  }

  /** The character position of the fault, or -1. */
  public int errorAt() {
    return errorAt;
  }

  public boolean blank() {
    return root == null && error == null;
  }

  /** Whether any term compares the total, so the final index must be filtered again. */
  public boolean counts() {
    return counts;
  }

  /** False only when the subject cannot match whatever its total is. */
  public boolean mayMatch(Subject subject) {
    if (error != null) return false;
    return root == null || test(root, subject, null) != Boolean.FALSE;
  }

  /** Whether the subject with this total matches. */
  public boolean matches(Subject subject, BigInteger total) {
    if (error != null) return false;
    return root == null || test(root, subject, total) == Boolean.TRUE;
  }

  /** Kleene three-valued evaluation: null is unknown (a count without a total). */
  private static Boolean test(Node node, Subject subject, BigInteger total) {
    return switch (node) {
      case Term term -> term(term, subject);
      case Count count -> total == null ? null : count(count, total);
      case Not not -> {
        var value = test(not.node(), subject, total);
        yield value == null ? null : !value;
      }
      case All all -> {
        Boolean result = Boolean.TRUE;
        for (var child : all.nodes()) {
          var value = test(child, subject, total);
          if (value == Boolean.FALSE) yield Boolean.FALSE;
          if (value == null) result = null;
        }
        yield result;
      }
      case Any any -> {
        Boolean result = Boolean.FALSE;
        for (var child : any.nodes()) {
          var value = test(child, subject, total);
          if (value == Boolean.TRUE) yield Boolean.TRUE;
          if (value == null) result = null;
        }
        yield result;
      }
    };
  }

  private static boolean term(Term term, Subject subject) {
    String text = term.text();
    return switch (term.kind()) {
      case MOD -> {
        String id = subject.id();
        yield id.substring(0, Math.max(0, id.indexOf(':'))).toLowerCase(Locale.ROOT).contains(text);
      }
      case TAG -> any(subject.tags(), text);
      case TOOLTIP -> any(subject.tooltip(), text);
      case ID -> subject.id().toLowerCase(Locale.ROOT).contains(text);
      default ->
          subject.id().toLowerCase(Locale.ROOT).contains(text)
              || subject.name().toLowerCase(Locale.ROOT).contains(text);
    };
  }

  private static boolean any(Iterable<String> values, String text) {
    for (var value : values) if (value.toLowerCase(Locale.ROOT).contains(text)) return true;
    return false;
  }

  private static boolean count(Count count, BigInteger total) {
    int compared = total.compareTo(count.amount());
    return switch (count.operator()) {
      case ">" -> compared > 0;
      case ">=" -> compared >= 0;
      case "<" -> compared < 0;
      case "<=" -> compared <= 0;
      default -> compared == 0;
    };
  }

  private static List<Token> mark(List<Token> tokens, int at) {
    var marked = new ArrayList<Token>(tokens.size());
    for (var token : tokens)
      marked.add(
          token.end() > at
              ? new Token(TokenKind.ERROR, token.start(), token.end(), token.value())
              : token);
    return marked;
  }

  private static boolean breaks(char c) {
    return Character.isWhitespace(c) || c == '|' || c == '(' || c == ')' || c == '"';
  }

  /** Splits {@code text} into {@code tokens}; returns a fault code (last token is the ERROR). */
  private static String lex(String text, List<Token> tokens) {
    int at = 0, length = text.length();
    while (at < length) {
      char c = text.charAt(at);
      if (Character.isWhitespace(c)) {
        at++;
        continue;
      }
      int start = at;
      switch (c) {
        case '|' -> tokens.add(new Token(TokenKind.OR, at, ++at, "|"));
        case '(' -> tokens.add(new Token(TokenKind.OPEN, at, ++at, "("));
        case ')' -> tokens.add(new Token(TokenKind.CLOSE, at, ++at, ")"));
        case '!' -> tokens.add(new Token(TokenKind.NOT, at, ++at, "!"));
        case '>', '<', '=' -> {
          at++;
          if (c != '=' && at < length && text.charAt(at) == '=') at++;
          String operator = text.substring(start, at);
          int digits = at;
          while (at < length && Character.isDigit(text.charAt(at))) at++;
          if (at == digits
              || at - digits > MAX_DIGITS
              || (at < length && !breaks(text.charAt(at)))) {
            while (at < length && !breaks(text.charAt(at))) at++;
            tokens.add(new Token(TokenKind.ERROR, start, at, text.substring(start, at)));
            return "bad_count";
          }
          tokens.add(new Token(TokenKind.COUNT, start, at, operator + text.substring(digits, at)));
        }
        default -> {
          var kind =
              switch (c) {
                case '@' -> TokenKind.MOD;
                case '#' -> TokenKind.TAG;
                case '$' -> TokenKind.TOOLTIP;
                case '*' -> TokenKind.ID;
                case '"' -> TokenKind.QUOTED;
                default -> TokenKind.TEXT;
              };
          if (kind != TokenKind.TEXT && kind != TokenKind.QUOTED) at++;
          String value;
          if (at < length && text.charAt(at) == '"') {
            int close = text.indexOf('"', at + 1);
            if (close < 0) {
              tokens.add(new Token(TokenKind.ERROR, start, length, text.substring(start)));
              return "unclosed_quote";
            }
            value = text.substring(at + 1, close);
            at = close + 1;
            if (kind == TokenKind.TEXT) kind = TokenKind.QUOTED;
          } else {
            int from = at;
            while (at < length && !breaks(text.charAt(at))) at++;
            value = text.substring(from, at);
          }
          if (value.isBlank()) {
            tokens.add(new Token(TokenKind.ERROR, start, Math.max(at, start + 1), ""));
            return "empty_term";
          }
          tokens.add(new Token(kind, start, at, value.toLowerCase(Locale.ROOT)));
        }
      }
    }
    return null;
  }

  private static final class Fault extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String code;
    final int at;

    Fault(String code, int at) {
      super(code, null, false, false);
      this.code = code;
      this.at = at;
    }
  }

  /** Recursive descent over the tokens, bounded in terms and nesting. */
  private static final class Parser {
    final List<Token> tokens;
    final int end;
    int at, terms;
    boolean counts;

    Parser(List<Token> tokens, int end) {
      this.tokens = tokens;
      this.end = end;
    }

    Fault fail(String code, int position) {
      return new Fault(code, position);
    }

    int here() {
      return at < tokens.size() ? tokens.get(at).start() : end;
    }

    boolean next(TokenKind kind) {
      return at < tokens.size() && tokens.get(at).kind() == kind;
    }

    Node or(int depth) {
      if (depth > MAX_DEPTH) throw fail("too_deep", here());
      var nodes = new ArrayList<Node>();
      nodes.add(and(depth));
      while (next(TokenKind.OR)) {
        at++;
        nodes.add(and(depth));
      }
      return nodes.size() == 1 ? nodes.getFirst() : new Any(nodes);
    }

    Node and(int depth) {
      var nodes = new ArrayList<Node>();
      while (at < tokens.size() && !next(TokenKind.OR) && !next(TokenKind.CLOSE))
        nodes.add(unary(depth));
      if (nodes.isEmpty()) throw fail("empty_group", here());
      return nodes.size() == 1 ? nodes.getFirst() : new All(nodes);
    }

    Node unary(int depth) {
      if (next(TokenKind.NOT)) {
        at++;
        if (at >= tokens.size() || next(TokenKind.OR) || next(TokenKind.CLOSE))
          throw fail("empty_group", here());
        return new Not(unary(depth));
      }
      var token = tokens.get(at);
      if (token.kind() == TokenKind.OPEN) {
        at++;
        var inner = or(depth + 1);
        if (!next(TokenKind.CLOSE)) throw fail("missing_close", token.start());
        at++;
        return inner;
      }
      if (++terms > MAX_TERMS) throw fail("too_many_terms", token.start());
      at++;
      if (token.kind() == TokenKind.COUNT) {
        counts = true;
        String value = token.value();
        int digits = value.charAt(1) == '=' ? 2 : 1;
        return new Count(value.substring(0, digits), new BigInteger(value.substring(digits)));
      }
      return new Term(token.kind(), token.value());
    }
  }
}
