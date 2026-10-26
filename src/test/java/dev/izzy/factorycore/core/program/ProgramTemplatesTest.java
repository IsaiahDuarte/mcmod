package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProgramTemplatesTest {
  @Test
  void everyTemplateCompilesWithAStableNamedRule() {
    assertEquals(
        12, ProgramTemplates.ALL.size(), "Seven v1 templates, three v2 (D9), one H9, one H11");
    for (var template : ProgramTemplates.ALL) {
      var result = FriendlyCompiler.compile(template.source());
      assertTrue(result.ok(), () -> template.name() + ": " + result.diagnostics());
      assertFalse(result.program().rules().getFirst().key().startsWith("rule-"), template.name());
    }
  }
}
