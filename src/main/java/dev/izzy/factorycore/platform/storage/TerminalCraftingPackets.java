package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.crafting.GridRequirement;
import dev.izzy.factorycore.core.crafting.ProcessingTransfer;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.WireEnums;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Manual crafting grid payloads. Commands name cells, own-inventory slots or current terminal rows
 * only; the server resolves exact keys and never trusts a client stack, recipe or quantity.
 */
public final class TerminalCraftingPackets {
  /**
   * The crafting grid's state or last craft's outcome, shown as {@code
   * gui.factorycore.terminal.grid.status.<key>}.
   */
  public enum GridStatus {
    EMPTY,
    FILLED,
    READY,
    GATHERING,
    CRAFTED,
    PARTIAL,
    ENCODED,
    NO_RECIPE,
    SHORTAGE,
    RESERVED,
    FULL,
    NO_BLANK,
    NO_POWER,
    PROCESSING_MODE,
    UNSUPPORTED,
    /** Q4: an encoded pattern was loaded into the grid; Encode rewrites it. */
    LOADED,
    /** As LOADED, for a crafting pattern that allows substitutes. */
    LOADED_SUBSTITUTES,
    /** Q4: Encode rewrote the loaded pattern in place. */
    REWRITTEN;

    public String key() {
      return EnumKeys.key(this);
    }

    /** Nothing to report: an empty grid, or one ready to craft. */
    public boolean quiet() {
      return this == EMPTY || this == READY;
    }
  }

  /**
   * Ghost from own slot (source=slot, fingerprint), current row (source=row), clear, craft, and
   * encode the current recipe onto a blank pattern from the player's main inventory.
   */
  public static final int SET_OWN = 0, SET_ROW = 1, CLEAR = 2, CRAFT = 3, ENCODE = 4;

  /** MODE: cell 0 = crafting, 1 = processing. COUNT: cell 0–11, crafts = units 1–64. */
  public static final int MODE = 5, COUNT = 6;

  /** Q4: load the encoded pattern in main-inventory slot {@code source} into the grid. */
  public static final int LOAD = 7;

  /**
   * Q112: MAIN makes processing output cell {@code cell} (10 or 11) the main output by swapping it,
   * with its count, into cell 9; the pattern then makes that output and the others become
   * byproducts.
   */
  public static final int MAIN = 8;

  /** Nine input cells plus three processing output cells (indices 9–11). */
  public static final int CELLS = TerminalCrafting.ALL_CELLS;

  public record Command(
      int menu,
      UUID session,
      long viewRevision,
      long gridRevision,
      int action,
      int cell,
      int source,
      int crafts,
      byte[] fingerprint)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_grid_command"));

    public Command {
      fingerprint = fingerprint.clone();
      if (action < SET_OWN
          || action > MAIN
          || (action == MAIN && cell != 10 && cell != 11)
          || cell < -1
          || cell >= CELLS
          || source < 0
          || source >= (action == SET_ROW ? TerminalPackets.MAX_ROWS : TerminalCrafting.SLOTS)
          || crafts < 1
          || crafts > GridRequirement.MAX_CRAFTS
          || fingerprint.length != 32)
        throw new IllegalArgumentException("Invalid grid command bounds");
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
              buffer.writeLong(value.viewRevision);
              buffer.writeLong(value.gridRevision);
              buffer.writeVarInt(value.action);
              buffer.writeVarInt(value.cell);
              buffer.writeVarInt(value.source);
              buffer.writeVarInt(value.crafts);
              buffer.writeByteArray(value.fingerprint);
            },
            buffer ->
                new Command(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readByteArray(32)));
  }

  /** Base registry icons only; exact components stay on the server. Empty string is empty. */
  public record View(
      int menu,
      UUID session,
      long gridRevision,
      List<String> cells,
      List<Integer> counts,
      boolean processing,
      String result,
      String resultName,
      int resultCount,
      GridStatus status,
      int crafted,
      int fluidMask)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_grid_view"));

    public View(
        int menu,
        UUID session,
        long gridRevision,
        List<String> cells,
        List<Integer> counts,
        boolean processing,
        String result,
        String resultName,
        int resultCount,
        GridStatus status,
        int crafted) {
      this(
          menu,
          session,
          gridRevision,
          cells,
          counts,
          processing,
          result,
          resultName,
          resultCount,
          status,
          crafted,
          0);
    }

    public boolean fluid(int cell) {
      if (cell < 0 || cell >= CELLS) throw new IllegalArgumentException("Grid cell");
      return (fluidMask & (1 << cell)) != 0;
    }

    /** Display units: mB for fluids, item units otherwise; processing wire counts remain 1–64. */
    public int amount(int cell) {
      return fluid(cell) ? counts.get(cell) * TerminalCrafting.FLUID_UNIT : counts.get(cell);
    }

    public View {
      cells = List.copyOf(cells);
      counts = List.copyOf(counts);
      Objects.requireNonNull(status);
      if (fluidMask < 0
          || fluidMask >= (1 << CELLS)
          || !processing && fluidMask != 0
          || cells.size() != CELLS
          || counts.size() != CELLS
          || counts.stream().anyMatch(count -> count < 1 || count > 64)
          || cells.stream().anyMatch(cell -> cell.length() > 256)
          || result.length() > 256
          || resultName.length() > 96
          || resultCount < 0
          || resultCount > 64
          || crafted < 0
          || crafted > GridRequirement.MAX_CRAFTS)
        throw new IllegalArgumentException("Invalid grid view bounds");
      for (int cell = 0; cell < CELLS; cell++)
        if ((fluidMask & (1 << cell)) != 0 && cells.get(cell).isEmpty())
          throw new IllegalArgumentException("Empty fluid ghost");
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
              buffer.writeLong(value.gridRevision);
              for (var cell : value.cells) buffer.writeUtf(cell, 256);
              for (var count : value.counts) buffer.writeVarInt(count);
              buffer.writeBoolean(value.processing);
              buffer.writeUtf(value.result, 256);
              buffer.writeUtf(value.resultName, 96);
              buffer.writeVarInt(value.resultCount);
              WireEnums.write(buffer, value.status);
              buffer.writeVarInt(value.crafted);
              buffer.writeVarInt(value.fluidMask);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              UUID session = buffer.readUUID();
              long revision = buffer.readLong();
              var cells = new ArrayList<String>();
              for (int index = 0; index < CELLS; index++) cells.add(buffer.readUtf(256));
              var counts = new ArrayList<Integer>();
              for (int index = 0; index < CELLS; index++) counts.add(buffer.readVarInt());
              boolean processing = buffer.readBoolean();
              return new View(
                  menu,
                  session,
                  revision,
                  cells,
                  counts,
                  processing,
                  buffer.readUtf(256),
                  buffer.readUtf(96),
                  buffer.readVarInt(),
                  WireEnums.read(buffer, GridStatus.class),
                  buffer.readVarInt(),
                  buffer.readVarInt());
            });
  }

  private TerminalCraftingPackets() {}

  /**
   * Recipe-viewer transfer: nine registry item IDs ("" = empty) set as crafting ghost cells by
   * registry identity. Ghost cells move nothing; a later craft revalidates real stock.
   */
  public record Fill(int menu, UUID session, List<String> items) implements CustomPacketPayload {
    public static final int MAX_ID = 256;
    public static final Type<Fill> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_grid_fill"));

    public Fill {
      items = List.copyOf(items);
      if (items.size() != GridRequirement.CELLS
          || items.stream()
              .anyMatch(
                  id ->
                      id.length() > MAX_ID
                          || !id.isEmpty() && ResourceLocation.tryParse(id) == null))
        throw new IllegalArgumentException("Invalid grid fill bounds");
    }

    @Override
    public Type<Fill> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Fill> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              for (var id : value.items) buffer.writeUtf(id, MAX_ID);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              var session = buffer.readUUID();
              var items = new ArrayList<String>(GridRequirement.CELLS);
              for (int cell = 0; cell < GridRequirement.CELLS; cell++)
                items.add(buffer.readUtf(MAX_ID));
              return new Fill(menu, session, items);
            });
  }

  /**
   * Q5: recipe-viewer transfer of any non-crafting recipe into the processing grid: up to nine
   * inputs and three outputs, each a registry ID of an item or fluid and its grid units (items one
   * each, fluids 250 mB each, 1–64). Ghost cells move nothing.
   */
  public record ProcessingFill(int menu, UUID session, List<Cell> inputs, List<Cell> outputs)
      implements CustomPacketPayload {
    public static final Type<ProcessingFill> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, "terminal_grid_processing_fill"));

    /** One cell: a fluid or item registry ID and its grid units. */
    public record Cell(boolean fluid, String id, int units) {
      public Cell {
        if (id.isEmpty()
            || id.length() > Fill.MAX_ID
            || ResourceLocation.tryParse(id) == null
            || units < 1
            || units > ProcessingTransfer.MAX_UNITS)
          throw new IllegalArgumentException("Invalid processing fill cell");
      }

      static void write(RegistryFriendlyByteBuf buffer, Cell cell) {
        buffer.writeBoolean(cell.fluid);
        buffer.writeUtf(cell.id, Fill.MAX_ID);
        buffer.writeVarInt(cell.units);
      }

      static Cell read(RegistryFriendlyByteBuf buffer) {
        return new Cell(buffer.readBoolean(), buffer.readUtf(Fill.MAX_ID), buffer.readVarInt());
      }
    }

    public ProcessingFill {
      inputs = List.copyOf(inputs);
      outputs = List.copyOf(outputs);
      if (inputs.isEmpty()
          || outputs.isEmpty()
          || inputs.size() > ProcessingTransfer.MAX_INPUTS
          || outputs.size() > ProcessingTransfer.MAX_OUTPUTS)
        throw new IllegalArgumentException("Invalid processing fill bounds");
    }

    @Override
    public Type<ProcessingFill> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ProcessingFill> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeVarInt(value.inputs.size());
              for (var cell : value.inputs) Cell.write(buffer, cell);
              buffer.writeVarInt(value.outputs.size());
              for (var cell : value.outputs) Cell.write(buffer, cell);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              var session = buffer.readUUID();
              int in = buffer.readVarInt();
              if (in < 1 || in > ProcessingTransfer.MAX_INPUTS)
                throw new IllegalArgumentException("Invalid processing fill bounds");
              var inputs = new ArrayList<Cell>(in);
              for (int index = 0; index < in; index++) inputs.add(Cell.read(buffer));
              int out = buffer.readVarInt();
              if (out < 1 || out > ProcessingTransfer.MAX_OUTPUTS)
                throw new IllegalArgumentException("Invalid processing fill bounds");
              var outputs = new ArrayList<Cell>(out);
              for (int index = 0; index < out; index++) outputs.add(Cell.read(buffer));
              return new ProcessingFill(menu, session, inputs, outputs);
            });
  }
}
