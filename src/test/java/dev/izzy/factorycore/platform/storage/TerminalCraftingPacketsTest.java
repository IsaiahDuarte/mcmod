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

class TerminalCraftingPacketsTest {
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
  void commandRoundTripCarriesNoStackRecipeOrQuantityAuthority() {
    var digest = new byte[32];
    digest[3] = 7;
    var command =
        new TerminalCraftingPackets.Command(
            4, UUID.randomUUID(), 9, 11, TerminalCraftingPackets.SET_OWN, 8, 35, 1, digest);
    digest[3] = 0;
    assertEquals(7, command.fingerprint()[3], "Fingerprint is detached");
    var output = buffer();
    try {
      TerminalCraftingPackets.Command.CODEC.encode(output, command);
      assertTrue(output.readableBytes() < 96, "Only bounded identifiers are sent");
      var restored = TerminalCraftingPackets.Command.CODEC.decode(output);
      assertEquals(command.session(), restored.session());
      assertEquals(11, restored.gridRevision());
      assertEquals(8, restored.cell());
      assertEquals(35, restored.source());
      assertArrayEquals(command.fingerprint(), restored.fingerprint());
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
  }

  @Test
  void fluidGhostsRoundTripWithMilliBucketDisplayUnitsAndStrictKinds() {
    var cells = new java.util.ArrayList<>(Collections.nCopies(12, ""));
    var counts = new java.util.ArrayList<>(Collections.nCopies(12, 1));
    cells.set(0, "minecraft:water");
    cells.set(9, "minecraft:lava");
    cells.set(10, "minecraft:stone");
    counts.set(0, 4);
    counts.set(9, 64);
    counts.set(10, 2);
    var view =
        new TerminalCraftingPackets.View(
            1,
            UUID.randomUUID(),
            3,
            cells,
            counts,
            true,
            "minecraft:stone",
            "Stone",
            2,
            "ready",
            0,
            (1 << 0) | (1 << 9));
    var output = buffer();
    try {
      TerminalCraftingPackets.View.CODEC.encode(output, view);
      var decoded = TerminalCraftingPackets.View.CODEC.decode(output);
      assertEquals(view, decoded);
      assertTrue(decoded.fluid(0));
      assertTrue(decoded.fluid(9));
      assertFalse(decoded.fluid(10));
      assertEquals(1000, decoded.amount(0));
      assertEquals(16000, decoded.amount(9));
      assertEquals(2, decoded.amount(10));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
    for (int mask : new int[] {-1, 4096, 1 << 1})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new TerminalCraftingPackets.View(
                  1, view.session(), 3, cells, counts, true, "", "", 0, "ready", 0, mask));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalCraftingPackets.View(
                1, view.session(), 3, cells, counts, false, "", "", 0, "ready", 0, 1));
  }

  @Test
  void commandAndViewBoundsReject() {
    var id = UUID.randomUUID();
    var empty = new byte[32];
    // Actions now run through COUNT and cells through the three processing outputs (9–11).
    for (int[] bad :
        new int[][] {
          {-1, 0, 0, 1},
          {TerminalCraftingPackets.COUNT + 1, 0, 0, 1},
          {0, TerminalCraftingPackets.CELLS, 0, 1},
          {0, -2, 0, 1},
          {0, 0, 36, 1},
          {3, 0, 0, 0},
          {3, 0, 0, 65}
        })
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new TerminalCraftingPackets.Command(
                  1, id, 1, 1, bad[0], bad[1], bad[2], bad[3], empty));
    new TerminalCraftingPackets.Command(
        1, id, 1, 1, TerminalCraftingPackets.COUNT, 11, 0, 64, empty);
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalCraftingPackets.Command(1, id, 1, 1, 0, 0, 0, 1, new byte[31]));
    var cells = Collections.nCopies(TerminalCraftingPackets.CELLS, "minecraft:oak_log");
    var counts = Collections.nCopies(TerminalCraftingPackets.CELLS, 3);
    var view =
        new TerminalCraftingPackets.View(
            1, id, 3, cells, counts, true, "minecraft:oak_planks", "Oak Planks", 4, "ready", 0);
    var output = buffer();
    try {
      TerminalCraftingPackets.View.CODEC.encode(output, view);
      assertEquals(view, TerminalCraftingPackets.View.CODEC.decode(output));
    } finally {
      output.release();
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalCraftingPackets.View(
                1, id, 3, List.of(""), counts, false, "", "", 0, "ready", 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalCraftingPackets.View(
                1,
                id,
                3,
                cells,
                Collections.nCopies(TerminalCraftingPackets.CELLS, 0),
                false,
                "",
                "",
                0,
                "ready",
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalCraftingPackets.View(
                1, id, 3, cells, counts, false, "", "", 65, "ready", 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalCraftingPackets.View(
                1, id, 3, cells, counts, false, "", "", 0, "x".repeat(65), 0));
  }
}
