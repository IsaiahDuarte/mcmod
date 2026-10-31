package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import dev.izzy.factorycore.platform.session.WireEnums;
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
  /** The last bay action's outcome, shown as {@code gui.factorycore.drive.status.<key>}. */
  public enum Status {
    LOADING,
    READY,
    INSTALLED,
    REMOVED,
    UPGRADED,
    WRONG_ITEM,
    REFUSED,
    PARTITIONED,
    PRIORITY,
    DENIED,
    STALE;

    public String key() {
      return EnumKeys.key(this);
    }
  }

  public static final int BAYS = 4;

  /**
   * One bay: {@code item} is the cell's registry id, or empty for an empty bay. {@code fluid}
   * selects mB instead of items. {@code total} and {@code capacity} are exact units; {@code types}
   * counts exact identities with positive stored quantities, including reserved stock.
   */
  public record Bay(
      String item,
      boolean fluid,
      long total,
      long capacity,
      boolean infinite,
      int types,
      String partitionMode,
      List<FilterEdits.Rule> partition) {
    public static final Bay EMPTY = new Bay("", false, 0, 0, false, 0, "ALL", List.of());

    /** B3: {@code partitionMode} is ALL, INCLUDE or EXCLUDE; {@code partition} its slots. */
    public Bay {
      partition = List.copyOf(partition);
      if (item.length() > 96
          || total < 0
          || capacity < 0
          || types < 0
          || partitionMode.length() > 16
          || partition.size() > dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS)
        throw new IllegalArgumentException("Invalid drive bay");
    }
  }

  /**
   * B3 partition edits for {@code bay}, applied to the cell at once (the Drive has no Apply step)
   * under EDIT. CONTENTS replaces the slots with the cell's first stored types (Q23). MODE cycles
   * all → only listed → all but listed; the others mirror the ghost slots.
   */
  public record FilterCommand(
      int menu, UUID session, int bay, int action, int selection, String text)
      implements CustomPacketPayload {
    public static final int CARRIED = 0,
        CYCLE = 1,
        REMOVE = 2,
        CLEAR = 3,
        REGISTRY = 4,
        TAG = 5,
        MODE = 6,
        // B4: sets the whole drive's priority to the integer in {@code text}; {@code bay} is
        // unused.
        PRIORITY = 7,
        CONTENTS = 8;

    public static final Type<FilterCommand> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "drive_filter"));

    public FilterCommand {
      if (bay < 0
          || bay >= BAYS
          || action < CARRIED
          || action > CONTENTS
          || selection < 0
          || selection >= dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS
          || text.length() > 256)
        throw new IllegalArgumentException("Invalid drive filter command");
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
              b.writeVarInt(v.bay);
              b.writeVarInt(v.action);
              b.writeVarInt(v.selection);
              b.writeUtf(v.text, 256);
            },
            b ->
                new FilterCommand(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readUtf(256)));
  }

  /** {@code status} is the last bay action's outcome; {@code detail} is a refusal. */
  public record View(
      int menu,
      UUID session,
      long revision,
      List<Bay> bays,
      Status status,
      String detail,
      int priority)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "drive_view"));

    public View {
      Objects.requireNonNull(session);
      Objects.requireNonNull(status);
      bays = List.copyOf(bays);
      if (bays.size() != BAYS || detail.length() > 128)
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
                b.writeUtf(bay.partitionMode, 16);
                b.writeVarInt(bay.partition.size());
                for (var rule : bay.partition) FilterEdits.Rule.write(b, rule);
              }
              WireEnums.write(b, v.status);
              b.writeUtf(v.detail, 128);
              b.writeVarInt(v.priority);
            },
            b -> {
              int menu = b.readVarInt();
              UUID session = b.readUUID();
              long revision = b.readVarLong();
              var bays = new ArrayList<Bay>(BAYS);
              for (int i = 0; i < BAYS; i++) {
                String item = b.readUtf(96);
                boolean fluid = b.readBoolean();
                long total = b.readVarLong(), capacity = b.readVarLong();
                boolean infinite = b.readBoolean();
                int types = b.readVarInt();
                String mode = b.readUtf(16);
                int size = b.readVarInt();
                if (size < 0 || size > dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS)
                  throw new IllegalArgumentException("Partition rule count");
                var rules = new ArrayList<FilterEdits.Rule>(size);
                for (int r = 0; r < size; r++) rules.add(FilterEdits.Rule.read(b));
                bays.add(new Bay(item, fluid, total, capacity, infinite, types, mode, rules));
              }
              return new View(
                  menu,
                  session,
                  revision,
                  bays,
                  WireEnums.read(b, Status.class),
                  b.readUtf(128),
                  b.readVarInt());
            });
  }

  private DrivePackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("5");
    MenuPayloads.toClient(registrar, View.TYPE, View.CODEC, DriveMenu.class, DriveMenu::accept);
    MenuPayloads.toServer(
        registrar, FilterCommand.TYPE, FilterCommand.CODEC, DriveMenu.class, DriveMenu::filter);
  }
}
