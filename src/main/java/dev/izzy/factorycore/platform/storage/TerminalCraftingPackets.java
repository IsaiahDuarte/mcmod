package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.GridRequirement;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.ArrayList;
import java.util.List;
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
   * Ghost from own slot (source=slot, fingerprint), current row (source=row), clear, craft, and
   * encode the current recipe onto a blank pattern from the player's main inventory.
   */
  public static final int SET_OWN = 0, SET_ROW = 1, CLEAR = 2, CRAFT = 3, ENCODE = 4;

  /** MODE: cell 0 = crafting, 1 = processing. COUNT: cell 0–11, crafts = units 1–64. */
  public static final int MODE = 5, COUNT = 6;

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
          || action > COUNT
          || cell < -1
          || cell >= CELLS
          || source < 0
          || source >= TerminalCrafting.SLOTS
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
      String status,
      int crafted)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_grid_view"));

    public View {
      cells = List.copyOf(cells);
      counts = List.copyOf(counts);
      if (cells.size() != CELLS
          || counts.size() != CELLS
          || counts.stream().anyMatch(count -> count < 1 || count > 64)
          || cells.stream().anyMatch(cell -> cell.length() > 256)
          || result.length() > 256
          || resultName.length() > 96
          || resultCount < 0
          || resultCount > 64
          || status.length() > 64
          || crafted < 0
          || crafted > GridRequirement.MAX_CRAFTS)
        throw new IllegalArgumentException("Invalid grid view bounds");
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
              buffer.writeUtf(value.status, 64);
              buffer.writeVarInt(value.crafted);
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
                  buffer.readUtf(64),
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
}
