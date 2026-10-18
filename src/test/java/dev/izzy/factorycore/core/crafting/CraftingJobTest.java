package dev.izzy.factorycore.core.crafting;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CraftingJobTest {
  private static final ResourceKey
      LOG = new ResourceKey(ResourceKind.ITEM, "minecraft:oak_log", new byte[0]),
      PLANKS = new ResourceKey(ResourceKind.ITEM, "minecraft:oak_planks", new byte[0]),
      STICK = new ResourceKey(ResourceKind.ITEM, "minecraft:stick", new byte[0]);
  private static final UUID CELL = new UUID(0, 7);
  private static final Pattern PLANK_PATTERN =
      new Pattern(
          new UUID(0, 1), 1, Pattern.Type.CRAFTING, 0, Map.of(LOG, 1L), PLANKS, 4, Map.of());
  private static final Pattern STICK_PATTERN =
      new Pattern(
          new UUID(0, 2), 1, Pattern.Type.CRAFTING, 0, Map.of(PLANKS, 2L), STICK, 4, Map.of());

  private static CraftingJob job() {
    var plan =
        new CraftingPlanner(key -> List.of(PLANK_PATTERN, STICK_PATTERN))
            .plan(STICK, 8, Map.of(LOG, 5L));
    assertEquals(CraftingPlanner.Status.OK, plan.status());
    return CraftingJob.accepted(
        new UUID(0, 9),
        new UUID(0, 10),
        new UUID(0, 11),
        new UUID(0, 11),
        new NodeRegistry.Position("minecraft:overworld", 5),
        plan,
        Map.of(CELL, Map.of(LOG, 1L)));
  }

  @Test
  void acceptedJobRunsStepsInOrderThenDeliversAndFinishesOnlyWhenEmpty() {
    var job = job();
    assertEquals(CraftingJob.State.RUNNING, job.state());
    assertEquals(0, job.current());
    assertEquals(3, job.craftsTotal(), "One plank craft and two stick crafts");
    assertThrows(
        IllegalArgumentException.class, () -> job.crafted(Map.of(LOG, 1L), Map.of(PLANKS, 4L)));
    job.pulled(CELL, LOG, 1);
    assertTrue(job.reserved().isEmpty());
    job.crafted(Map.of(LOG, 1L), Map.of(PLANKS, 4L));
    assertEquals(1, job.current());
    job.crafted(Map.of(PLANKS, 2L), Map.of(STICK, 4L));
    job.waiting("offline");
    assertEquals(CraftingJob.State.WAITING, job.state());
    assertThrows(
        IllegalStateException.class, () -> job.crafted(Map.of(PLANKS, 2L), Map.of(STICK, 4L)));
    job.resume();
    job.crafted(Map.of(PLANKS, 2L), Map.of(STICK, 4L));
    assertEquals(CraftingJob.State.DELIVERING, job.state());
    assertEquals(Map.of(STICK, 8L), job.staged());
    assertFalse(job.finished());
    job.delivered(STICK, 5);
    assertThrows(IllegalArgumentException.class, () -> job.delivered(STICK, 4));
    job.delivered(STICK, 3);
    assertTrue(job.finished());
  }

  @Test
  void cancelKeepsStagedUnitsForDeliveryAndReleasesReservationsExplicitly() {
    var job = job();
    job.cancel();
    assertEquals(CraftingJob.State.CANCELLING, job.state());
    assertFalse(job.finished(), "A reservation is still held");
    assertThrows(IllegalStateException.class, () -> job.crafted(Map.of(), Map.of()));
    job.released(CELL, LOG);
    assertThrows(IllegalArgumentException.class, () -> job.released(CELL, LOG));
    assertTrue(job.finished());
    var partial = job();
    partial.pulled(CELL, LOG, 1);
    partial.crafted(Map.of(LOG, 1L), Map.of(PLANKS, 4L));
    partial.cancel();
    assertEquals(
        Map.of(PLANKS, 4L), partial.staged(), "Consumed log is not restored; planks return");
    partial.delivered(PLANKS, 4);
    assertTrue(partial.finished());
  }

  @Test
  void snapshotRoundTripsAndValidates() {
    var job = job();
    job.pulled(CELL, LOG, 1);
    var restored = new CraftingJob(job.snapshot());
    assertEquals(job.snapshot(), restored.snapshot());
    var s = job.snapshot();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CraftingJob.Snapshot(
                s.id(),
                s.principal(),
                s.network(),
                s.segment(),
                s.origin(),
                s.request(),
                0,
                s.steps(),
                s.reserved(),
                s.staged(),
                s.state(),
                s.reason()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CraftingJob.Snapshot(
                s.id(),
                s.principal(),
                s.network(),
                s.segment(),
                s.origin(),
                s.request(),
                1,
                List.of(),
                s.reserved(),
                s.staged(),
                s.state(),
                s.reason()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CraftingJob.Snapshot(
                s.id(),
                s.principal(),
                s.network(),
                s.segment(),
                s.origin(),
                s.request(),
                1,
                s.steps(),
                Map.of(CELL, Map.of(LOG, 0L)),
                s.staged(),
                s.state(),
                s.reason()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CraftingJob.Step(new UUID(0, 1), 1, PLANKS, 2, 3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CraftingJob.accepted(
                new UUID(0, 1),
                new UUID(0, 2),
                new UUID(0, 3),
                new UUID(0, 3),
                new NodeRegistry.Position("minecraft:overworld", 1),
                new CraftingPlanner(key -> List.of(PLANK_PATTERN, STICK_PATTERN))
                    .plan(STICK, 8, Map.of(LOG, 5L)),
                Map.of(CELL, Map.of(LOG, 2L))),
        "Reservations must equal consumed stock");
  }

  @Test
  void registryBoundsJobsPerWorldAndPrincipalAndRetiresOnlyFinishedJobs() {
    var jobs = new CraftingJobs(List.of(), () -> {});
    var principal = new UUID(0, 10);
    var added = new ArrayList<UUID>();
    for (int i = 0; i < CraftingJobs.MAX_PER_PRINCIPAL; i++) {
      var s = job().snapshot();
      var id = new UUID(1, i);
      jobs.add(
          new CraftingJob(
              new CraftingJob.Snapshot(
                  id,
                  principal,
                  s.network(),
                  s.segment(),
                  s.origin(),
                  s.request(),
                  s.amount(),
                  s.steps(),
                  s.reserved(),
                  s.staged(),
                  s.state(),
                  s.reason())));
      added.add(id);
    }
    assertEquals("principal_limit", jobs.admission(principal));
    assertNull(jobs.admission(new UUID(0, 99)));
    assertThrows(IllegalStateException.class, () -> jobs.retire(added.get(0)));
    var first = jobs.get(added.get(0));
    first.cancel();
    first.released(CELL, LOG);
    jobs.retire(added.get(0));
    assertNull(jobs.get(added.get(0)));
    assertNull(jobs.admission(principal));
  }

  @Test
  void processingDispatchLeasesStagesAndCompletesOnlyFromCollectedOutputs() {
    var ore = new ResourceKey(ResourceKind.ITEM, "minecraft:raw_iron", new byte[0]);
    var ingot = new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);
    var smelt =
        new Pattern(
            new UUID(0, 3), 1, Pattern.Type.PROCESSING, 0, Map.of(ore, 1L), ingot, 1, Map.of());
    var plan = new CraftingPlanner(key -> List.of(smelt)).plan(ingot, 2, Map.of(ore, 2L));
    var job =
        CraftingJob.accepted(
            new UUID(0, 9),
            new UUID(0, 10),
            new UUID(0, 11),
            new UUID(0, 11),
            new NodeRegistry.Position("minecraft:overworld", 5),
            plan,
            Map.of(CELL, Map.of(ore, 2L)));
    var machine = new NodeRegistry.Position("minecraft:overworld", 77);
    job.pulled(CELL, ore, 1);
    job.dispatch(machine);
    assertThrows(
        IllegalStateException.class, () -> job.dispatch(machine), "One dispatch at a time");
    assertThrows(
        IllegalStateException.class, () -> job.crafted(Map.of(ore, 1L), Map.of(ingot, 1L)));
    assertThrows(
        IllegalArgumentException.class, () -> job.processed(Map.of(ore, 1L), Map.of(ingot, 1L)));
    job.inserted(ore, 1);
    assertEquals(0, job.stagedAmount(ore), "Inserted units left the job");
    assertThrows(
        IllegalArgumentException.class, () -> job.processed(Map.of(ore, 1L), Map.of(ingot, 1L)));
    job.collected(ingot, 1);
    job.processed(Map.of(ore, 1L), Map.of(ingot, 1L));
    assertNull(job.dispatch());
    assertEquals(1, job.steps().getFirst().done());
    job.pulled(CELL, ore, 1);
    job.dispatch(machine);
    job.inserted(ore, 1);
    var saved = new CraftingJob(job.snapshot());
    assertEquals(job.snapshot(), saved.snapshot(), "Dispatch persists with the job");
    job.cancel();
    assertFalse(job.finished(), "Cleanup record keeps a cancelled job alive");
    job.collected(ingot, 1);
    job.abandonDispatch();
    job.delivered(ingot, 2);
    assertTrue(job.finished());
  }
}
