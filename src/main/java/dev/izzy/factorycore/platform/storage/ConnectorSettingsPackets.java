package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
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
      int tanks)
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
                    b.readVarInt()));
  }

  private ConnectorSettingsPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("2");
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
