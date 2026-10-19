package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Transfer Recovery screen payloads (ADR 0055). Commands carry an action and a record id that must
 * be on the session's current server-built page; the server never trusts client ownership, operator
 * status or record state.
 */
public final class RecoveryPackets {
  public static final int HOME = 0, NEXT = 1, RETURN = 2, SETTLE = 3, RETIRE = 4;
  public static final int PAGE_ROWS = 6;
  public static final UUID NONE = new UUID(0, 0);

  public record Command(int menu, UUID session, int action, UUID record)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "recovery_command"));

    public Command {
      Objects.requireNonNull(session);
      Objects.requireNonNull(record);
      if (action < HOME || action > RETIRE)
        throw new IllegalArgumentException("Invalid recovery command");
    }

    @Override
    public Type<Command> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              b.writeVarInt(v.action);
              b.writeUUID(v.record);
            },
            b -> new Command(b.readVarInt(), b.readUUID(), b.readVarInt(), b.readUUID()));
  }

  /**
   * One transfer record. {@code classification} is uncertain, complete, legacy or known; {@code
   * mine} is true when the viewer is the record's original owner.
   */
  public record Row(
      UUID id,
      String owner,
      String state,
      String kind,
      String resource,
      long staged,
      long delivered,
      long returned,
      String pending,
      String classification,
      boolean mine) {
    public Row {
      Objects.requireNonNull(id);
      if (owner.length() > 48
          || state.length() > 16
          || kind.length() > 8
          || resource.length() > 96
          || staged < 0
          || delivered < 0
          || returned < 0
          || pending.length() > 16
          || classification.length() > 12) throw new IllegalArgumentException("Invalid row");
    }
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      boolean operator,
      List<Row> rows,
      int page,
      boolean more,
      String status,
      String evidence,
      String detail)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "recovery_view"));

    public View {
      rows = List.copyOf(rows);
      if (rows.size() > PAGE_ROWS
          || page < 0
          || page > 1024
          || status.length() > 32
          || evidence.length() > 128
          || detail.length() > 32) throw new IllegalArgumentException("Invalid recovery view");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              b.writeVarLong(v.revision);
              b.writeBoolean(v.operator);
              b.writeVarInt(v.rows.size());
              for (var row : v.rows) {
                b.writeUUID(row.id);
                b.writeUtf(row.owner, 48);
                b.writeUtf(row.state, 16);
                b.writeUtf(row.kind, 8);
                b.writeUtf(row.resource, 96);
                b.writeVarLong(row.staged);
                b.writeVarLong(row.delivered);
                b.writeVarLong(row.returned);
                b.writeUtf(row.pending, 16);
                b.writeUtf(row.classification, 12);
                b.writeBoolean(row.mine);
              }
              b.writeVarInt(v.page);
              b.writeBoolean(v.more);
              b.writeUtf(v.status, 32);
              b.writeUtf(v.evidence, 128);
              b.writeUtf(v.detail, 32);
            },
            b -> {
              int menu = b.readVarInt();
              UUID session = b.readUUID();
              long revision = b.readVarLong();
              boolean operator = b.readBoolean();
              int size = b.readVarInt();
              if (size < 0 || size > PAGE_ROWS) throw new IllegalArgumentException("Rows");
              var rows = new ArrayList<Row>(size);
              for (int i = 0; i < size; i++)
                rows.add(
                    new Row(
                        b.readUUID(),
                        b.readUtf(48),
                        b.readUtf(16),
                        b.readUtf(8),
                        b.readUtf(96),
                        b.readVarLong(),
                        b.readVarLong(),
                        b.readVarLong(),
                        b.readUtf(16),
                        b.readUtf(12),
                        b.readBoolean()));
              return new View(
                  menu,
                  session,
                  revision,
                  operator,
                  rows,
                  b.readVarInt(),
                  b.readBoolean(),
                  b.readUtf(32),
                  b.readUtf(128),
                  b.readUtf(32));
            });
  }

  private RecoveryPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(
        Command.TYPE,
        Command.CODEC,
        (packet, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof RecoveryMenu menu) menu.command(player, packet);
        });
    registrar.playToClient(
        View.TYPE,
        View.CODEC,
        (packet, context) -> {
          if (context.player().containerMenu instanceof RecoveryMenu menu) menu.accept(packet);
        });
  }
}
