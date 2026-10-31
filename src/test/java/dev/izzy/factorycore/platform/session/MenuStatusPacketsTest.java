package dev.izzy.factorycore.platform.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.network.AccessPackets;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.program.ProgramPackets;
import dev.izzy.factorycore.platform.signal.SignalPackets;
import dev.izzy.factorycore.platform.storage.BufferSettingsPackets;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsPackets;
import dev.izzy.factorycore.platform.storage.DrivePackets;
import dev.izzy.factorycore.platform.storage.InterfaceSwitch;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.platform.storage.RecoveryActions;
import dev.izzy.factorycore.platform.storage.RecoveryPackets;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

/**
 * M142: every menu's status travels as a keyed enum. Each value round-trips through its view's
 * codec, and a key the client does not know (a mismatched peer) is refused at the packet instead of
 * being shown raw.
 */
class MenuStatusPacketsTest {
  private static final UUID SESSION = new UUID(1, 2);

  @Test
  void everyStatusRoundTripsAndAnUnknownKeyIsRefused() {
    typed(
        SignalPackets.View.CODEC,
        SignalPackets.Status.class,
        status ->
            new SignalPackets.View(
                1,
                SESSION,
                2,
                SignalRule.DEFAULT,
                List.of(
                    new dev.izzy.factorycore.platform.storage.FilterEdits.Rule(
                        dev.izzy.factorycore.platform.storage.FilterEdits.TAG,
                        "minecraft:oak_log",
                        "#minecraft:logs")),
                false,
                0,
                "",
                true,
                status,
                ""));
    var form =
        new MachineSettingsPackets.Form(
            "", ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 1, 8);
    typed(
        MachineSettingsPackets.View.CODEC,
        MachineSettingsPackets.Status.class,
        status ->
            new MachineSettingsPackets.View(
                1, SESSION, 2, form, "west", status, true, "base", 8, false, 1, 0));
    typed(
        ConnectorSettingsPackets.View.CODEC,
        MachineSettingsPackets.Status.class,
        status ->
            new ConnectorSettingsPackets.View(
                1, SESSION, 2, form, "west", "", status, true, "base", 8, false, 1, 0, "ALL",
                List.of()));
    typed(
        MachinePresetPackets.View.CODEC,
        MachinePresetPackets.Status.class,
        status ->
            new MachinePresetPackets.View(
                1,
                SESSION,
                2,
                ResourceKind.ITEM,
                MachinePresets.Mode.PAUSED,
                dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL,
                64,
                List.of(),
                status,
                MachinePresets.Mode.PAUSED,
                "",
                true,
                false,
                false,
                0,
                false));
    typed(
        BufferSettingsPackets.View.CODEC,
        BufferSettingsPackets.Status.class,
        status ->
            new BufferSettingsPackets.View(
                1, SESSION, 2, "PAUSED", "ALL", 8, 16, List.of(), List.of(), status, true, false,
                false, 0, 0, 0, false));
    var bay = new DrivePackets.Bay("", false, 0, 0, false, 0, "ALL", List.of());
    typed(
        DrivePackets.View.CODEC,
        DrivePackets.Status.class,
        status ->
            new DrivePackets.View(
                1, SESSION, 2, Collections.nCopies(DrivePackets.BAYS, bay), status, "", 0));
    typed(
        AccessPackets.View.CODEC,
        AccessPackets.Status.class,
        status ->
            new AccessPackets.View(
                1,
                SESSION,
                2,
                "",
                false,
                false,
                false,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                0,
                0,
                AccessPackets.NONE,
                "",
                status,
                0));
    typed(
        RecoveryPackets.View.CODEC,
        RecoveryPackets.Status.class,
        status ->
            new RecoveryPackets.View(1, SESSION, 2, false, List.of(), 0, false, status, "", ""));
    typed(
        ProgramPackets.View.CODEC,
        ProgramControllerEntity.State.class,
        state ->
            new ProgramPackets.View(
                1, SESSION, 2, state, "", 0, null, false, List.of(), List.of()));
    typed(
        ProgramPackets.View.CODEC,
        WorldPrograms.RuntimeStatus.class,
        status ->
            new ProgramPackets.View(
                1,
                SESSION,
                2,
                ProgramControllerEntity.State.PAUSED,
                "",
                0,
                status,
                false,
                List.of(),
                List.of()));
    typed(
        TerminalPackets.View.CODEC,
        TerminalPackets.Status.class,
        status -> new TerminalPackets.View(1, SESSION, 2, status, List.of(), false, false, "0"));
    typed(
        TerminalCraftingPackets.View.CODEC,
        TerminalCraftingPackets.GridStatus.class,
        status ->
            new TerminalCraftingPackets.View(
                1,
                SESSION,
                2,
                Collections.nCopies(TerminalCraftingPackets.CELLS, ""),
                Collections.nCopies(TerminalCraftingPackets.CELLS, 1),
                false,
                "",
                "",
                0,
                status,
                0));
    typed(
        DeviceStatus.View.CODEC,
        NetworkTopology.Status.class,
        network -> new DeviceStatus.View(1, SESSION, network, "", ""));
  }

  @Test
  void optionalStatusesRoundTripEmpty() {
    var program =
        new ProgramPackets.View(
            1,
            SESSION,
            2,
            ProgramControllerEntity.State.STOPPED,
            "",
            0,
            null,
            false,
            List.of(),
            List.of());
    assertEquals(
        program, decode(ProgramPackets.View.CODEC, encode(ProgramPackets.View.CODEC, program)));
    var pending = DeviceStatus.View.pending(1, SESSION);
    assertNull(pending.network());
    assertFalse(pending.online());
    assertEquals(
        pending, decode(DeviceStatus.View.CODEC, encode(DeviceStatus.View.CODEC, pending)));
  }

  /** Statuses that mirror another enum have a constant for each of its values. */
  @Test
  void mirroredOutcomesAllHaveAStatus() {
    for (var outcome : InterfaceSwitch.Outcome.values()) {
      if (outcome == InterfaceSwitch.Outcome.SWITCHED) continue;
      assertEquals(outcome.key(), outcome.as(SignalPackets.Status.class).key());
      assertEquals(outcome.key(), outcome.as(MachineSettingsPackets.Status.class).key());
      assertEquals(outcome.key(), outcome.as(BufferSettingsPackets.Status.class).key());
    }
    for (var reason : OperationResult.Reason.values()) {
      assertEquals(EnumKeys.key(reason), MachinePresetPackets.Status.of(reason).key());
      assertEquals(EnumKeys.key(reason), BufferSettingsPackets.Status.of(reason).key());
      assertEquals(EnumKeys.key(reason), TerminalPackets.Status.of(reason).key());
    }
    for (var result : RecoveryActions.Result.values()) {
      var status = RecoveryPackets.Status.of(result);
      assertEquals(result.key(), status.key());
      assertEquals(
          result == RecoveryActions.Result.QUEUED || result == RecoveryActions.Result.RETIRED
              ? "gui.factorycore.recovery." + result.key()
              : "command.factorycore.recovery." + result.key(),
          status.translation());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> InterfaceSwitch.Outcome.SWITCHED.as(SignalPackets.Status.class));
  }

  private static <V, E extends Enum<E>> void typed(
      StreamCodec<RegistryFriendlyByteBuf, V> codec, Class<E> type, Function<E, V> view) {
    for (var value : type.getEnumConstants()) {
      var packet = view.apply(value);
      assertEquals(packet, decode(codec, encode(codec, packet)), type.getName() + " " + value);
    }
    // Overwrite the first constant's key in place with an unknown key of the same length.
    var sample = type.getEnumConstants()[0];
    var bytes = encode(codec, view.apply(sample));
    var key = EnumKeys.key(sample).getBytes(StandardCharsets.UTF_8);
    int at = find(bytes, key);
    assertTrue(at >= 0, "Key on the wire: " + type.getName());
    assertEquals(-1, find(java.util.Arrays.copyOfRange(bytes, at + 1, bytes.length), key));
    for (int index = 0; index < key.length; index++) bytes[at + 1 + index] = 'z';
    assertThrows(
        IllegalArgumentException.class, () -> decode(codec, bytes), "Unknown " + type.getName());
  }

  /** The offset of {@code key}'s length byte followed by {@code key}, or -1. */
  private static int find(byte[] bytes, byte[] key) {
    search:
    for (int at = 0; at + key.length < bytes.length; at++) {
      if (bytes[at] != key.length) continue;
      for (int index = 0; index < key.length; index++)
        if (bytes[at + 1 + index] != key[index]) continue search;
      return at;
    }
    return -1;
  }

  private static RegistryFriendlyByteBuf buffer() {
    return new RegistryFriendlyByteBuf(
        Unpooled.buffer(),
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
        ConnectionType.NEOFORGE);
  }

  private static <V> byte[] encode(StreamCodec<RegistryFriendlyByteBuf, V> codec, V value) {
    var buffer = buffer();
    try {
      codec.encode(buffer, value);
      var bytes = new byte[buffer.readableBytes()];
      buffer.readBytes(bytes);
      return bytes;
    } finally {
      buffer.release();
    }
  }

  private static <V> V decode(StreamCodec<RegistryFriendlyByteBuf, V> codec, byte[] bytes) {
    var buffer = buffer();
    try {
      buffer.writeBytes(bytes);
      var value = codec.decode(buffer);
      assertFalse(buffer.isReadable());
      return value;
    } finally {
      buffer.release();
    }
  }
}
