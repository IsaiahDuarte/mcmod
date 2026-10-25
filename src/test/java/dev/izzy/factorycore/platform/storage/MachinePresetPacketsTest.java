package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.MachinePresets;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.*;

class MachinePresetPacketsTest {
  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  private static RegistryFriendlyByteBuf buffer() {
    return new RegistryFriendlyByteBuf(
        Unpooled.buffer(),
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
        ConnectionType.NEOFORGE);
  }

  @Test
  void detachedFingerprintAndBoundedDraftViewRoundTrip() {
    byte[] fingerprint = new byte[32];
    fingerprint[0] = 7;
    var command =
        new MachinePresetPackets.Command(
            17, UUID.randomUUID(), 42, MachinePresetPackets.SAMPLE, 35, "", fingerprint);
    fingerprint[0] = 9;
    command.fingerprint()[0] = 11;
    assertEquals(7, command.fingerprint()[0]);
    var b = buffer();
    try {
      MachinePresetPackets.Command.CODEC.encode(b, command);
      var decoded = MachinePresetPackets.Command.CODEC.decode(b);
      assertEquals(command.session(), decoded.session());
      assertEquals(35, decoded.selection());
      assertArrayEquals(command.fingerprint(), decoded.fingerprint());
      var view =
          new MachinePresetPackets.View(
              17,
              command.session(),
              42,
              ResourceKind.ITEM,
              MachinePresets.Mode.STOCK,
              ResourceFilter.Mode.INCLUDE,
              Long.MAX_VALUE,
              List.of(
                  new FilterEdits.Rule(
                      FilterEdits.EXACT,
                      "minecraft:iron_ingot",
                      "= minecraft:iron_ingot · Named iron")),
              "draft",
              MachinePresets.Mode.PAUSED,
              "ok",
              true,
              false,
              true,
              128,
              true);
      MachinePresetPackets.View.CODEC.encode(b, view);
      assertEquals(view, MachinePresetPackets.View.CODEC.decode(b));
      assertFalse(b.isReadable());
    } finally {
      b.release();
    }
  }

  @Test
  void malformedCommandsAndOverlongDisplayListsRejectBeforeMenuAccess() {
    var id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MachinePresetPackets.Command(
                1, id, 1, MachinePresetPackets.CYCLE + 1, 0, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePresetPackets.Command(1, id, 1, 0, 36, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePresetPackets.Command(1, id, 1, 0, 0, "x".repeat(257), new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePresetPackets.Command(1, id, 1, 0, 0, "", new byte[31]));
    var b = buffer();
    try {
      b.writeVarInt(1);
      b.writeUUID(id);
      b.writeLong(1);
      b.writeEnum(ResourceKind.ITEM);
      b.writeEnum(MachinePresets.Mode.PAUSED);
      b.writeEnum(ResourceFilter.Mode.ALL);
      b.writeLong(0);
      b.writeVarInt(17);
      assertThrows(IllegalArgumentException.class, () -> MachinePresetPackets.View.CODEC.decode(b));
    } finally {
      b.release();
    }
  }
}
