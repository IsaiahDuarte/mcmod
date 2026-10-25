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

/** Bounded configuration values, never client-supplied actor/root/provider authority. */
public final class MachineSettingsPackets {
  public record Form(
      String label,
      ResourceKind kind,
      ConnectorSettings.Access access,
      int priority,
      int firstSlot,
      int slotCount,
      int rate) {
    public Form {
      if (label.length() > 64 || label.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Machine label bounds");
      new ConnectorSettings(kind, access, priority, firstSlot, slotCount, rate);
    }

    public ConnectorSettings settings() {
      return new ConnectorSettings(kind, access, priority, firstSlot, slotCount, rate);
    }
  }

  private static void write(RegistryFriendlyByteBuf b, Form f) {
    b.writeUtf(f.label(), 64);
    b.writeEnum(f.kind());
    b.writeEnum(f.access());
    b.writeInt(f.priority());
    b.writeVarInt(f.firstSlot());
    b.writeVarInt(f.slotCount());
    b.writeVarInt(f.rate());
  }

  private static Form read(RegistryFriendlyByteBuf b) {
    return new Form(
        b.readUtf(64),
        b.readEnum(ResourceKind.class),
        b.readEnum(ConnectorSettings.Access.class),
        b.readInt(),
        b.readVarInt(),
        b.readVarInt(),
        b.readVarInt());
  }

  public record Apply(int menu, UUID session, long generation, Form form)
      implements CustomPacketPayload {
    public static final Type<Apply> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "machine_settings_apply"));

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

  public record View(
      int menu,
      UUID session,
      long generation,
      Form form,
      String side,
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
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "machine_settings_view"));

    public View {
      if (side.length() > 16
          || status.length() > 32
          || tier.length() > 16
          || maxRate < 1
          || maxRate > ThroughputTier.maxQuantum(form.kind())
          || itemSlots < 0
          || itemSlots > ConnectorEntity.MAX_REPORTED_SLOTS
          || tanks < 0
          || tanks > ConnectorEntity.MAX_REPORTED_SLOTS)
        throw new IllegalArgumentException("Machine view bounds");
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
                    b.readBoolean(),
                    b.readUtf(16),
                    b.readVarInt(),
                    b.readBoolean(),
                    b.readVarInt(),
                    b.readVarInt()));
  }

  private MachineSettingsPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("3");
    MenuPayloads.toServer(
        registrar, Apply.TYPE, Apply.CODEC, MachineSettingsMenu.class, MachineSettingsMenu::apply);
    MenuPayloads.toClient(
        registrar, View.TYPE, View.CODEC, MachineSettingsMenu.class, MachineSettingsMenu::accept);
  }
}
