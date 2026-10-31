package dev.izzy.factorycore.platform.network;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

class OverviewPacketsTest {
  private static RegistryFriendlyByteBuf buffer() {
    return new RegistryFriendlyByteBuf(
        Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
  }

  @Test
  void topUserNamesRoundTripAndStayBounded() {
    var names = new OverviewPackets.Names(7, List.of("Front gate", ""));
    var out = buffer();
    OverviewPackets.Names.CODEC.encode(out, names);
    assertEquals(names, OverviewPackets.Names.CODEC.decode(out));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OverviewPackets.Names(1, Collections.nCopies(6, "a")),
        "At most the five top users");
    assertThrows(
        IllegalArgumentException.class,
        () -> new OverviewPackets.Names(1, List.of("a".repeat(65))),
        "Names are bounded like labels");
    var forged = buffer();
    forged.writeVarInt(1);
    forged.writeVarInt(6);
    assertThrows(IllegalArgumentException.class, () -> OverviewPackets.Names.CODEC.decode(forged));
  }
}
