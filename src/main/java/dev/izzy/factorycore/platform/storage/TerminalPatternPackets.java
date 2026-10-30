package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.crafting.PatternHolderEntity;
import dev.izzy.factorycore.platform.crafting.WorldCrafting;
import dev.izzy.factorycore.platform.session.WireEnums;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The terminal's Patterns view (ergonomics Q3): a scrolled window of the Crafters this terminal's
 * segment can use and the patterns in each. Commands name a row of the current window and a slot;
 * the server owns which Crafter that is and checks EDIT there before each change.
 */
public final class TerminalPatternPackets {
  /**
   * WINDOW scrolls so Crafter {@code selection} is first; CLICK uses slot {@code slot} of row
   * {@code selection}.
   */
  public static final int WINDOW = 0, CLICK = 1;

  /** Crafters shown at once, pattern slots per Crafter, and the furthest scroll. */
  public static final int ROWS = 3,
      SLOTS = PatternHolderEntity.SLOTS,
      MAX_OFFSET = WorldCrafting.MAX_UNITS;

  /**
   * The outcome of the last click, shown as {@code gui.factorycore.terminal.patterns.status.<key>}.
   */
  public enum Status {
    READY,
    TAKEN,
    INSERTED,
    SWAPPED,
    /** The viewer lacks EDIT where the Crafter is. */
    DENIED,
    /** The cursor holds something the Crafter does not take. */
    REFUSED,
    /** The row's Crafter is gone or no longer visible here; the window was refreshed. */
    GONE,
    NO_POWER;

    public String key() {
      return EnumKeys.key(this);
    }
  }

  public record Command(int menu, UUID session, long revision, int action, int selection, int slot)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, "terminal_pattern_command"));

    public Command {
      Objects.requireNonNull(session);
      if (action < WINDOW
          || action > CLICK
          || selection < 0
          || selection > (action == WINDOW ? MAX_OFFSET : ROWS - 1)
          || slot < 0
          || slot >= SLOTS) throw new IllegalArgumentException("Invalid pattern command bounds");
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
              buffer.writeVarInt(value.slot);
            },
            buffer ->
                new Command(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()));
  }

  /**
   * One pattern slot: the output's registry ID ({@code icon}, empty for an empty slot), whether it
   * is a fluid, a processing pattern, and whether it matches the terminal search.
   */
  public record Entry(String icon, boolean fluid, boolean processing, boolean match) {
    public static final Entry EMPTY = new Entry("", false, false, false);

    public Entry {
      if (icon.length() > 256) throw new IllegalArgumentException("Invalid pattern entry");
    }

    static void write(RegistryFriendlyByteBuf buffer, Entry value) {
      buffer.writeUtf(value.icon, 256);
      buffer.writeBoolean(value.fluid);
      buffer.writeBoolean(value.processing);
      buffer.writeBoolean(value.match);
    }

    static Entry read(RegistryFriendlyByteBuf buffer) {
      return new Entry(
          buffer.readUtf(256), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
    }
  }

  /**
   * One Crafter: where it is, whether it sits in a branch that offers its patterns through its
   * gateways ({@code published}), whether the viewer may move its patterns, and its nine slots.
   */
  public record Crafter(
      BlockPos position,
      String dimension,
      boolean published,
      boolean editable,
      List<Entry> patterns) {
    public Crafter {
      Objects.requireNonNull(position);
      patterns = List.copyOf(patterns);
      if (dimension.length() > 256 || patterns.size() != SLOTS)
        throw new IllegalArgumentException("Invalid pattern crafter row");
    }

    static void write(RegistryFriendlyByteBuf buffer, Crafter value) {
      buffer.writeBlockPos(value.position);
      buffer.writeUtf(value.dimension, 256);
      buffer.writeBoolean(value.published);
      buffer.writeBoolean(value.editable);
      for (var entry : value.patterns) Entry.write(buffer, entry);
    }

    static Crafter read(RegistryFriendlyByteBuf buffer) {
      var position = buffer.readBlockPos();
      var dimension = buffer.readUtf(256);
      boolean published = buffer.readBoolean(), editable = buffer.readBoolean();
      var patterns = new ArrayList<Entry>(SLOTS);
      for (int slot = 0; slot < SLOTS; slot++) patterns.add(Entry.read(buffer));
      return new Crafter(position, dimension, published, editable, patterns);
    }
  }

  /**
   * A window of at most {@link #ROWS} Crafters from {@code offset} of {@code total} that match the
   * terminal search, and the last click's outcome.
   */
  public record View(
      int menu,
      UUID session,
      long revision,
      int offset,
      int total,
      Status status,
      List<Crafter> crafters)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_pattern_view"));

    public View {
      Objects.requireNonNull(session);
      Objects.requireNonNull(status);
      crafters = List.copyOf(crafters);
      if (offset < 0
          || offset > MAX_OFFSET
          || total < 0
          || total > MAX_OFFSET
          || crafters.size() > ROWS) throw new IllegalArgumentException("Invalid pattern view");
    }

    /** Before the first server publication. */
    public static View empty(int menu, UUID session) {
      return new View(menu, session, 0, 0, 0, Status.READY, List.of());
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
              buffer.writeVarLong(value.revision);
              buffer.writeVarInt(value.offset);
              buffer.writeVarInt(value.total);
              WireEnums.write(buffer, value.status);
              buffer.writeVarInt(value.crafters.size());
              for (var crafter : value.crafters) Crafter.write(buffer, crafter);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              var session = buffer.readUUID();
              long revision = buffer.readVarLong();
              int offset = buffer.readVarInt(), total = buffer.readVarInt();
              var status = WireEnums.read(buffer, Status.class);
              int size = buffer.readVarInt();
              if (size < 0 || size > ROWS) throw new IllegalArgumentException("Pattern row count");
              var crafters = new ArrayList<Crafter>(size);
              for (int i = 0; i < size; i++) crafters.add(Crafter.read(buffer));
              return new View(menu, session, revision, offset, total, status, crafters);
            });
  }

  private TerminalPatternPackets() {}
}
