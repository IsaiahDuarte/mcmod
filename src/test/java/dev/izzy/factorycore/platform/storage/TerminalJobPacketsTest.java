package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.JobStatus;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerminalJobPacketsTest {
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private static RegistryFriendlyByteBuf buffer() {
    return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
  }

  @Test
  void commandAndViewRoundTripWithinBounds() {
    var id = UUID.randomUUID();
    var command = new TerminalJobPackets.Command(3, id, 9, TerminalJobPackets.PREVIEW, 7, 4096);
    var stick = new TerminalJobPackets.Entry("minecraft:stick", "Stick", "8");
    var view =
        new TerminalJobPackets.View(
            3,
            id,
            9,
            List.of(
                stick,
                new TerminalJobPackets.Entry("minecraft:torch", "Torch", "16", JobStatus.MISSING)),
            40,
            42,
            stick,
            null,
            2,
            3,
            List.of(new TerminalJobPackets.Entry("minecraft:oak_log", "Oak Log", "1")),
            List.of(
                new TerminalJobPackets.PlanRow(
                    new TerminalJobPackets.Entry("minecraft:oak_log", "Oak Log", ""), 0, 0, 1),
                new TerminalJobPackets.PlanRow(stick, 2, 8, 0)),
            3,
            List.of("Coal → Charcoal → Coal"),
            List.of(
                new TerminalJobPackets.Job(stick, 1, 3, CraftingJob.State.RUNNING, null),
                new TerminalJobPackets.Job(
                    stick, 0, 2, CraftingJob.State.WAITING, JobReason.BUSY, "Alex")),
            new TerminalJobPackets.Detail(
                new TerminalJobPackets.Job(
                    stick, 0, 2, CraftingJob.State.WAITING, JobReason.BUSY, "Alex"),
                false,
                List.of(
                    new TerminalJobPackets.StepRow(stick, 1, 1, TerminalJobPackets.StepStatus.DONE),
                    new TerminalJobPackets.StepRow(
                        stick, 0, 1, TerminalJobPackets.StepStatus.WAITING)),
                3),
            JobStatus.PREVIEWED);
    var output = buffer();
    try {
      TerminalJobPackets.Command.CODEC.encode(output, command);
      assertEquals(command, TerminalJobPackets.Command.CODEC.decode(output));
      TerminalJobPackets.View.CODEC.encode(output, view);
      assertEquals(view, TerminalJobPackets.View.CODEC.decode(output));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
  }

  @Test
  void outOfRangeCommandsAndViewsReject() {
    var id = UUID.randomUUID();
    for (int[] bad :
        new int[][] {
          {-1, 0, 1},
          {TerminalJobPackets.NOTIFY + 1, 0, 1},
          {TerminalJobPackets.PREVIEW, TerminalJobPackets.ROWS, 1},
          {TerminalJobPackets.PREVIEW, -1, 1},
          {TerminalJobPackets.PREVIEW, 0, 0},
          {TerminalJobPackets.PREVIEW, 0, 4097},
          {TerminalJobPackets.QUICK, 0, 4097},
          {TerminalJobPackets.TARGET, 0, TerminalJobPackets.MAX_TARGET + 1},
          {TerminalJobPackets.WINDOW, TerminalJobPackets.MAX_OFFSET + 1, 1}
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> new TerminalJobPackets.Command(1, id, 1, bad[0], bad[1], bad[2]));
    // Q64: a target (=N) may name more than one request can make.
    new TerminalJobPackets.Command(
        1, id, 1, TerminalJobPackets.TARGET, 0, TerminalJobPackets.MAX_TARGET);
    new TerminalJobPackets.Command(
        1, id, 1, TerminalJobPackets.QUICK_TARGET, 0, TerminalJobPackets.MAX_TARGET);
    var entry = TerminalJobPackets.NONE;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.View(
                1,
                id,
                1,
                Collections.nCopies(9, entry),
                0,
                9,
                entry,
                null,
                0,
                0,
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                TerminalJobPackets.NO_DETAIL,
                JobStatus.READY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.View(
                1,
                id,
                1,
                List.of(entry),
                5,
                5,
                entry,
                null,
                0,
                0,
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                TerminalJobPackets.NO_DETAIL,
                JobStatus.READY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.View(
                1,
                id,
                1,
                List.of(),
                0,
                0,
                entry,
                null,
                0,
                0,
                List.of(),
                Collections.nCopies(
                    TerminalJobPackets.PLAN_ROWS + 1,
                    new TerminalJobPackets.PlanRow(entry, 0, 0, 0)),
                0,
                List.of(),
                List.of(),
                TerminalJobPackets.NO_DETAIL,
                JobStatus.READY));
    assertThrows(
        IllegalArgumentException.class, () -> new TerminalJobPackets.PlanRow(entry, -1, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.Detail(
                new TerminalJobPackets.Job(entry, 0, 0, CraftingJob.State.RUNNING, null),
                true,
                Collections.nCopies(
                    TerminalJobPackets.DETAIL_STEPS + 1,
                    new TerminalJobPackets.StepRow(
                        entry, 0, 1, TerminalJobPackets.StepStatus.QUEUED)),
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.Job(
                entry, 0, 1, CraftingJob.State.RUNNING, null, "x".repeat(49)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.Command(
                1, id, 1, TerminalJobPackets.DETAIL, TerminalJobPackets.JOBS, 1));
    assertEquals(
        TerminalJobPackets.MAX_OFFSET,
        new TerminalJobPackets.Command(
                1, id, 1, TerminalJobPackets.WINDOW, TerminalJobPackets.MAX_OFFSET, 1)
            .selection());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Job(entry, 4, 3, CraftingJob.State.RUNNING, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Entry("x".repeat(257), "", ""));
  }

  /** M52: every reason, status, state and step value crosses the wire as itself. */
  @Test
  void everyTypedValueRoundTrips() {
    var id = UUID.randomUUID();
    var stick = new TerminalJobPackets.Entry("minecraft:stick", "Stick", "8");
    var reasons = new java.util.ArrayList<JobReason>(List.of(JobReason.values()));
    reasons.add(null);
    var states = CraftingJob.State.values();
    var steps = TerminalJobPackets.StepStatus.values();
    for (var status : JobStatus.values()) {
      var jobs = new java.util.ArrayList<TerminalJobPackets.Job>();
      for (int index = 0; index < reasons.size(); index++)
        if (jobs.size() < TerminalJobPackets.JOBS)
          jobs.add(
              new TerminalJobPackets.Job(
                  stick, 0, 1, states[index % states.length], reasons.get(index)));
      var rows = new java.util.ArrayList<TerminalJobPackets.StepRow>();
      for (var step : steps) rows.add(new TerminalJobPackets.StepRow(stick, 0, 1, step));
      var view =
          new TerminalJobPackets.View(
              1,
              id,
              1,
              List.of(new TerminalJobPackets.Entry("minecraft:stick", "Stick", "8", status)),
              0,
              1,
              stick,
              status,
              0,
              0,
              List.of(),
              List.of(),
              0,
              List.of(),
              jobs,
              new TerminalJobPackets.Detail(jobs.getFirst(), true, rows, 0),
              status);
      var output = buffer();
      try {
        TerminalJobPackets.View.CODEC.encode(output, view);
        assertEquals(view, TerminalJobPackets.View.CODEC.decode(output));
      } finally {
        output.release();
      }
    }
    // Reasons past the first JOBS rows ride in the detail job.
    for (var reason : reasons) {
      var job = new TerminalJobPackets.Job(stick, 0, 1, CraftingJob.State.WAITING, reason);
      var output = buffer();
      try {
        TerminalJobPackets.writeJob(output, job);
        assertEquals(job, TerminalJobPackets.readJob(output));
      } finally {
        output.release();
      }
    }
  }

  /** An unknown key from a mismatched peer is refused, not shown raw or mapped to a default. */
  @Test
  void unknownKeysAreRefused() {
    var output = buffer();
    try {
      output.writeUtf("minecraft:stick", 256);
      output.writeUtf("Stick", 96);
      output.writeUtf("8", 32);
      output.writeUtf("machine_on_fire", 32);
      assertThrows(IllegalArgumentException.class, () -> TerminalJobPackets.Entry.read(output));
    } finally {
      output.release();
    }
    var job = buffer();
    try {
      TerminalJobPackets.Entry.write(job, TerminalJobPackets.NONE);
      job.writeVarLong(0);
      job.writeVarLong(1);
      job.writeUtf("", 32);
      assertThrows(IllegalArgumentException.class, () -> TerminalJobPackets.readJob(job));
    } finally {
      job.release();
    }
    var tooLong = buffer();
    try {
      TerminalJobPackets.Entry.write(tooLong, TerminalJobPackets.NONE);
      tooLong.writeVarLong(0);
      tooLong.writeVarLong(1);
      tooLong.writeUtf("x".repeat(33));
      assertThrows(RuntimeException.class, () -> TerminalJobPackets.readJob(tooLong));
    } finally {
      tooLong.release();
    }
  }
}
