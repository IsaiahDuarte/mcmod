package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.GridRequirement;
import dev.izzy.factorycore.platform.FactoryCoreMod;
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
  /** Ghost from own slot (source=slot, fingerprint), current row (source=row), clear, craft. */
  public static final int SET_OWN = 0, SET_ROW = 1, CLEAR = 2, CRAFT = 3;

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
          || action > CRAFT
          || cell < -1
          || cell >= GridRequirement.CELLS
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
      if (cells.size() != GridRequirement.CELLS
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
              var cells = new java.util.ArrayList<String>();
              for (int index = 0; index < GridRequirement.CELLS; index++)
                cells.add(buffer.readUtf(256));
              return new View(
                  menu,
                  session,
                  revision,
                  cells,
                  buffer.readUtf(256),
                  buffer.readUtf(96),
                  buffer.readVarInt(),
                  buffer.readUtf(64),
                  buffer.readVarInt());
            });
  }

  private TerminalCraftingPackets() {}
}
