package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProgramTemplatesTest {
  @Test
  void everyTemplateCompilesWithAStableNamedRule() {
    assertEquals(7, ProgramTemplates.ALL.size());
    for (var template : ProgramTemplates.ALL) {
      var result = FriendlyCompiler.compile(template.source());
      assertTrue(result.ok(), () -> template.name() + ": " + result.diagnostics());
      assertFalse(result.program().rules().getFirst().key().startsWith("rule-"), template.name());
    }
  }
}
