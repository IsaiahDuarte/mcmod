package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class BufferSettingsPackets {
  public static final int MODE = 0,
      FILTER = 1,
      RATE = 2,
      TARGET = 3,
      REGISTRY = 4,
      TAG = 5,
      EXACT = 6,
      REMOVE = 7,
      CLEAR = 8,
      APPLY = 9,
      RESET = 10,
      TAKE = 11,
      DEPOSIT = 12,
      RETURN = 13,
      // B1 ghost slots: add the cursor item; cycle rule {@code selection}'s match (text: shown ID).
      CARRIED = 14,
      CYCLE = 15;

  /**
   * MODE and FILTER with {@code value} 0 cycle to the next choice; a value of n picks choice n - 1
   * directly (playtest H3a: the screen shows every choice). An out-of-range choice is invalid.
   */
  public static <T> T chosen(T[] choices, int value) {
    if (value > choices.length) throw new IllegalArgumentException("Choice");
    return choices[value - 1];
  }

  public record Command(
      int menu,
      UUID session,
      long revision,
      int action,
      int selection,
      int value,
      String text,
      byte[] fingerprint)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, "buffer_settings_command"));

    public Command {
      fingerprint = fingerprint.clone();
      if (action < 0
          || action > CYCLE
          || selection < 0
          || selection > 35
          || value < 0
          || value > BufferPolicy.MAX_TARGET
          || text.length() > 256
          || fingerprint.length != 32)
        throw new IllegalArgumentException("Invalid buffer command bounds");
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
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              b.writeLong(v.revision);
              b.writeVarInt(v.action);
              b.writeVarInt(v.selection);
              b.writeVarInt(v.value);
              b.writeUtf(v.text, 256);
              b.writeByteArray(v.fingerprint);
            },
            b ->
                new Command(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readLong(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readUtf(256),
                    b.readByteArray(32)));
  }

  public record Row(int slot, String icon, String name, int count) {
    public Row {
      if (slot < 0
          || slot > 8
          || icon.length() > 256
          || name.length() > 96
          || count < 0
          || count > 64) throw new IllegalArgumentException("Invalid buffer row");
    }
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      String mode,
      String filter,
      int rate,
      int target,
      List<FilterEdits.Rule> rules,
      List<Row> slots,
      String status,
      boolean editable,
      boolean busy,
      boolean dirty,
      long staged,
      long moved,
      int requested)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "buffer_settings_view"));

    public View {
      rules = List.copyOf(rules);
      slots = List.copyOf(slots);
      if (mode.length() > 16
          || filter.length() > 16
          || rate < 1
          || rate > ThroughputTier.ENDGAME.bufferRate()
          || target < 0
          || target > BufferPolicy.MAX_TARGET
          || rules.size() > 16
          || slots.size() > 9
          || status.length() > 64
          || staged < 0
          || moved < 0
          || requested < 0
          || requested > 64) throw new IllegalArgumentException("Invalid buffer view");
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
              b.writeLong(v.revision);
              b.writeUtf(v.mode, 16);
              b.writeUtf(v.filter, 16);
              b.writeVarInt(v.rate);
              b.writeVarInt(v.target);
              b.writeVarInt(v.rules.size());
              for (var rule : v.rules) FilterEdits.Rule.write(b, rule);
              b.writeVarInt(v.slots.size());
              for (var row : v.slots) {
                b.writeVarInt(row.slot);
                b.writeUtf(row.icon, 256);
                b.writeUtf(row.name, 96);
                b.writeVarInt(row.count);
              }
              b.writeUtf(v.status, 64);
              b.writeBoolean(v.editable);
              b.writeBoolean(v.busy);
              b.writeBoolean(v.dirty);
              b.writeVarLong(v.staged);
              b.writeVarLong(v.moved);
              b.writeVarInt(v.requested);
            },
            b -> {
              int id = b.readVarInt();
              UUID nonce = b.readUUID();
              long revision = b.readLong();
              String mode = b.readUtf(16), filter = b.readUtf(16);
              int rate = b.readVarInt(), target = b.readVarInt(), size = b.readVarInt();
              if (size < 0 || size > 16) throw new IllegalArgumentException("Rule count");
              var rules = new ArrayList<FilterEdits.Rule>();
              for (int i = 0; i < size; i++) rules.add(FilterEdits.Rule.read(b));
              size = b.readVarInt();
              if (size < 0 || size > 9) throw new IllegalArgumentException("Slot count");
              var rows = new ArrayList<Row>();
              for (int i = 0; i < size; i++)
                rows.add(new Row(b.readVarInt(), b.readUtf(256), b.readUtf(96), b.readVarInt()));
              return new View(
                  id,
                  nonce,
                  revision,
                  mode,
                  filter,
                  rate,
                  target,
                  rules,
                  rows,
                  b.readUtf(64),
                  b.readBoolean(),
                  b.readBoolean(),
                  b.readBoolean(),
                  b.readVarLong(),
                  b.readVarLong(),
                  b.readVarInt());
            });
  }

  private BufferSettingsPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var r = event.registrar("4");
    MenuPayloads.toServer(
        r, Command.TYPE, Command.CODEC, BufferSettingsMenu.class, BufferSettingsMenu::command);
    MenuPayloads.toClient(
        r, View.TYPE, View.CODEC, BufferSettingsMenu.class, BufferSettingsMenu::accept);
  }
}
