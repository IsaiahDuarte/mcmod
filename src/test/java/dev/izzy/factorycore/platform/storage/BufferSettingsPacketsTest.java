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

class BufferSettingsPacketsTest {
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
        new BufferSettingsPackets.Command(
            17, UUID.randomUUID(), 42, BufferSettingsPackets.DEPOSIT, 35, 64, "iron", digest);
    digest[0] ^= 1;
    assertNotEquals(digest[0], command.fingerprint()[0]);
    var output = buffer();
    try {
      BufferSettingsPackets.Command.CODEC.encode(output, command);
      assertTrue(
          output.readableBytes() < 256, "No resource components or topology sent as authority");
      var restored = BufferSettingsPackets.Command.CODEC.decode(output);
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
  void boundedSettingsProjectionRoundTrips() {
    var packet =
        new BufferSettingsPackets.View(
            17,
            UUID.randomUUID(),
            42,
            "STOCK",
            "INCLUDE",
            8,
            16,
            List.of("#minecraft:logs", "= minecraft:iron_ingot · Exact sample"),
            List.of(new BufferSettingsPackets.Row(0, "minecraft:iron_ingot", "Exact sample", 64)),
            "draft",
            true,
            false,
            true,
            3,
            8,
            64);
    var output = buffer();
    try {
      BufferSettingsPackets.View.CODEC.encode(output, packet);
      assertTrue(output.readableBytes() < 512);
      assertEquals(packet, BufferSettingsPackets.View.CODEC.decode(output));
      assertFalse(output.isReadable());
    } finally {
      output.release();
    }
  }

  @Test
  void oversizedCommandsAndRuleProjectionsReject() {
    UUID session = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> new BufferSettingsPackets.Command(1, session, 1, 14, 0, 1, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BufferSettingsPackets.Command(1, session, 1, 4, 36, 1, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BufferSettingsPackets.Command(1, session, 1, 4, 0, 577, "", new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BufferSettingsPackets.Command(
                1, session, 1, 0, 0, 1, "a".repeat(257), new byte[32]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BufferSettingsPackets.View(
                1,
                session,
                1,
                "PAUSED",
                "ALL",
                64,
                64,
                Collections.nCopies(17, "rule"),
                List.of(),
                "ready",
                true,
                false,
                false,
                0,
                0,
                0));
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
          () -> BufferSettingsPackets.Command.CODEC.decode(output));
    } finally {
      output.release();
    }
  }
}
