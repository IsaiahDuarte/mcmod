package dev.izzy.factorycore.core.program.helper;

import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramSuggestions;
import dev.izzy.factorycore.core.program.ProgramTemplates;
import dev.izzy.factorycore.core.program.Vocabulary;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The offline program helper (ADR 0068): an MCP server over stdio for AI clients writing Factory
 * Core programs. It needs no game, network or world: it compiles with the bundled vocabulary (or
 * the file named by {@code FACTORYCORE_VOCABULARY}) and only reads its inputs. Messages are
 * newline-delimited JSON-RPC 2.0, at most {@link #MAX_LINE} characters each.
 */
public final class ProgramHelper {
  public static final String NAME = "factorycore-program-helper";
  static final int MAX_LINE = 1 << 20;
  private static final String PROTOCOL = "2025-06-18";
  private static final int MAX_NAMES = 25;

  private static final String INSTRUCTIONS =
      "Writes programs for the Minecraft mod Factory Core. Programs are plain sentences: rules"
          + " start with 'every …:' or 'when … is powered:', and indented lines move, keep or"
          + " craft items. Read language_reference first, use find_names for exact item names,"
          + " and call check_program until it reports ok. Machine labels are whatever the player"
          + " set with the Label Tool; ask for them.";

  private final Vocabulary vocabulary;
  private final String reference;

  public ProgramHelper(Vocabulary vocabulary, String reference) {
    this.vocabulary = vocabulary;
    this.reference = reference;
  }

  public static void main(String[] args) throws IOException {
    var vocabularyFile = System.getenv("FACTORYCORE_VOCABULARY");
    String vocabulary =
        vocabularyFile == null || vocabularyFile.isBlank()
            ? resource("/data/factorycore/program_vocabulary.txt")
            : Files.readString(Path.of(vocabularyFile), StandardCharsets.UTF_8);
    new ProgramHelper(Vocabulary.read(vocabulary), resource("/PROGRAM_LANGUAGE.md"))
        .serve(System.in, System.out);
  }

  private static String resource(String name) throws IOException {
    try (var stream = ProgramHelper.class.getResourceAsStream(name)) {
      if (stream == null) throw new IOException("Missing " + name + " in the helper jar");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Answers one request per line until the input ends; notifications get no answer. */
  public void serve(InputStream in, OutputStream out) throws IOException {
    var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    var writer = new PrintStream(out, true, StandardCharsets.UTF_8);
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) continue;
      var answer = handle(line);
      if (answer != null) writer.println(answer);
    }
  }

  /** The response to one JSON-RPC message, or null for a notification. */
  public String handle(String line) {
    if (line.length() > MAX_LINE) return error(null, -32600, "Message too long");
    Map<?, ?> message;
    try {
      if (!(Json.parse(line) instanceof Map<?, ?> map)) return error(null, -32600, "Not a request");
      message = map;
    } catch (IllegalArgumentException invalid) {
      return error(null, -32700, "Parse error: " + invalid.getMessage());
    }
    var id = message.get("id");
    var method = message.get("method");
    if (!(method instanceof String name)) return error(id, -32600, "No method");
    if (!message.containsKey("id")) return null;
    var params = message.get("params") instanceof Map<?, ?> map ? map : Map.of();
    try {
      return switch (name) {
        case "initialize" -> result(id, initialize(params));
        case "ping" -> result(id, Map.of());
        case "tools/list" -> result(id, Map.of("tools", tools()));
        case "tools/call" -> result(id, call(params));
        default -> error(id, -32601, "Unknown method " + name);
      };
    } catch (IllegalArgumentException invalid) {
      return error(id, -32602, invalid.getMessage());
    }
  }

  private Map<String, Object> initialize(Map<?, ?> params) {
    var version =
        params.get("protocolVersion") instanceof String asked && !asked.isBlank()
            ? asked
            : PROTOCOL;
    var answer = new LinkedHashMap<String, Object>();
    answer.put("protocolVersion", version);
    answer.put("capabilities", Map.of("tools", Map.of()));
    answer.put("serverInfo", Map.of("name", NAME, "version", "1"));
    answer.put("instructions", INSTRUCTIONS);
    return answer;
  }

  private static List<Object> tools() {
    return List.of(
        tool(
            "language_reference",
            "The Factory Core program language: rule shapes, sentences, names, checks and limits,"
                + " with examples. Read this before writing a program.",
            Map.of()),
        tool(
            "list_examples",
            "The built-in example programs (the editor's templates), by name.",
            Map.of()),
        tool(
            "check_program",
            "Compiles a program and returns ok, or each problem with its line, column, hint and"
                + " an example; when ok, also the rules and the program with every name as its ID.",
            Map.of("source", Map.of("type", "string", "description", "The whole program text"))),
        tool(
            "find_names",
            "Finds how a program names items, item groups (tags) or fluids, closest first. Use it"
                + " to get exact names before writing them.",
            Map.of(
                "query",
                Map.of("type", "string", "description", "Part of a name, like 'iron' or 'planks'"),
                "kind",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("item", "fluid"),
                    "description",
                    "item (default) or fluid"))));
  }

  private static Map<String, Object> tool(
      String name, String description, Map<String, Object> properties) {
    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (properties.containsKey("source")) schema.put("required", List.of("source"));
    if (properties.containsKey("query")) schema.put("required", List.of("query"));
    var tool = new LinkedHashMap<String, Object>();
    tool.put("name", name);
    tool.put("description", description);
    tool.put("inputSchema", schema);
    return tool;
  }

  private Map<String, Object> call(Map<?, ?> params) {
    var arguments = params.get("arguments") instanceof Map<?, ?> map ? map : Map.of();
    if (!(params.get("name") instanceof String name))
      throw new IllegalArgumentException("tools/call needs a tool name");
    return switch (name) {
      case "language_reference" -> text(reference, false);
      case "list_examples" -> {
        var out = new StringBuilder();
        for (var template : ProgramTemplates.ALL)
          out.append("## ")
              .append(template.name())
              .append("\n\n")
              .append(template.source())
              .append('\n');
        yield text(out.toString(), false);
      }
      case "check_program" -> check(string(arguments, "source"));
      case "find_names" -> {
        var kind = "fluid".equals(arguments.get("kind")) ? ResourceKind.FLUID : ResourceKind.ITEM;
        var found =
            ProgramSuggestions.match(
                string(arguments, "query"), ProgramSuggestions.names(vocabulary, kind), MAX_NAMES);
        yield text(found.isEmpty() ? "No names match." : String.join("\n", found), false);
      }
      default -> throw new IllegalArgumentException("Unknown tool " + name);
    };
  }

  private static String string(Map<?, ?> arguments, String key) {
    if (!(arguments.get(key) instanceof String value))
      throw new IllegalArgumentException("'" + key + "' must be text");
    if (value.length() > FriendlyCompiler.MAX_SOURCE)
      throw new IllegalArgumentException("'" + key + "' is longer than 16,384 characters");
    return value;
  }

  private Map<String, Object> check(String source) {
    var result = FriendlyCompiler.compile(source, vocabulary);
    var answer = new LinkedHashMap<String, Object>();
    answer.put("ok", result.ok());
    if (result.ok()) {
      var rules = new ArrayList<Object>();
      for (var rule : result.program().rules())
        rules.add(
            Map.of(
                "name", rule.key(),
                "everyTicks", (double) rule.interval(),
                "sentences", (double) rule.body().size()));
      answer.put("rules", rules);
      answer.put("canonical", FriendlyCompiler.canonical(result.program()));
    } else {
      var problems = new ArrayList<Object>();
      for (var diagnostic : result.diagnostics()) {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("line", (double) diagnostic.line());
        problem.put("column", (double) diagnostic.column());
        problem.put("message", diagnostic.message());
        if (diagnostic.hint() != null) problem.put("hint", diagnostic.hint());
        if (diagnostic.example() != null) problem.put("example", diagnostic.example());
        problems.add(problem);
      }
      answer.put("problems", problems);
    }
    return text(Json.write(answer), !result.ok());
  }

  private static Map<String, Object> text(String text, boolean error) {
    var answer = new LinkedHashMap<String, Object>();
    answer.put("content", List.of(Map.of("type", "text", "text", text)));
    if (error) answer.put("isError", true);
    return answer;
  }

  private static String result(Object id, Object result) {
    var answer = new LinkedHashMap<String, Object>();
    answer.put("jsonrpc", "2.0");
    answer.put("id", id);
    answer.put("result", result);
    return Json.write(answer);
  }

  private static String error(Object id, int code, String message) {
    var answer = new LinkedHashMap<String, Object>();
    answer.put("jsonrpc", "2.0");
    answer.put("id", id);
    answer.put("error", Map.of("code", (double) code, "message", message));
    return Json.write(answer);
  }
}
