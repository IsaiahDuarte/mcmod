package dev.izzy.factorycore.core.program.helper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small JSON subset MCP messages need, so the offline helper runs on the JDK alone. Objects
 * read as {@link Map}, arrays as {@link List}, numbers as {@link Double}; nesting and size are
 * bounded by the caller's line limit.
 */
final class Json {
  private static final int MAX_DEPTH = 64;

  private final String text;
  private int at;

  private Json(String text) {
    this.text = text;
  }

  static Object parse(String text) {
    var json = new Json(text);
    var value = json.value(0);
    json.space();
    if (json.at != text.length()) throw json.error("Unexpected text after the value");
    return value;
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at " + at);
  }

  private void space() {
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
  }

  private Object value(int depth) {
    if (depth > MAX_DEPTH) throw error("Too deeply nested");
    space();
    if (at >= text.length()) throw error("Expected a value");
    char c = text.charAt(at);
    return switch (c) {
      case '{' -> object(depth);
      case '[' -> array(depth);
      case '"' -> string();
      case 't' -> word("true", Boolean.TRUE);
      case 'f' -> word("false", Boolean.FALSE);
      case 'n' -> word("null", null);
      default -> number();
    };
  }

  private Object word(String word, Object value) {
    if (!text.startsWith(word, at)) throw error("Expected " + word);
    at += word.length();
    return value;
  }

  private Map<String, Object> object(int depth) {
    var map = new LinkedHashMap<String, Object>();
    at++;
    space();
    if (at < text.length() && text.charAt(at) == '}') {
      at++;
      return map;
    }
    while (true) {
      space();
      if (at >= text.length() || text.charAt(at) != '"') throw error("Expected a key");
      var key = string();
      space();
      if (at >= text.length() || text.charAt(at++) != ':') throw error("Expected ':'");
      map.put(key, value(depth + 1));
      space();
      if (at >= text.length()) throw error("Unclosed object");
      char c = text.charAt(at++);
      if (c == '}') return map;
      if (c != ',') throw error("Expected ',' or '}'");
    }
  }

  private List<Object> array(int depth) {
    var list = new ArrayList<Object>();
    at++;
    space();
    if (at < text.length() && text.charAt(at) == ']') {
      at++;
      return list;
    }
    while (true) {
      list.add(value(depth + 1));
      space();
      if (at >= text.length()) throw error("Unclosed array");
      char c = text.charAt(at++);
      if (c == ']') return list;
      if (c != ',') throw error("Expected ',' or ']'");
    }
  }

  private String string() {
    var out = new StringBuilder();
    at++;
    while (at < text.length()) {
      char c = text.charAt(at++);
      if (c == '"') return out.toString();
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (at >= text.length()) break;
      char e = text.charAt(at++);
      switch (e) {
        case '"', '\\', '/' -> out.append(e);
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          if (at + 4 > text.length()) throw error("Short \\u escape");
          out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
          at += 4;
        }
        default -> throw error("Unknown escape");
      }
    }
    throw error("Unclosed string");
  }

  private Double number() {
    int start = at;
    while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
    if (start == at) throw error("Expected a value");
    try {
      return Double.valueOf(text.substring(start, at));
    } catch (NumberFormatException invalid) {
      throw error("Bad number");
    }
  }

  /** Writes maps, lists, strings, numbers, booleans and null as compact JSON. */
  static String write(Object value) {
    var out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    switch (value) {
      case null -> out.append("null");
      case String text -> quote(text, out);
      case Boolean flag -> out.append(flag);
      case Double number when number == Math.rint(number) && Math.abs(number) < 1e15 ->
          out.append(number.longValue());
      case Number number -> out.append(number);
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
          if (!first) out.append(',');
          first = false;
          quote(String.valueOf(entry.getKey()), out);
          out.append(':');
          write(entry.getValue(), out);
        }
        out.append('}');
      }
      case List<?> list -> {
        out.append('[');
        for (int index = 0; index < list.size(); index++) {
          if (index > 0) out.append(',');
          write(list.get(index), out);
        }
        out.append(']');
      }
      default -> throw new IllegalArgumentException("Not JSON: " + value.getClass());
    }
  }

  private static void quote(String text, StringBuilder out) {
    out.append('"');
    for (int index = 0; index < text.length(); index++) {
      char c = text.charAt(index);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }
}
