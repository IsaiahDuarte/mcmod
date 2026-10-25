package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

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
                stick, new TerminalJobPackets.Entry("minecraft:torch", "Torch", "16", "missing")),
            40,
            42,
            stick,
            "",
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
                new TerminalJobPackets.Job(stick, 1, 3, "running", ""),
                new TerminalJobPackets.Job(stick, 0, 2, "waiting", "busy", "Alex")),
            new TerminalJobPackets.Detail(
                new TerminalJobPackets.Job(stick, 0, 2, "waiting", "busy", "Alex"),
                false,
                List.of(
                    new TerminalJobPackets.StepRow(stick, 1, 1, "done"),
                    new TerminalJobPackets.StepRow(stick, 0, 1, "waiting")),
                3),
            "previewed");
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
          {TerminalJobPackets.DETAIL + 1, 0, 1},
          {TerminalJobPackets.PREVIEW, TerminalJobPackets.ROWS, 1},
          {TerminalJobPackets.PREVIEW, -1, 1},
          {TerminalJobPackets.PREVIEW, 0, 0},
          {TerminalJobPackets.PREVIEW, 0, 4097},
          {TerminalJobPackets.WINDOW, TerminalJobPackets.MAX_OFFSET + 1, 1}
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> new TerminalJobPackets.Command(1, id, 1, bad[0], bad[1], bad[2]));
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
                "",
                0,
                0,
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                TerminalJobPackets.NO_DETAIL,
                "ready"));
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
                "",
                0,
                0,
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                TerminalJobPackets.NO_DETAIL,
                "ready"));
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
                "",
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
                "ready"));
    assertThrows(
        IllegalArgumentException.class, () -> new TerminalJobPackets.PlanRow(entry, -1, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalJobPackets.Detail(
                new TerminalJobPackets.Job(entry, 0, 0, "running", ""),
                true,
                Collections.nCopies(
                    TerminalJobPackets.DETAIL_STEPS + 1,
                    new TerminalJobPackets.StepRow(entry, 0, 1, "queued")),
                0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Job(entry, 0, 1, "running", "", "x".repeat(49)));
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
        () -> new TerminalJobPackets.Job(entry, 4, 3, "running", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Entry("x".repeat(257), "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Entry("minecraft:stick", "", "", "x".repeat(65)));
  }
}
