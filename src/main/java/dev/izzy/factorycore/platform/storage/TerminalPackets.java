package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import dev.izzy.factorycore.platform.session.WireEnums;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class TerminalPackets {
  /**
   * The storage view's state or last action's outcome, shown as {@code
   * gui.factorycore.terminal.status.<key>}. The first group belongs to the terminal and its
   * container actions; the second mirrors {@link OperationResult.Reason}.
   */
  public enum Status {
    LOADING,
    READY,
    NO_POWER,
    EMPTY,
    FILLED,
    EMPTIED,
    CHARGED,
    DISCHARGED,
    NO_CONTAINER,
    NO_ENERGY_ITEM,
    ITEM_FULL,
    ITEM_EMPTY,
    OK,
    FULL,
    TECHNICAL_LIMIT,
    CATALOG_LIMIT,
    RESERVATION_LIMIT,
    SHORTAGE,
    RESERVED,
    OFFLINE,
    WRONG_RESOURCE,
    DENIED,
    HANDLER_FAULT,
    UNCERTAIN,
    RATE_LIMIT,
    OVERLAP,
    UNSUPPORTED,
    BUSY,
    REDSTONE;

    public String key() {
      return EnumKeys.key(this);
    }

    public static Status of(OperationResult.Reason reason) {
      return EnumKeys.require(Status.class, EnumKeys.key(reason));
    }
  }

  public static final int SEARCH = 0, HOME = 1, NEXT = 2, WITHDRAW = 3, DEPOSIT = 4, RETURN = 5;

  /**
   * KIND switches the view (selection 0 items, 1 fluids, 2 energy); FILL fills the first compatible
   * container from fluid row {@code selection}; EMPTY empties own slot {@code selection};
   * CHARGE/DISCHARGE move up to 1,000 FE into/out of the energy item in a slot.
   */
  public static final int KIND = 6, FILL = 7, EMPTY = 8, CHARGE = 9, DISCHARGE = 10, SORT = 11;

  public static final int WINDOW = 12, TAKE_CURSOR = 13, HALF_CURSOR = 14, DEPOSIT_CURSOR = 15;

  /**
   * A3: CRAFTABLES sets the "Show craftable" preference ({@code selection} 0 off, 1 on); REQUEST
   * asks for craftable row {@code selection} to be selected in the Jobs list for a request. A5:
   * with non-empty {@code text}, REQUEST names a plain item ID (from a recipe viewer) instead; it
   * is honoured only if a visible pattern makes that item.
   */
  public static final int CRAFTABLES = 16, REQUEST = 17;

  public static final int MAX_ROWS = 256, MAX_OFFSET = 65536;

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
          || action > REQUEST
          || selection < 0
          || selection
              >= (action == WINDOW
                  ? MAX_OFFSET + 1
                  : action == WITHDRAW
                          || action == TAKE_CURSOR
                          || action == HALF_CURSOR
                          || action == FILL
                          || action == REQUEST
                      ? MAX_ROWS
                      : action == CRAFTABLES ? 2 : 36)
          || (action == SORT && !TerminalSearchSupport.validSort(selection))
          || amount < 1
          || amount > (action == WINDOW ? MAX_ROWS : 64)
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

  /**
   * {@code craftable}: a visible pattern makes this resource; with zero total it is a ghost row.
   */
  public record Row(
      int token,
      String icon,
      String name,
      String total,
      String reserved,
      String available,
      boolean craftable) {
    public Row(
        int token, String icon, String name, String total, String reserved, String available) {
      this(token, icon, name, total, reserved, available, false);
    }

    public Row {
      if (token < 0
          || token >= MAX_ROWS
          || icon.length() > 256
          || name.length() > 96
          || total.length() > 32
          || reserved.length() > 32
          || available.length() > 32)
        throw new IllegalArgumentException("Invalid terminal row bounds");
    }

    /** None stored: a craftable ghost row. */
    public boolean none() {
      return total.equals("0");
    }
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      Status status,
      List<Row> rows,
      boolean next,
      boolean busy,
      String staged,
      String search,
      int sort,
      int offset,
      int total,
      int size,
      int kind,
      String used,
      String capacity,
      boolean infinite,
      boolean external,
      boolean craftables,
      boolean uncertain)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_view"));

    public View(
        int menu,
        UUID session,
        long revision,
        Status status,
        List<Row> rows,
        boolean next,
        boolean busy,
        String staged) {
      this(
          menu,
          session,
          revision,
          status,
          rows,
          next,
          busy,
          staged,
          "",
          TerminalSearchSupport.NAME);
    }

    public View(
        int menu,
        UUID session,
        long revision,
        Status status,
        List<Row> rows,
        boolean next,
        boolean busy,
        String staged,
        String search,
        int sort) {
      this(
          menu,
          session,
          revision,
          status,
          rows,
          next,
          busy,
          staged,
          search,
          sort,
          0,
          rows.size(),
          8,
          0,
          "0",
          "0",
          false,
          false,
          false,
          false);
    }

    public View {
      if (search.length() > 128 || !TerminalSearchSupport.validSort(sort))
        throw new IllegalArgumentException("Invalid terminal preferences");
      if (offset < 0
          || total < 0
          || total > MAX_OFFSET
          || offset > total
          || size < 1
          || size > MAX_ROWS
          || kind < 0
          || kind > 2
          || used.length() > 32
          || capacity.length() > 32) throw new IllegalArgumentException("Invalid terminal window");
      rows = List.copyOf(rows);
      Objects.requireNonNull(status);
      if (rows.size() > MAX_ROWS || staged.length() > 32)
        throw new IllegalArgumentException("Invalid terminal view bounds");
    }

    /** Something waits in the player's staging record to be returned. */
    public boolean staging() {
      return !staged.equals("0");
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
              WireEnums.write(buffer, value.status);
              buffer.writeVarInt(value.rows.size());
              for (var row : value.rows) {
                buffer.writeVarInt(row.token);
                buffer.writeUtf(row.icon, 256);
                buffer.writeUtf(row.name, 96);
                buffer.writeUtf(row.total, 32);
                buffer.writeUtf(row.reserved, 32);
                buffer.writeUtf(row.available, 32);
                buffer.writeBoolean(row.craftable);
              }
              buffer.writeBoolean(value.next);
              buffer.writeBoolean(value.busy);
              buffer.writeUtf(value.staged, 32);
              buffer.writeUtf(value.search, 128);
              buffer.writeVarInt(value.sort);
              buffer.writeVarInt(value.offset);
              buffer.writeVarInt(value.total);
              buffer.writeVarInt(value.size);
              buffer.writeVarInt(value.kind);
              buffer.writeUtf(value.used, 32);
              buffer.writeUtf(value.capacity, 32);
              buffer.writeBoolean(value.infinite);
              buffer.writeBoolean(value.external);
              buffer.writeBoolean(value.craftables);
              buffer.writeBoolean(value.uncertain);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              UUID session = buffer.readUUID();
              long revision = buffer.readLong();
              var status = WireEnums.read(buffer, Status.class);
              int size = buffer.readVarInt();
              if (size < 0 || size > MAX_ROWS)
                throw new IllegalArgumentException("Terminal row count");
              var rows = new ArrayList<Row>();
              for (int index = 0; index < size; index++)
                rows.add(
                    new Row(
                        buffer.readVarInt(),
                        buffer.readUtf(256),
                        buffer.readUtf(96),
                        buffer.readUtf(32),
                        buffer.readUtf(32),
                        buffer.readUtf(32),
                        buffer.readBoolean()));
              return new View(
                  menu,
                  session,
                  revision,
                  status,
                  rows,
                  buffer.readBoolean(),
                  buffer.readBoolean(),
                  buffer.readUtf(32),
                  buffer.readUtf(128),
                  buffer.readVarInt(),
                  buffer.readVarInt(),
                  buffer.readVarInt(),
                  buffer.readVarInt(),
                  buffer.readVarInt(),
                  buffer.readUtf(32),
                  buffer.readUtf(32),
                  buffer.readBoolean(),
                  buffer.readBoolean(),
                  buffer.readBoolean(),
                  buffer.readBoolean());
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
    var registrar = event.registrar("6");
    MenuPayloads.toServer(
        registrar, Command.TYPE, Command.CODEC, TerminalMenu.class, TerminalMenu::command);
    MenuPayloads.toServer(
        registrar,
        TerminalJobPackets.Command.TYPE,
        TerminalJobPackets.Command.CODEC,
        TerminalMenu.class,
        TerminalMenu::jobCommand);
    MenuPayloads.toClient(
        registrar,
        TerminalJobPackets.View.TYPE,
        TerminalJobPackets.View.CODEC,
        TerminalMenu.class,
        TerminalMenu::accept);
    MenuPayloads.toServer(
        registrar,
        TerminalCraftingPackets.Command.TYPE,
        TerminalCraftingPackets.Command.CODEC,
        TerminalMenu.class,
        TerminalMenu::gridCommand);
    MenuPayloads.toServer(
        registrar,
        TerminalCraftingPackets.Fill.TYPE,
        TerminalCraftingPackets.Fill.CODEC,
        TerminalMenu.class,
        TerminalMenu::gridFill);
    MenuPayloads.toClient(
        registrar,
        TerminalCraftingPackets.View.TYPE,
        TerminalCraftingPackets.View.CODEC,
        TerminalMenu.class,
        TerminalMenu::accept);
    MenuPayloads.toClient(
        registrar, View.TYPE, View.CODEC, TerminalMenu.class, TerminalMenu::accept);
  }
}
