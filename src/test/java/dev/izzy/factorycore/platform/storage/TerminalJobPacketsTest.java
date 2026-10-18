package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
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
    return new RegistryFriendlyByteBuf(
        Unpooled.buffer(),
        registries,
        net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
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
            List.of(stick),
            true,
            stick,
            "",
            2,
            3,
            List.of(new TerminalJobPackets.Entry("minecraft:oak_log", "Oak Log", "1")),
            List.of(new TerminalJobPackets.Job(stick, 1, 3, "running", "")),
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
          {TerminalJobPackets.KEEP + 1, 0, 1},
          {2, 8, 1},
          {2, -1, 1},
          {2, 0, 0},
          {2, 0, 4097}
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
                java.util.Collections.nCopies(9, entry),
                false,
                entry,
                "",
                0,
                0,
                List.of(),
                List.of(),
                "ready"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Job(entry, 4, 3, "running", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalJobPackets.Entry("x".repeat(257), "", ""));
  }
}
