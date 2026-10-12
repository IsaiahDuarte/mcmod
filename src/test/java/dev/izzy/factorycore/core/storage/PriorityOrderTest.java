package dev.izzy.factorycore.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriorityOrderTest {
  @Test
  void globalOrderDoesNotDependOnPageArrivalAndBreaksTiesByStableId() {
    var order = new PriorityOrder();
    var low = new UUID(0, 1);
    var high = new UUID(0, 3);
    var tie = new UUID(0, 2);
    order.add(low, -5);
    order.add(high, 10);
    order.add(tie, 10);
    assertEquals(tie, order.poll());
    assertEquals(high, order.poll());
    assertEquals(low, order.poll());
    assertNull(order.poll());
  }

  @Test
  void repeatedBackingUpdatesOneCandidateInsteadOfDuplicatingStock() {
    var order = new PriorityOrder();
    var id = UUID.randomUUID();
    order.add(id, 0);
    order.add(id, Integer.MIN_VALUE);
    order.add(id, Integer.MAX_VALUE);
    assertEquals(1, order.size());
    assertEquals(id, order.poll());
    assertNull(order.poll());
  }

  @Test
  void quotaRejectsNewIdentityButAllowsExistingPriorityUpdateAndReclaimsAfterPoll() {
    var order = new PriorityOrder();
    for (int index = 0; index < PriorityOrder.MAX_CANDIDATES; index++)
      assertTrue(order.add(new UUID(0, index), 0));
    assertFalse(order.add(new UUID(1, 1), 1));
    assertTrue(order.add(new UUID(0, 1), 9));
    assertEquals(new UUID(0, 1), order.poll());
    assertTrue(order.add(new UUID(1, 1), 1));
    assertEquals(PriorityOrder.MAX_CANDIDATES, order.size());
  }
}
