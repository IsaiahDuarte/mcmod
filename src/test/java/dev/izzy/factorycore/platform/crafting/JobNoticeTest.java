package dev.izzy.factorycore.platform.crafting;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JobNoticeTest {
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  @Test
  void roundTripsAndRefusesBadBounds() {
    // Q99: the toast flag (a carried wireless terminal) crosses the wire too.
    for (var notice :
        java.util.List.of(
            new JobNotice("minecraft:oak_planks", 37, false),
            new JobNotice("minecraft:stick", 4, true, true))) {
      var buffer =
          new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
      try {
        JobNotice.CODEC.encode(buffer, notice);
        assertEquals(notice, JobNotice.CODEC.decode(buffer));
        assertFalse(buffer.isReadable());
      } finally {
        buffer.release();
      }
    }
    assertThrows(IllegalArgumentException.class, () -> new JobNotice("minecraft:stick", 0, true));
    assertThrows(IllegalArgumentException.class, () -> new JobNotice("x".repeat(257), 1, false));
  }
}
