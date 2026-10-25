package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Storage-mode Interface settings form. Reuses the bounded {@link MachineSettingsPackets.Form}; the
 * connector has no label, so the client sends an empty one and the server keeps the stored label.
 */
public final class ConnectorSettingsPackets {
  private static void write(RegistryFriendlyByteBuf b, MachineSettingsPackets.Form f) {
    b.writeUtf(f.label(), 64);
    b.writeEnum(f.kind());
    b.writeEnum(f.access());
    b.writeInt(f.priority());
    b.writeVarInt(f.firstSlot());
    b.writeVarInt(f.slotCount());
    b.writeVarInt(f.rate());
  }

  private static MachineSettingsPackets.Form read(RegistryFriendlyByteBuf b) {
    return new MachineSettingsPackets.Form(
        b.readUtf(64),
        b.readEnum(ResourceKind.class),
        b.readEnum(ConnectorSettings.Access.class),
        b.readInt(),
        b.readVarInt(),
        b.readVarInt(),
        b.readVarInt());
  }

  public record Apply(int menu, UUID session, long generation, MachineSettingsPackets.Form form)
      implements CustomPacketPayload {
    public static final Type<Apply> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, "connector_settings_apply"));

    @Override
    public Type<Apply> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Apply> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu());
              b.writeUUID(v.session());
              b.writeLong(v.generation());
              write(b, v.form());
            },
            b -> new Apply(b.readVarInt(), b.readUUID(), b.readLong(), read(b)));
  }

  /**
   * {@code binding} names a {@code reason.factorycore.*} key for the container registration; {@code
   * status} names a {@code gui.factorycore.machine.status.*} key for the last edit.
   */
  public record View(
      int menu,
      UUID session,
      long generation,
      MachineSettingsPackets.Form form,
      String side,
      String binding,
      String status,
      boolean editable,
      String tier,
      int maxRate,
      boolean throttled,
      int itemSlots,
      int tanks,
      String insertMode,
      List<FilterEdits.Rule> insertRules)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, "connector_settings_view"));

    public View {
      if (side.length() > 16
          || binding.length() > 32
          || status.length() > 32
          || tier.length() > 16
          || maxRate < 1
          || insertMode.length() > 16
          || insertRules.size() > dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS
          || maxRate > ThroughputTier.maxQuantum(form.kind())
          || itemSlots < 0
          || itemSlots > ConnectorEntity.MAX_REPORTED_SLOTS
          || tanks < 0
          || tanks > ConnectorEntity.MAX_REPORTED_SLOTS)
        throw new IllegalArgumentException("Connector view bounds");
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
              b.writeLong(v.generation());
              write(b, v.form());
              b.writeUtf(v.side(), 16);
              b.writeUtf(v.binding(), 32);
              b.writeUtf(v.status(), 32);
              b.writeBoolean(v.editable());
              b.writeUtf(v.tier(), 16);
              b.writeVarInt(v.maxRate());
              b.writeBoolean(v.throttled());
              b.writeVarInt(v.itemSlots());
              b.writeVarInt(v.tanks());
              b.writeUtf(v.insertMode(), 16);
              b.writeVarInt(v.insertRules().size());
              for (var rule : v.insertRules()) FilterEdits.Rule.write(b, rule);
            },
            b ->
                new View(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readLong(),
                    read(b),
                    b.readUtf(16),
                    b.readUtf(32),
                    b.readUtf(32),
                    b.readBoolean(),
                    b.readUtf(16),
                    b.readVarInt(),
                    b.readBoolean(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readUtf(16),
                    rules(b)));
  }

  private static List<FilterEdits.Rule> rules(RegistryFriendlyByteBuf b) {
    int size = b.readVarInt();
    if (size < 0 || size > dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS)
      throw new IllegalArgumentException("Insert rule count");
    var rules = new java.util.ArrayList<FilterEdits.Rule>(size);
    for (int i = 0; i < size; i++) rules.add(FilterEdits.Rule.read(b));
    return rules;
  }

  /**
   * B2 insert-filter draft edits on the server-held draft; Apply saves it with the form. MODE
   * cycles all → allow listed → block listed; the others mirror the Buffer's ghost-slot actions.
   */
  public record FilterCommand(int menu, UUID session, int action, int selection, String text)
      implements CustomPacketPayload {
    public static final int CARRIED = 0,
        CYCLE = 1,
        REMOVE = 2,
        CLEAR = 3,
        REGISTRY = 4,
        TAG = 5,
        MODE = 6;

    public static final Type<FilterCommand> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                FactoryCoreMod.MOD_ID, "connector_filter_command"));

    public FilterCommand {
      if (action < CARRIED
          || action > MODE
          || selection < 0
          || selection >= dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS
          || text.length() > 256) throw new IllegalArgumentException("Invalid filter command");
    }

    @Override
    public Type<FilterCommand> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, FilterCommand> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              b.writeVarInt(v.action);
              b.writeVarInt(v.selection);
              b.writeUtf(v.text, 256);
            },
            b ->
                new FilterCommand(
                    b.readVarInt(), b.readUUID(), b.readVarInt(), b.readVarInt(), b.readUtf(256)));
  }

  private ConnectorSettingsPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("3");
    MenuPayloads.toServer(
        registrar,
        FilterCommand.TYPE,
        FilterCommand.CODEC,
        ConnectorSettingsMenu.class,
        ConnectorSettingsMenu::filter);
    MenuPayloads.toServer(
        registrar,
        Apply.TYPE,
        Apply.CODEC,
        ConnectorSettingsMenu.class,
        ConnectorSettingsMenu::apply);
    MenuPayloads.toClient(
        registrar,
        View.TYPE,
        View.CODEC,
        ConnectorSettingsMenu.class,
        ConnectorSettingsMenu::accept);
  }
}
