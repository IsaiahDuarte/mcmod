package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerminalPacketsTest {
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
  void commandRoundTripOwnIdentityHashIsCountIndependentAndDetached() {
    var codec = new PlatformResourceCodec(registries);
    var stack = new ItemStack(Items.IRON_INGOT, 64);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Exact selection"));
    byte[] digest = TerminalPackets.fingerprint(codec.itemKey(stack));
    assertArrayEquals(digest, TerminalPackets.fingerprint(codec.itemKey(stack.copyWithCount(1))));
    var command =
        new TerminalPackets.Command(
            17, UUID.randomUUID(), 42, TerminalPackets.DEPOSIT, 35, 64, "iron", digest);
    digest[0] ^= 1;
    assertNotEquals(digest[0], command.fingerprint()[0]);
    var output = buffer();
    try {
      TerminalPackets.Command.CODEC.encode(output, command);
      assertTrue(
          output.readableBytes() < 256, "No resource components or topology sent as authority");
      var restored = TerminalPackets.Command.CODEC.decode(output);
      assertEquals(command.session(), restored.session());
      assertEquals(35, restored.selection());
      assertArrayEquals(command.fingerprint(), restored.fingerprint());
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));
    assertFalse(
        MessageDigest.isEqual(
            command.fingerprint(), TerminalPackets.fingerprint(codec.itemKey(stack))));
  }

  @Test
  void exactHugeCountsRoundTripInBoundedDisplayProjection() {
    var packet =
        new TerminalPackets.View(
            17,
            UUID.randomUUID(),
            42,
            "ready",
            List.of(
                new TerminalPackets.Row(
                    0,
                    "minecraft:iron_ingot",
                    "Plain name",
                    "47223664828696452136960",
                    "9223372036854775807",
                    "47214441456659597361153")),
            true,
            false,
            "64",
            "@minecraft",
            TerminalSearchSupport.QUANTITY);
    var output = buffer();
    try {
      TerminalPackets.View.CODEC.encode(output, packet);
      assertTrue(output.readableBytes() < 512);
      assertEquals(packet, TerminalPackets.View.CODEC.decode(output));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
  }

  @Test
  void oversizedAndInvalidInboundCommandsRejectBeforeMenuExecution() {
    UUID session = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalPackets.Command(
                1, session, 1, TerminalPackets.SORT + 1, 0, 1, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalPackets.Command(1, session, 1, 4, 36, 1, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalPackets.Command(1, session, 1, 4, 0, 65, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalPackets.Command(1, session, 1, 0, 0, 1, "a".repeat(129), new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalPackets.Command(
                1, session, 1, TerminalPackets.SORT, 2, 1, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TerminalPackets.View(1, session, 1, "ready", List.of(), false, false, "0", "", 2));
    var output = buffer();
    try {
      output.writeVarInt(1);
      output.writeUUID(session);
      output.writeLong(1);
      output.writeVarInt(4);
      output.writeVarInt(0);
      output.writeVarInt(1);
      output.writeUtf("");
      output.writeByteArray(new byte[33]);
      assertThrows(
          io.netty.handler.codec.DecoderException.class,
          () -> TerminalPackets.Command.CODEC.decode(output));
    } finally {
      output.release();
    }
    var rows =
        Collections.nCopies(
            9, new TerminalPackets.Row(0, "minecraft:iron_ingot", "Iron", "1", "0", "1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalPackets.View(1, session, 1, "ready", rows, false, false, "0"));
  }
}
