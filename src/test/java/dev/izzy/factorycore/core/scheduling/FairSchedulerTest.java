package dev.izzy.factorycore.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FairSchedulerTest {
  @Test
  void diagnosticsFailurePreservesOtherQueuedWork() {
    var ran = new AtomicLong();
    var scheduler =
        new FairScheduler(
            LIMITS,
            () -> 0,
            failure -> {
              throw new IllegalStateException("diagnostic sink failed");
            });
    var network = UUID.randomUUID();
    var program = UUID.randomUUID();
    scheduler.submit(
        network,
        program,
        "bad",
        1,
        () -> {
          throw new IllegalStateException("task failed");
        });
    scheduler.submit(network, program, "remaining", 1, ran::incrementAndGet);
    assertThrows(IllegalStateException.class, scheduler::tick);
    assertEquals(1, scheduler.backlog());
    assertEquals(1, scheduler.tick().executed());
    assertEquals(1, ran.get());
  }

  private static final FairScheduler.Limits LIMITS =
      new FairScheduler.Limits(4, 4, 8, 32, 32, 8, 8, 4, 2, 100);

  @Test
  void overloadedNetworksAndProgramsReceiveIndependentFairShares() {
    var executed = new ArrayList<Integer>();
    var scheduler = new FairScheduler(LIMITS, () -> 0, failure -> fail(failure.toString()));
    for (int network = 0; network < 2; network++) {
      for (int program = 0; program < 2; program++) {
        int id = network * 2 + program;
        for (int task = 0; task < 8; task++)
          scheduler.submit(
              new UUID(0, network), new UUID(0, id), "t" + task, 1, () -> executed.add(id));
      }
    }
    var tick = scheduler.tick();
    assertEquals(8, tick.executed());
    assertEquals(8, tick.credits());
    for (int id = 0; id < 4; id++) {
      int expected = id;
      assertEquals(2, executed.stream().filter(value -> value == expected).count());
    }
    assertEquals(24, tick.backlog());
    for (int pass = 0; pass < 3; pass++) scheduler.tick();
    assertEquals(0, scheduler.backlog());
    assertEquals(0, scheduler.tick().visits());
  }

  @Test
  void keyedReplacementDoesNotAccumulateAndCancellationRemovesUndispatchedWork() {
    var scheduler = new FairScheduler(LIMITS, () -> 0, failure -> fail(failure.toString()));
    var network = UUID.randomUUID();
    var program = UUID.randomUUID();
    var result = new AtomicLong();
    assertEquals(
        FairScheduler.Admission.ACCEPTED,
        scheduler.submit(network, program, "stock", 1, () -> result.set(1)));
    assertEquals(
        FairScheduler.Admission.REPLACED,
        scheduler.submit(network, program, "stock", 1, () -> result.set(2)));
    assertEquals(1, scheduler.backlog());
    scheduler.tick();
    assertEquals(2, result.get());
    scheduler.submit(network, program, "cancel", 1, () -> result.set(9));
    assertEquals(1, scheduler.cancelProgram(network, program));
    assertEquals(0, scheduler.tick().visits());
    assertEquals(2, result.get());
    assertEquals(
        FairScheduler.Admission.COST_LIMIT,
        scheduler.submit(network, program, "expensive", 3, () -> result.set(7)));
  }

  @Test
  void hostTimeAndQueueBoundsAreEnforcedAndTaskFailureDoesNotStarveOthers() {
    var clock = new AtomicLong();
    var failures = new ArrayList<FairScheduler.Failure>();
    var scheduler = new FairScheduler(LIMITS, clock::get, failures::add);
    var network = UUID.randomUUID();
    var program = UUID.randomUUID();
    scheduler.submit(network, program, "slow", 1, () -> clock.set(100));
    scheduler.submit(network, program, "later", 1, () -> clock.set(101));
    assertTrue(scheduler.tick().timeLimited());
    assertEquals(1, scheduler.backlog());
    scheduler.tick();
    assertEquals(0, scheduler.backlog());
    scheduler.submit(
        network,
        program,
        "fault",
        1,
        () -> {
          throw new IllegalStateException("broken endpoint");
        });
    scheduler.submit(network, program, "ok", 1, () -> clock.incrementAndGet());
    var report = scheduler.tick();
    assertEquals(1, report.failed());
    assertEquals(2, report.executed());
    assertTrue(failures.getFirst().diagnostic().contains("broken endpoint"));
    for (int task = 0; task < 8; task++)
      scheduler.submit(network, program, "t" + task, 1, () -> {});
    assertEquals(
        FairScheduler.Admission.QUEUE_FULL,
        scheduler.submit(network, program, "overflow", 1, () -> {}));
    assertEquals(8, scheduler.backlog());
  }
}
