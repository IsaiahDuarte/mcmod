package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;

class MachineSettingsPacketsTest {
  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static RegistryFriendlyByteBuf buffer() {
    return new RegistryFriendlyByteBuf(
        Unpooled.buffer(),
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
        net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
  }

  @Test
  void completeBoundedFormRoundTripsWithoutAuthorityOrProviderData() {
    var form =
        new MachineSettingsPackets.Form(
            "smelter", ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, -1000, 2, 1, 8);
    var packet = new MachineSettingsPackets.Apply(17, UUID.randomUUID(), 42, form);
    var output = buffer();
    try {
      MachineSettingsPackets.Apply.CODEC.encode(output, packet);
      assertTrue(output.readableBytes() < 128);
      assertEquals(packet, MachineSettingsPackets.Apply.CODEC.decode(output));
      assertFalse(output.isReadable());
      var view =
          new MachineSettingsPackets.View(
              17, packet.session(), 42, form, "west", "stale", true, "ENDGAME", 1024, true);
      MachineSettingsPackets.View.CODEC.encode(output, view);
      assertEquals(view, MachineSettingsPackets.View.CODEC.decode(output));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
  }

  @Test
  void selectedTankFormRoundTripsWithExactWindow() {
    var form =
        new MachineSettingsPackets.Form(
            "coolant", ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 3, 7, 2, 500);
    var packet = new MachineSettingsPackets.Apply(1, UUID.randomUUID(), 4, form);
    var output = buffer();
    try {
      MachineSettingsPackets.Apply.CODEC.encode(output, packet);
      assertEquals(packet, MachineSettingsPackets.Apply.CODEC.decode(output));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
  }

  @Test
  void malformedFieldsRejectBeforeAnyMenuOrProviderCall() {
    var access = ConnectorSettings.Access.READ_WRITE;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MachineSettingsPackets.Form("x".repeat(65), ResourceKind.ITEM, access, 0, 0, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("a\nb", ResourceKind.ITEM, access, 0, 0, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("", ResourceKind.ITEM, access, 1001, 0, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("", ResourceKind.ITEM, access, 0, -1, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("", ResourceKind.ITEM, access, 0, 0, 65, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("", ResourceKind.ITEM, access, 0, 0, 1, 1025));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("", ResourceKind.ENERGY, access, 0, 1, 64, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineSettingsPackets.Form("", ResourceKind.ENERGY, access, 0, 0, 64, 32001));
    var output = buffer();
    try {
      output.writeVarInt(1);
      output.writeUUID(UUID.randomUUID());
      output.writeLong(1);
      output.writeUtf("");
      output.writeVarInt(99);
      assertThrows(
          IndexOutOfBoundsException.class, () -> MachineSettingsPackets.Apply.CODEC.decode(output));
    } finally {
      output.release();
    }
  }
}
