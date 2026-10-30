package dev.izzy.factorycore.platform.session;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The one-line status strip shared by every device screen (U7): network state, the device's current
 * reason and, for powered devices, stored energy. Menus publish it from their own broadcast step;
 * it carries display text keys only, never authority.
 */
public final class DeviceStatus {
  /** A menu whose screen shows the status strip. */
  public interface Holder {
    View deviceStatus();

    void acceptDeviceStatus(View view);
  }

  /**
   * {@code network} is the device's topology status, null before the first publication; {@code
   * reason} is a full translation key or empty; {@code power} is preformatted numbers or empty.
   */
  public record View(
      int menu, UUID session, NetworkTopology.Status network, String reason, String power)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "device_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              WireEnums.write(b, v.network);
              b.writeUtf(v.reason, 96);
              b.writeUtf(v.power, 48);
            },
            b ->
                new View(
                    b.readVarInt(),
                    b.readUUID(),
                    WireEnums.readNullable(b, NetworkTopology.Status.class),
                    b.readUtf(96),
                    b.readUtf(48)));

    public View {
      Objects.requireNonNull(session);
      if (reason.length() > 96 || power.length() > 48)
        throw new IllegalArgumentException("Device status bounds");
    }

    /** Whether the device's network is online. */
    public boolean online() {
      return network == NetworkTopology.Status.VALID;
    }

    /** Placeholder before the first server publication. */
    public static View pending(int menu, UUID session) {
      return new View(menu, session, null, "", "");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }
  }

  private DeviceStatus() {}

  /** The current status of the device at {@code position}. */
  public static View of(
      MinecraftServer server,
      int menu,
      UUID session,
      NodeRegistry.Position position,
      String reason,
      String power) {
    return new View(
        menu, session, WorldNetworks.get(server).membership(position).status(), reason, power);
  }

  /** "stored / capacity FE" with grouping, for powered devices. */
  public static String energy(long stored, long capacity) {
    return String.format(Locale.ROOT, "%,d / %,d FE", stored, capacity);
  }

  /** Sends {@code next} when it differs from {@code previous}; returns the status now shown. */
  public static View publish(ServerPlayer player, View previous, View next) {
    if (next.equals(previous)) return previous;
    PacketDistributor.sendToPlayer(player, next);
    return next;
  }

  /** Client acceptance: only the addressed open menu and session. */
  public static View accept(int menu, UUID session, View current, View packet) {
    return packet.menu() == menu && packet.session().equals(session) ? packet : current;
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    MenuPayloads.toClient(
        event.registrar("1"), View.TYPE, View.CODEC, Holder.class, Holder::acceptDeviceStatus);
  }
}
