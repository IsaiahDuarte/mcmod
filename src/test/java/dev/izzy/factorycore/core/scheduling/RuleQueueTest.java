package dev.izzy.factorycore.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleQueueTest {
  private static List<String> drain(RuleQueue<String> queue, int count) {
    var taken = new ArrayList<String>();
    for (int i = 0; i < count && !queue.isEmpty(); i++) taken.add(queue.poll().command());
    return taken;
  }

  /**
   * M145: the furnace test's shape. A busy rule queues twelve commands every interval, one command
   * drains per tick and the interval is ten ticks, so the busy rule never empties. The rule after
   * it must still run within its first interval and then every interval.
   */
  @Test
  void aRuleBehindABusyRuleRunsEveryInterval() {
    var queue = new RuleQueue<String>(64);
    var busy = new ArrayList<String>();
    for (int i = 0; i < 12; i++) busy.add("busy" + i);
    int outputs = 0;
    for (int interval = 0; interval < 5; interval++) {
      queue.replace("busy", busy);
      queue.replace("output", List.of("output"));
      for (var command : drain(queue, 10)) if (command.equals("output")) outputs++;
    }
    assertEquals(5, outputs, "the output rule runs once per interval");
  }

  @Test
  void rulesTakeTurnsAndEachKeepsItsOwnOrder() {
    var queue = new RuleQueue<String>(64);
    queue.replace("a", List.of("a1", "a2", "a3"));
    queue.replace("b", List.of("b1", "b2"));
    assertEquals(List.of("a1", "b1", "a2", "b2", "a3"), drain(queue, 10));
    assertTrue(queue.isEmpty());
    assertNull(queue.poll());
  }

  @Test
  void aRuleThatRunsAgainReplacesItsBatchAndKeepsItsTurn() {
    var queue = new RuleQueue<String>(64);
    queue.replace("a", List.of("a1", "a2"));
    queue.replace("b", List.of("b1"));
    queue.replace("a", List.of("a3"));
    assertEquals(2, queue.size(), "a's old commands are gone");
    assertEquals(List.of("a3", "b1"), drain(queue, 10), "a was waiting first and stays first");
    queue.replace("b", List.of("b2"));
    queue.replace("b", List.of());
    assertTrue(queue.isEmpty(), "an empty batch clears the rule");
  }

  @Test
  void theQueueIsBoundedAndSaysWhatItDropped() {
    var queue = new RuleQueue<String>(3);
    assertEquals(0, queue.replace("a", List.of("a1", "a2")));
    assertEquals(1, queue.replace("b", List.of("b1", "b2")));
    assertEquals(3, queue.size());
    assertEquals(0, queue.replace("a", List.of("a3")), "replacing frees a's old room");
    assertEquals(2, queue.size());
    queue.clear();
    assertTrue(queue.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new RuleQueue<String>(0));
  }
}
