package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import dev.izzy.factorycore.platform.session.WireEnums;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The Crafter screen's view (ergonomics Q2). Names travel as registry IDs and the client resolves
 * them, so a dedicated server never translates text. Patterns themselves are ordinary menu slots.
 */
public final class CrafterPackets {
  /** Machine rows: one per side, so at most six. */
  public static final int MAX_MACHINES = Direction.values().length;

  /** What the Crafter is doing, shown as {@code gui.factorycore.crafter.activity.<key>}. */
  public enum Activity {
    LOADING,
    NO_PATTERNS,
    /** Patterns are installed but no job needs them now. */
    IDLE,
    WORKING,
    /** A job needs one of its patterns and waits; {@link View#reason} says why. */
    WAITING;

    public String key() {
      return EnumKeys.key(this);
    }
  }

  /** A served machine's state, shown as {@code gui.factorycore.crafter.machine.<key>}. */
  public enum MachineState {
    FREE,
    BUSY,
    /** A job holds the machine and waits; {@link Machine#reason} says why. */
    WAITING;

    public String key() {
      return EnumKeys.key(this);
    }
  }

  /**
   * One adjacent machine: its side, block ID, state, and while a job holds it, that job's current
   * output ({@code output}, a registry ID, {@code fluid} for a fluid) and wait reason (or null).
   */
  public record Machine(
      Direction side,
      String block,
      MachineState state,
      String output,
      boolean fluid,
      JobReason reason) {
    public Machine {
      Objects.requireNonNull(side);
      Objects.requireNonNull(state);
      if (block.length() > 256 || output.length() > 256)
        throw new IllegalArgumentException("Invalid crafter machine row");
    }

    static void write(RegistryFriendlyByteBuf b, Machine row) {
      b.writeEnum(row.side);
      b.writeUtf(row.block, 256);
      WireEnums.write(b, row.state);
      b.writeUtf(row.output, 256);
      b.writeBoolean(row.fluid);
      WireEnums.write(b, row.reason);
    }

    static Machine read(RegistryFriendlyByteBuf b) {
      return new Machine(
          b.readEnum(Direction.class),
          b.readUtf(256),
          WireEnums.read(b, MachineState.class),
          b.readUtf(256),
          b.readBoolean(),
          WireEnums.readNullable(b, JobReason.class));
    }
  }

  /**
   * The screen's state: the installed speed {@code tier}, whether the viewer may move patterns
   * ({@code editable}), what the Crafter is doing and why it waits ({@code reason}, or null), and
   * its machines.
   */
  public record View(
      int menu,
      UUID session,
      long revision,
      ThroughputTier tier,
      boolean editable,
      Activity activity,
      JobReason reason,
      List<Machine> machines)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "crafter_view"));

    public View {
      Objects.requireNonNull(session);
      Objects.requireNonNull(tier);
      Objects.requireNonNull(activity);
      machines = List.copyOf(machines);
      if (machines.size() > MAX_MACHINES)
        throw new IllegalArgumentException("Invalid crafter view");
    }

    /** Placeholder before the first server publication. */
    public static View loading(int menu, UUID session) {
      return new View(
          menu, session, 0, ThroughputTier.BASE, false, Activity.LOADING, null, List.of());
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
              WireEnums.write(b, v.tier);
              b.writeBoolean(v.editable);
              WireEnums.write(b, v.activity);
              WireEnums.write(b, v.reason);
              b.writeVarInt(v.machines.size());
              for (var row : v.machines) Machine.write(b, row);
            },
            b -> {
              int menu = b.readVarInt();
              UUID session = b.readUUID();
              long revision = b.readVarLong();
              var tier = WireEnums.read(b, ThroughputTier.class);
              boolean editable = b.readBoolean();
              var activity = WireEnums.read(b, Activity.class);
              var reason = WireEnums.readNullable(b, JobReason.class);
              int size = b.readVarInt();
              if (size < 0 || size > MAX_MACHINES)
                throw new IllegalArgumentException("Crafter machine count");
              var machines = new ArrayList<Machine>(size);
              for (int i = 0; i < size; i++) machines.add(Machine.read(b));
              return new View(menu, session, revision, tier, editable, activity, reason, machines);
            });
  }

  private CrafterPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    MenuPayloads.toClient(
        event.registrar("1"), View.TYPE, View.CODEC, CrafterMenu.class, CrafterMenu::accept);
  }
}
