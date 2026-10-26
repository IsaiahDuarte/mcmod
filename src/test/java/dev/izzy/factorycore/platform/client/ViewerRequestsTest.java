package dev.izzy.factorycore.platform.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** M57: a recipe-viewer request reaches only the terminal it was made from. */
class ViewerRequestsTest {
  @Test
  void requestIsTakenOnceByItsOwnTerminal() {
    var requests = new ViewerRequests();
    var terminal = new Object();
    requests.offer(terminal, "minecraft:stick");
    assertNull(requests.take(new Object()), "Another terminal does not take it");
    assertEquals("minecraft:stick", requests.take(terminal));
    assertNull(requests.take(terminal), "Taken once");
  }

  /** The bug: a request left pending when its terminal closed reached a later terminal. */
  @Test
  void requestLeftPendingIsNotCarriedIntoALaterTerminal() {
    var requests = new ViewerRequests();
    var closed = new Object();
    requests.offer(closed, "minecraft:torch");
    requests.drop(closed);
    assertNull(requests.take(closed));
    // Even without a drop, a later menu (which may reuse the container ID) never matches.
    requests.offer(closed, "minecraft:torch");
    assertNull(requests.take(new Object()));
  }

  @Test
  void dropLeavesAnotherTerminalsRequestAndANewerOfferReplacesTheOld() {
    var requests = new ViewerRequests();
    var first = new Object();
    var second = new Object();
    requests.offer(first, "minecraft:stick");
    requests.drop(second);
    requests.offer(second, "minecraft:torch");
    assertNull(requests.take(first));
    assertEquals("minecraft:torch", requests.take(second));
  }
}
