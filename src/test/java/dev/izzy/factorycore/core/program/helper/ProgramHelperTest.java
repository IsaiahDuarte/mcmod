package dev.izzy.factorycore.core.program.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.Vocabulary;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR 0068 (Q124): the offline helper speaks MCP over stdio with every tool working. */
class ProgramHelperTest {
  private static ProgramHelper helper() throws IOException {
    return new ProgramHelper(
        Vocabulary.read(resource("/data/factorycore/program_vocabulary.txt")),
        resource("/PROGRAM_LANGUAGE.md"));
  }

  private static String resource(String name) throws IOException {
    try (var stream = ProgramHelperTest.class.getResourceAsStream(name)) {
      if (stream == null) throw new IOException("Missing test resource " + name);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String request(int id, String method, Map<String, Object> params) {
    return Json.write(
        Map.of("jsonrpc", "2.0", "id", (double) id, "method", method, "params", params));
  }

  private static Map<?, ?> result(String answer) {
    var message = (Map<?, ?>) Json.parse(answer);
    assertEquals("2.0", message.get("jsonrpc"));
    assertFalse(message.containsKey("error"), answer);
    return (Map<?, ?>) message.get("result");
  }

  private static String text(Map<?, ?> result) {
    var content = (List<?>) result.get("content");
    var first = (Map<?, ?>) content.getFirst();
    assertEquals("text", first.get("type"));
    return (String) first.get("text");
  }

  private static Map<?, ?> call(ProgramHelper helper, String tool, Map<String, Object> arguments) {
    return result(
        helper.handle(request(9, "tools/call", Map.of("name", tool, "arguments", arguments))));
  }

  @Test
  void aClientInitializesListsToolsAndUsesThemOverStdio() throws IOException {
    var in =
        String.join(
                "\n",
                request(
                    1,
                    "initialize",
                    Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1"))),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                request(2, "tools/list", Map.of()),
                request(3, "ping", Map.of()))
            + "\n";
    var out = new ByteArrayOutputStream();
    helper().serve(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out);
    var lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
    assertEquals(3, lines.length, "The notification gets no answer");
    var init = result(lines[0]);
    assertEquals("2025-06-18", init.get("protocolVersion"));
    assertEquals(ProgramHelper.NAME, ((Map<?, ?>) init.get("serverInfo")).get("name"));
    assertTrue(((String) init.get("instructions")).contains("check_program"));
    var tools = (List<?>) result(lines[1]).get("tools");
    assertEquals(
        List.of("language_reference", "list_examples", "check_program", "find_names"),
        tools.stream().map(tool -> ((Map<?, ?>) tool).get("name")).toList());
    for (var tool : tools)
      assertEquals("object", ((Map<?, ?>) ((Map<?, ?>) tool).get("inputSchema")).get("type"));
    assertEquals(Map.of(), result(lines[2]));
  }

  @Test
  void checkProgramReportsProblemsWithHintsAndPinnedIdsWhenOk() throws IOException {
    var helper = helper();
    var good =
        call(
            helper,
            "check_program",
            Map.of(
                "source",
                "every second called ores:\n    move 8 raw materials from storage to furnace top\n"));
    assertFalse(good.containsKey("isError"));
    var report = (Map<?, ?>) Json.parse(text(good));
    assertEquals(true, report.get("ok"));
    var rule = (Map<?, ?>) ((List<?>) report.get("rules")).getFirst();
    assertEquals("ores", rule.get("name"));
    assertEquals(20.0, rule.get("everyTicks"));
    var canonical = (String) report.get("canonical");
    assertTrue(canonical.contains("tag c:raw_materials"), canonical);
    assertTrue(FriendlyCompiler.compile(canonical).ok(), "The pinned form needs no vocabulary");

    var bad =
        call(
            helper,
            "check_program",
            Map.of("source", "every second:\n    move iron ingit from storage to a\n"));
    assertEquals(true, bad.get("isError"));
    var problem =
        (Map<?, ?>) ((List<?>) ((Map<?, ?>) Json.parse(text(bad))).get("problems")).getFirst();
    assertEquals(2.0, problem.get("line"));
    assertEquals("Did you mean iron ingot?", problem.get("hint"));
  }

  @Test
  void referenceExamplesAndNames() throws IOException {
    var helper = helper();
    assertTrue(text(call(helper, "language_reference", Map.of())).contains("## Sentences"));
    var examples = text(call(helper, "list_examples", Map.of()));
    assertTrue(
        examples.contains("## Auto smelter") && examples.contains("keep 8 coal in furnace side"));
    var names = text(call(helper, "find_names", Map.of("query", "oak pl"))).split("\n");
    assertEquals("oak planks", names[0]);
    assertEquals(
        "water",
        text(call(helper, "find_names", Map.of("query", "wat", "kind", "fluid"))).split("\n")[0]);
  }

  @Test
  void badInputGetsJsonRpcErrors() throws IOException {
    var helper = helper();
    assertEquals(
        -32700.0,
        ((Map<?, ?>) ((Map<?, ?>) Json.parse(helper.handle("{oops"))).get("error")).get("code"));
    assertEquals(
        -32601.0,
        ((Map<?, ?>)
                ((Map<?, ?>) Json.parse(helper.handle(request(1, "resources/list", Map.of()))))
                    .get("error"))
            .get("code"));
    assertEquals(
        -32602.0,
        ((Map<?, ?>)
                ((Map<?, ?>)
                        Json.parse(
                            helper.handle(
                                request(
                                    1,
                                    "tools/call",
                                    Map.of("name", "check_program", "arguments", Map.of())))))
                    .get("error"))
            .get("code"));
    assertEquals("\"a\\\"b\\n\\u0001\"", Json.write("a\"b\n\u0001"));
    assertEquals(List.of(1.0, "é", true), Json.parse("[1, \"\\u00e9\", true]"));
  }
}
