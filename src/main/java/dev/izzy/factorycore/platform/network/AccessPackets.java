package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Network Access screen payloads (ADR 0055). Commands name an action, a principal and one value;
 * the server derives the actor, origin and current grants itself and ignores commands that do not
 * match the open session. Views are bounded pages computed from canonical state.
 */
public final class AccessPackets {
  public static final int GRANT = 0,
      REVOKE = 1,
      REVOKE_ALL = 2,
      PAGE = 3,
      TRANSFER = 4,
      CONFIRM = 5,
      CANCEL = 6,
      RECOVERY = 7;

  /** Principal rows per page; candidates (online players without grants) follow on each page. */
  public static final int PAGE_ROWS = 6;

  /** Explicit principals (at most 256) plus at most {@link #MAX_CANDIDATES} online players. */
  public static final int MAX_CANDIDATES = 8, MAX_TOTAL = 256 + MAX_CANDIDATES, MAX_PAGES = 64;

  public static final UUID NONE = new UUID(0, 0);

  public record Command(int menu, UUID session, int action, UUID principal, int value)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "access_command"));

    public Command {
      if (action < GRANT || action > RECOVERY || value < 0 || value >= MAX_PAGES)
        throw new IllegalArgumentException("Invalid access command bounds");
      Objects.requireNonNull(session);
      Objects.requireNonNull(principal);
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
              b.writeUUID(v.principal);
              b.writeVarInt(v.value);
            },
            b ->
                new Command(
                    b.readVarInt(), b.readUUID(), b.readVarInt(), b.readUUID(), b.readVarInt()));
  }

  /** One principal: explicit grants as a permission bit mask; candidates have no grants yet. */
  public record Row(UUID id, String name, int granted, boolean online, boolean candidate) {
    public Row {
      Objects.requireNonNull(id);
      if (name.length() > 48 || granted < 0 || granted >= 1 << 7)
        throw new IllegalArgumentException("Invalid access row");
    }
  }

  /**
   * One bounded view. Masks use permission ordinals; {@code branch} is the intersection of every
   * ancestor gateway's branch policy (all bits at the root segment), {@code inherited} and {@code
   * kinds} the intersected parent-stock limits. {@code self} is the viewer's current effective
   * access here. Rows and the owner are present only for a viewer who currently has Manage.
   */
  public record View(
      int menu,
      UUID session,
      long revision,
      String owner,
      boolean ownerSelf,
      boolean manage,
      boolean transfer,
      int self,
      int branch,
      int inherited,
      int kinds,
      int gateways,
      List<Row> rows,
      int total,
      int page,
      UUID pending,
      String pendingName,
      String status,
      long generation)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "access_view"));

    public View {
      rows = List.copyOf(rows);
      if (owner.length() > 48
          || self < 0
          || self >= 1 << 7
          || branch < 0
          || branch >= 1 << 7
          || inherited < 0
          || inherited >= 1 << 7
          || kinds < 0
          || kinds >= 1 << 3
          || gateways < 0
          || gateways > 64
          || rows.size() > PAGE_ROWS
          || total < 0
          || total > MAX_TOTAL
          || page < 0
          || page >= MAX_PAGES
          || pendingName.length() > 48
          || status.length() > 32
          || generation < 0) throw new IllegalArgumentException("Invalid access view");
      Objects.requireNonNull(pending);
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
              b.writeUtf(v.owner, 48);
              b.writeBoolean(v.ownerSelf);
              b.writeBoolean(v.manage);
              b.writeBoolean(v.transfer);
              b.writeVarInt(v.self);
              b.writeVarInt(v.branch);
              b.writeVarInt(v.inherited);
              b.writeVarInt(v.kinds);
              b.writeVarInt(v.gateways);
              b.writeVarInt(v.rows.size());
              for (var row : v.rows) {
                b.writeUUID(row.id);
                b.writeUtf(row.name, 48);
                b.writeVarInt(row.granted);
                b.writeBoolean(row.online);
                b.writeBoolean(row.candidate);
              }
              b.writeVarInt(v.total);
              b.writeVarInt(v.page);
              b.writeUUID(v.pending);
              b.writeUtf(v.pendingName, 48);
              b.writeUtf(v.status, 32);
              b.writeVarLong(v.generation);
            },
            b -> {
              int menu = b.readVarInt();
              UUID session = b.readUUID();
              long revision = b.readVarLong();
              String owner = b.readUtf(48);
              boolean ownerSelf = b.readBoolean(), manage = b.readBoolean();
              boolean transfer = b.readBoolean();
              int self = b.readVarInt(), branch = b.readVarInt(), inherited = b.readVarInt();
              int kinds = b.readVarInt(), gateways = b.readVarInt(), size = b.readVarInt();
              if (size < 0 || size > PAGE_ROWS) throw new IllegalArgumentException("Rows");
              var rows = new ArrayList<Row>(size);
              for (int i = 0; i < size; i++)
                rows.add(
                    new Row(
                        b.readUUID(),
                        b.readUtf(48),
                        b.readVarInt(),
                        b.readBoolean(),
                        b.readBoolean()));
              return new View(
                  menu,
                  session,
                  revision,
                  owner,
                  ownerSelf,
                  manage,
                  transfer,
                  self,
                  branch,
                  inherited,
                  kinds,
                  gateways,
                  rows,
                  b.readVarInt(),
                  b.readVarInt(),
                  b.readUUID(),
                  b.readUtf(48),
                  b.readUtf(32),
                  b.readVarLong());
            });
  }

  private AccessPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    MenuPayloads.toServer(
        registrar,
        Command.TYPE,
        Command.CODEC,
        NetworkAccessMenu.class,
        NetworkAccessMenu::command);
    MenuPayloads.toClient(
        registrar, View.TYPE, View.CODEC, NetworkAccessMenu.class, NetworkAccessMenu::accept);
  }
}
