package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import io.netty.buffer.Unpooled;
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

/** Q143: the Storage Interface view carries the facing and what each face could face. */
class ConnectorSettingsPacketsTest {
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private static ConnectorSettingsPackets.View view(int facing, List<String> faces) {
    return new ConnectorSettingsPackets.View(
        1,
        UUID.randomUUID(),
        2,
        new MachineSettingsPackets.Form(
            "", ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 1, 8),
        "west",
        "",
        MachineSettingsPackets.Status.READY,
        true,
        "base",
        8,
        false,
        27,
        0,
        "ALL",
        List.of(),
        "block.minecraft.chest",
        "",
        facing,
        faces);
  }

  @Test
  void facesRoundTripAndBoundsReject() {
    var packet =
        view(5, List.of("", "block.minecraft.barrel", "", "", "", "block.minecraft.chest"));
    var output =
        new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
    try {
      ConnectorSettingsPackets.View.CODEC.encode(output, packet);
      assertEquals(packet, ConnectorSettingsPackets.View.CODEC.decode(output));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
    assertEquals(-1, view(-1, List.of()).facing(), "A pending view has no facing yet");
    assertThrows(IllegalArgumentException.class, () -> view(6, List.of()), "Facing is a face");
    assertThrows(
        IllegalArgumentException.class,
        () -> view(0, List.of("", "")),
        "Faces are all six or none");
    assertThrows(
        IllegalArgumentException.class,
        () -> view(0, java.util.Collections.nCopies(6, "x".repeat(129))),
        "Each face's name key is bounded");
  }
}
