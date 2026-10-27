package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Q11: the overlay snapshot keeps the nearest 256 entries and its payload is bounded. */
class LabelOverlayTest {
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  @Test
  void keepsTheNearestEntriesNearestFirst() {
    var entries = new ArrayList<LabelOverlay.Entry>();
    for (int x = 300; x > 0; x--)
      entries.add(new LabelOverlay.Entry(new BlockPos(x, 0, 0).asLong(), ""));
    var kept = LabelOverlay.nearest(entries, BlockPos.ZERO, LabelOverlay.MAX_ENTRIES);
    assertEquals(LabelOverlay.MAX_ENTRIES, kept.size());
    assertEquals(new BlockPos(1, 0, 0).asLong(), kept.getFirst().pos());
    assertEquals(new BlockPos(256, 0, 0).asLong(), kept.getLast().pos(), "The farthest 44 dropped");
  }

  @Test
  void payloadRoundTripsAndRefusesMoreThanTheBound() {
    // The network view carries up to MAX_NETWORK entries; label mode keeps the nearest 256.
    var full = new ArrayList<LabelOverlay.Entry>();
    for (int index = 0; index < LabelOverlay.MAX_NETWORK; index++)
      full.add(
          index % 2 == 0
              ? new LabelOverlay.Entry(index, "oven")
              : new LabelOverlay.Entry(
                  index, "", LabelOverlay.Kind.values()[index % 7], index % 256));
    var snapshot = new LabelOverlay.Snapshot(full);
    var buffer =
        new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
    try {
      LabelOverlay.Snapshot.CODEC.encode(buffer, snapshot);
      assertEquals(snapshot, LabelOverlay.Snapshot.CODEC.decode(buffer));
      buffer.clear();
      buffer.writeVarInt(LabelOverlay.MAX_NETWORK + 1);
      assertThrows(
          IllegalArgumentException.class, () -> LabelOverlay.Snapshot.CODEC.decode(buffer));
    } finally {
      buffer.release();
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LabelOverlay.Snapshot(
                Collections.nCopies(LabelOverlay.MAX_NETWORK + 1, new LabelOverlay.Entry(7, ""))));
    assertThrows(IllegalArgumentException.class, () -> new LabelOverlay.Entry(7, "x".repeat(65)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LabelOverlay.Entry(7, "", LabelOverlay.Kind.CABLE, 256));
  }
}
