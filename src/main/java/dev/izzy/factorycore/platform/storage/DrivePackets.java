package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Drive screen view. Bay actions use vanilla menu-button clicks (see {@link DriveMenu}); this view
 * only carries what the four bays hold, since cell totals do not fit vanilla data slots.
 */
public final class DrivePackets {
  public static final int BAYS = 4;

  /**
   * One bay: {@code item} is the cell's registry id, or empty for an empty bay. {@code fluid}
   * selects mB instead of items. {@code total} and {@code capacity} are exact units; {@code types}
   * counts exact identities with positive stored quantities, including reserved stock.
   */
  public record Bay(
      String item, boolean fluid, long total, long capacity, boolean infinite, int types) {
    public static final Bay EMPTY = new Bay("", false, 0, 0, false, 0);

    public Bay {
      if (item.length() > 96 || total < 0 || capacity < 0 || types < 0)
        throw new IllegalArgumentException("Invalid drive bay");
    }
  }

  /**
   * {@code status} names a {@code gui.factorycore.drive.status.*} key; {@code detail} is a refusal.
   */
  public record View(
      int menu, UUID session, long revision, List<Bay> bays, String status, String detail)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "drive_view"));

    public View {
      Objects.requireNonNull(session);
      bays = List.copyOf(bays);
      if (bays.size() != BAYS || status.length() > 16 || detail.length() > 128)
        throw new IllegalArgumentException("Invalid drive view");
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
              b.writeVarLong(v.revision);
              for (var bay : v.bays) {
                b.writeUtf(bay.item, 96);
                b.writeBoolean(bay.fluid);
                b.writeVarLong(bay.total);
                b.writeVarLong(bay.capacity);
                b.writeBoolean(bay.infinite);
                b.writeVarInt(bay.types);
              }
              b.writeUtf(v.status, 16);
              b.writeUtf(v.detail, 128);
            },
            b -> {
              int menu = b.readVarInt();
              UUID session = b.readUUID();
              long revision = b.readVarLong();
              var bays = new ArrayList<Bay>(BAYS);
              for (int i = 0; i < BAYS; i++)
                bays.add(
                    new Bay(
                        b.readUtf(96),
                        b.readBoolean(),
                        b.readVarLong(),
                        b.readVarLong(),
                        b.readBoolean(),
                        b.readVarInt()));
              return new View(menu, session, revision, bays, b.readUtf(16), b.readUtf(128));
            });
  }

  private DrivePackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    MenuPayloads.toClient(
        event.registrar("1"), View.TYPE, View.CODEC, DriveMenu.class, DriveMenu::accept);
  }
}
