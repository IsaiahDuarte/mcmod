package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Opaque session/draft commands and bounded display rows; no principal, topology or raw keys. */
public final class MachinePresetPackets {
  public static final int MODE = 0,
      FILTER = 1,
      GOAL = 2,
      REGISTRY = 3,
      TAG = 4,
      SAMPLE = 5,
      REMOVE = 6,
      CLEAR = 7,
      DEPLOY = 8,
      RESET = 9,
      RETURN = 10,
      // B1 ghost slots: add the cursor item; cycle rule {@code selection}'s match (text: shown ID).
      CARRIED = 11,
      CYCLE = 12,
      // H6: apply as mode {@code selection} (an ordinal; text: goal), and set the filter mode.
      DEPLOY_AS = 13,
      FILTER_MODE = 14;

  public record Command(
      int menu,
      UUID session,
      long revision,
      int action,
      int selection,
      String text,
      byte[] fingerprint)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "machine_preset_command"));

    public Command {
      fingerprint = fingerprint.clone();
      if (action < 0
          || action > FILTER_MODE
          || selection < 0
          || selection > 35
          || text.length() > 256
          || fingerprint.length != 32)
        throw new IllegalArgumentException("Machine preset command bounds");
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
              b.writeVarInt(v.menu());
              b.writeUUID(v.session());
              b.writeLong(v.revision());
              b.writeVarInt(v.action());
              b.writeVarInt(v.selection());
              b.writeUtf(v.text(), 256);
              b.writeByteArray(v.fingerprint);
            },
            b ->
                new Command(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readLong(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readUtf(256),
                    b.readByteArray(32)));
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      ResourceKind kind,
      MachinePresets.Mode mode,
      ResourceFilter.Mode filter,
      long goal,
      List<FilterEdits.Rule> rules,
      String status,
      MachinePresets.Mode running,
      String reason,
      boolean editable,
      boolean busy,
      boolean dirty,
      long staged,
      boolean cleanup)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "machine_preset_view"));

    public View {
      rules = List.copyOf(rules);
      if (goal < 0
          || rules.size() > 16
          || status.length() > 32
          || reason.length() > 32
          || staged < 0) throw new IllegalArgumentException("Machine preset view bounds");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu());
              b.writeUUID(v.session());
              b.writeLong(v.revision());
              b.writeEnum(v.kind());
              b.writeEnum(v.mode());
              b.writeEnum(v.filter());
              b.writeLong(v.goal());
              b.writeVarInt(v.rules().size());
              for (var rule : v.rules()) FilterEdits.Rule.write(b, rule);
              b.writeUtf(v.status(), 32);
              b.writeEnum(v.running());
              b.writeUtf(v.reason(), 32);
              b.writeBoolean(v.editable());
              b.writeBoolean(v.busy());
              b.writeBoolean(v.dirty());
              b.writeVarLong(v.staged());
              b.writeBoolean(v.cleanup());
            },
            b -> {
              int id = b.readVarInt();
              UUID session = b.readUUID();
              long revision = b.readLong();
              var kind = b.readEnum(ResourceKind.class);
              var mode = b.readEnum(MachinePresets.Mode.class);
              var filter = b.readEnum(ResourceFilter.Mode.class);
              long goal = b.readLong();
              int size = b.readVarInt();
              if (size < 0 || size > 16) throw new IllegalArgumentException("Machine rule count");
              var rules = new ArrayList<FilterEdits.Rule>();
              for (int i = 0; i < size; i++) rules.add(FilterEdits.Rule.read(b));
              return new View(
                  id,
                  session,
                  revision,
                  kind,
                  mode,
                  filter,
                  goal,
                  rules,
                  b.readUtf(32),
                  b.readEnum(MachinePresets.Mode.class),
                  b.readUtf(32),
                  b.readBoolean(),
                  b.readBoolean(),
                  b.readBoolean(),
                  b.readVarLong(),
                  b.readBoolean());
            });
  }

  private MachinePresetPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var r = event.registrar("2");
    MenuPayloads.toServer(
        r,
        Command.TYPE,
        Command.CODEC,
        MachineSettingsMenu.class,
        MachineSettingsMenu::presetCommand);
    MenuPayloads.toClient(
        r, View.TYPE, View.CODEC, MachineSettingsMenu.class, MachineSettingsMenu::acceptPreset);
  }
}
