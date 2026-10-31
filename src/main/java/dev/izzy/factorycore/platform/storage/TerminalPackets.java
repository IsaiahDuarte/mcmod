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

  /**
   * Q62: the Items view, sent as the CRAFTABLES selection and saved with the preferences. STORED
   * shows stock only, ALL adds craftable items without stock, CRAFTABLE shows only craftable items
   * (stocked or not). STORED and ALL keep the old off/on values.
   */
  public static final int VIEW_STORED = 0, VIEW_ALL = 1, VIEW_CRAFTABLE = 2;

  /**
   * Q18: DEPOSIT_ALL deposits every main-inventory stack matching own slot {@code selection} (exact
   * key, checked by {@code fingerprint}), one stack after another.
   */
  public static final int DEPOSIT_ALL = 18;

  /**
   * Q63 grid shortcuts. DEPOSIT_ROW stores {@code amount} of visible row {@code selection}'s item
   * from the first own slot holding it; CURSOR_ONE adds one of row {@code selection} to the cursor;
   * DEPOSIT_REGION stores every stack of the hotbar ({@code selection} 0) or the main inventory
   * (1); SWAP stores hotbar slot {@code amount} − 1 and fills it with a stack of row {@code
   * selection}.
   */
  public static final int DEPOSIT_ROW = 19, CURSOR_ONE = 20, DEPOSIT_REGION = 21, SWAP = 22;

  /**
   * Q67 saved views. VIEWS acts on saved view {@code selection} (0 to {@link #SAVED_VIEWS} − 1):
   * {@code amount} {@link #VIEW_APPLY} applies it, {@link #VIEW_SAVE} saves the current search,
   * sort and view into it, {@link #VIEW_CLEAR} empties it.
   */
  public static final int VIEWS = 23,
      SAVED_VIEWS = 5,
      VIEW_APPLY = 1,
      VIEW_SAVE = 2,
      VIEW_CLEAR = 3;

  /**
   * Q67: one saved view. {@code sort} keeps only the sort mode and direction; the layout flags
   * (Tall, recipe-viewer sync, auto-focus) stay as they are when it is applied.
   */
  public record SavedView(boolean set, String search, int sort, int view) {
    public static final SavedView NONE = new SavedView(false, "", TerminalSearchSupport.NAME, 0);

    public SavedView {
      if (search.length() > 128
          || !TerminalSearchSupport.validSort(sort)
          || (sort & ~(TerminalSearchSupport.mode(sort) | TerminalSearchSupport.REVERSED)) != 0
          || view < VIEW_STORED
          || view > VIEW_CRAFTABLE
          || (!set && !equals(search, sort, view)))
        throw new IllegalArgumentException("Invalid saved view");
    }

    private static boolean equals(String search, int sort, int view) {
      return search.isEmpty() && sort == TerminalSearchSupport.NAME && view == 0;
    }

    /** The parts of {@code sort} a saved view keeps. */
    public static int sortOf(int sort) {
      return TerminalSearchSupport.mode(sort) | (sort & TerminalSearchSupport.REVERSED);
    }
  }

  public static final int MAX_ROWS = 256, MAX_OFFSET = 65536;

  /**
   * Q63: the most distinct keys a view reports as read before filtering ("showing X of Y"); the
   * search's byte budget keeps the real count far lower.
   */
  public static final int MAX_ALL = 1 << 20;

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
          || action > VIEWS
          || selection < 0
          || selection
              >= (action == WINDOW
                  ? MAX_OFFSET + 1
                  : action == WITHDRAW
                          || action == TAKE_CURSOR
                          || action == HALF_CURSOR
                          || action == FILL
                          || action == REQUEST
                          || action == DEPOSIT_ROW
                          || action == CURSOR_ONE
                          || action == SWAP
                      ? MAX_ROWS
                      : action == CRAFTABLES
                          ? 3
                          : action == SORT
                              ? 128
                              : action == DEPOSIT_REGION ? 2 : action == VIEWS ? SAVED_VIEWS : 36)
          || (action == SORT && !TerminalSearchSupport.validSort(selection))
          || amount < 1
          || amount
              > (action == WINDOW
                  ? MAX_ROWS
                  : action == SWAP ? 9 : action == VIEWS ? VIEW_CLEAR : 64)
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
      int view,
      boolean uncertain,
      int all,
      List<SavedView> saved)
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
          VIEW_STORED,
          false,
          rows.size(),
          List.of());
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
        int sort,
        int offset,
        int total,
        int size,
        int kind,
        String used,
        String capacity,
        boolean infinite,
        boolean external,
        int view,
        boolean uncertain,
        int all) {
      this(
          menu, session, revision, status, rows, next, busy, staged, search, sort, offset, total,
          size, kind, used, capacity, infinite, external, view, uncertain, all, List.of());
    }

    /** Whether craftable items without stock are shown (the All or Craftable view). */
    public boolean craftables() {
      return view != VIEW_STORED;
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
          || view < VIEW_STORED
          || view > VIEW_CRAFTABLE
          || all < 0
          || all > MAX_ALL
          || used.length() > 32
          || capacity.length() > 32) throw new IllegalArgumentException("Invalid terminal window");
      rows = List.copyOf(rows);
      saved = List.copyOf(saved);
      if (!saved.isEmpty() && saved.size() != SAVED_VIEWS)
        throw new IllegalArgumentException("Saved views");
      Objects.requireNonNull(status);
      if (rows.size() > MAX_ROWS || staged.length() > 32)
        throw new IllegalArgumentException("Invalid terminal view bounds");
    }

    private static List<SavedView> readSaved(RegistryFriendlyByteBuf buffer) {
      int count = buffer.readVarInt();
      if (count != 0 && count != SAVED_VIEWS) throw new IllegalArgumentException("Saved views");
      var saved = new ArrayList<SavedView>(count);
      for (int index = 0; index < count; index++)
        saved.add(
            new SavedView(
                buffer.readBoolean(),
                buffer.readUtf(128),
                buffer.readVarInt(),
                buffer.readVarInt()));
      return saved;
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
              buffer.writeVarInt(value.view);
              buffer.writeBoolean(value.uncertain);
              buffer.writeVarInt(value.all);
              buffer.writeVarInt(value.saved.size());
              for (var saved : value.saved) {
                buffer.writeBoolean(saved.set());
                buffer.writeUtf(saved.search(), 128);
                buffer.writeVarInt(saved.sort());
                buffer.writeVarInt(saved.view());
              }
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
                  buffer.readVarInt(),
                  buffer.readBoolean(),
                  buffer.readVarInt(),
                  readSaved(buffer));
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
    var registrar = event.registrar("15");
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
        TerminalPatternPackets.Command.TYPE,
        TerminalPatternPackets.Command.CODEC,
        TerminalMenu.class,
        TerminalMenu::patternCommand);
    MenuPayloads.toClient(
        registrar,
        TerminalPatternPackets.View.TYPE,
        TerminalPatternPackets.View.CODEC,
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
    MenuPayloads.toServer(
        registrar,
        TerminalCraftingPackets.ProcessingFill.TYPE,
        TerminalCraftingPackets.ProcessingFill.CODEC,
        TerminalMenu.class,
        TerminalMenu::gridProcessingFill);
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
