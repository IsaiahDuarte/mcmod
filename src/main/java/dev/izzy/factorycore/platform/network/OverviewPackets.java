package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.power.EnergyUsers;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Q116: the Overview's largest energy users' anvil names ("" for none), in the order of the menu's
 * top-user data slots. Sent only when they change.
 */
public final class OverviewPackets {
  public static final int MAX_NAME = 64;

  private OverviewPackets() {}

  public record Names(int containerId, List<String> names) implements CustomPacketPayload {
    public static final Type<Names> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "overview_names"));

    public static final StreamCodec<RegistryFriendlyByteBuf, Names> CODEC =
        StreamCodec.of(
            (buffer, names) -> {
              buffer.writeVarInt(names.containerId);
              buffer.writeVarInt(names.names.size());
              for (var name : names.names) buffer.writeUtf(name, MAX_NAME);
            },
            buffer -> {
              int id = buffer.readVarInt();
              int size = buffer.readVarInt();
              if (size < 0 || size > EnergyUsers.TOP)
                throw new IllegalArgumentException("Overview name count");
              var names = new ArrayList<String>(size);
              for (int i = 0; i < size; i++) names.add(buffer.readUtf(MAX_NAME));
              return new Names(id, names);
            });

    public Names {
      names = List.copyOf(names);
      if (names.size() > EnergyUsers.TOP || names.stream().anyMatch(n -> n.length() > MAX_NAME))
        throw new IllegalArgumentException("Overview names");
    }

    @Override
    public Type<Names> type() {
      return TYPE;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    MenuPayloads.toClient(
        event.registrar("1"), Names.TYPE, Names.CODEC, OverviewMenu.class, OverviewMenu::accept);
  }
}
