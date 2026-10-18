package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class TerminalPackets {
  public static final int SEARCH = 0, HOME = 1, NEXT = 2, WITHDRAW = 3, DEPOSIT = 4, RETURN = 5;

  /**
   * KIND switches the view (selection 0 items, 1 fluids, 2 energy); FILL fills the first compatible
   * container from fluid row {@code selection}; EMPTY empties own slot {@code selection};
   * CHARGE/DISCHARGE move up to 1,000 FE into/out of the energy item in a slot.
   */
  public static final int KIND = 6, FILL = 7, EMPTY = 8, CHARGE = 9, DISCHARGE = 10;

  public record Command(
      int menu,
      UUID session,
      long revision,
      int action,
      int selection,
      int amount,
      String text,
      byte[] fingerprint)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_command"));

    public Command {
      fingerprint = fingerprint.clone();
      if (action < 0
          || action > DISCHARGE
          || selection < 0
          || selection >= 36
          || amount < 1
          || amount > 64
          || text.length() > 128
          || fingerprint.length != 32)
        throw new IllegalArgumentException("Invalid terminal command bounds");
    }

    @Override
    public byte[] fingerprint() {
      return fingerprint.clone();
    }

    @Override
    public Type<Command> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeLong(value.revision);
              buffer.writeVarInt(value.action);
              buffer.writeVarInt(value.selection);
              buffer.writeVarInt(value.amount);
              buffer.writeUtf(value.text, 128);
              buffer.writeByteArray(value.fingerprint);
            },
            buffer ->
                new Command(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(128),
                    buffer.readByteArray(32)));
  }

  public record Row(
      int token, String icon, String name, String total, String reserved, String available) {
    public Row {
      if (token < 0
          || token >= 8
          || icon.length() > 256
          || name.length() > 96
          || total.length() > 32
          || reserved.length() > 32
          || available.length() > 32)
        throw new IllegalArgumentException("Invalid terminal row bounds");
    }
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      String status,
      List<Row> rows,
      boolean next,
      boolean busy,
      String staged)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_view"));

    public View {
      rows = List.copyOf(rows);
      if (rows.size() > 8 || status.length() > 64 || staged.length() > 32)
        throw new IllegalArgumentException("Invalid terminal view bounds");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeLong(value.revision);
              buffer.writeUtf(value.status, 64);
              buffer.writeVarInt(value.rows.size());
              for (var row : value.rows) {
                buffer.writeVarInt(row.token);
                buffer.writeUtf(row.icon, 256);
                buffer.writeUtf(row.name, 96);
                buffer.writeUtf(row.total, 32);
                buffer.writeUtf(row.reserved, 32);
                buffer.writeUtf(row.available, 32);
              }
              buffer.writeBoolean(value.next);
              buffer.writeBoolean(value.busy);
              buffer.writeUtf(value.staged, 32);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              UUID session = buffer.readUUID();
              long revision = buffer.readLong();
              String status = buffer.readUtf(64);
              int size = buffer.readVarInt();
              if (size < 0 || size > 8) throw new IllegalArgumentException("Terminal row count");
              var rows = new java.util.ArrayList<Row>();
              for (int index = 0; index < size; index++)
                rows.add(
                    new Row(
                        buffer.readVarInt(),
                        buffer.readUtf(256),
                        buffer.readUtf(96),
                        buffer.readUtf(32),
                        buffer.readUtf(32),
                        buffer.readUtf(32)));
              return new View(
                  menu,
                  session,
                  revision,
                  status,
                  rows,
                  buffer.readBoolean(),
                  buffer.readBoolean(),
                  buffer.readUtf(32));
            });
  }

  private TerminalPackets() {}

  public static byte[] fingerprint(ResourceKey key) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(key.registryId().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      return digest.digest(key.components());
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("Required SHA-256 unavailable", missing);
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(
        Command.TYPE,
        Command.CODEC,
        (value, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof TerminalMenu menu) menu.command(player, value);
        });
    registrar.playToServer(
        TerminalJobPackets.Command.TYPE,
        TerminalJobPackets.Command.CODEC,
        (value, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof TerminalMenu menu) menu.jobCommand(player, value);
        });
    registrar.playToClient(
        TerminalJobPackets.View.TYPE,
        TerminalJobPackets.View.CODEC,
        (value, context) -> {
          if (context.player().containerMenu instanceof TerminalMenu menu) menu.accept(value);
        });
    registrar.playToServer(
        TerminalCraftingPackets.Command.TYPE,
        TerminalCraftingPackets.Command.CODEC,
        (value, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof TerminalMenu menu) menu.gridCommand(player, value);
        });
    registrar.playToClient(
        TerminalCraftingPackets.View.TYPE,
        TerminalCraftingPackets.View.CODEC,
        (value, context) -> {
          if (context.player().containerMenu instanceof TerminalMenu menu) menu.accept(value);
        });
    registrar.playToClient(
        View.TYPE,
        View.CODEC,
        (value, context) -> {
          if (context.player().containerMenu instanceof TerminalMenu menu) menu.accept(value);
        });
  }
}
