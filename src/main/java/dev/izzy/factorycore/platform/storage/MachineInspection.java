package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Q12: what a program can do with one machine, for the Label Tool's network view (SFM's Network
 * Tool block inspection): what the machine offers on each side (items with their slot count, fluids
 * with their tank count, energy), its labels, and ready-to-paste {@code move} lines for each side.
 * Reading it needs VIEW where the machine's cable or port is.
 */
public final class MachineInspection {
  public static final int MAX_LINES = 48, MAX_LINE = 160;

  /** The inspection for one machine; {@code title} names it, {@code lines} hold the rest. */
  public record View(String title, List<String> lines) implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "machine_inspection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeUtf(value.title, MAX_LINE);
              buffer.writeVarInt(value.lines.size());
              for (var line : value.lines) buffer.writeUtf(line, MAX_LINE);
            },
            buffer -> {
              var title = buffer.readUtf(MAX_LINE);
              int size = buffer.readVarInt();
              if (size < 0 || size > MAX_LINES)
                throw new IllegalArgumentException("Inspection line count");
              var lines = new ArrayList<String>(size);
              for (int index = 0; index < size; index++) lines.add(buffer.readUtf(MAX_LINE));
              return new View(title, lines);
            });

    public View {
      lines = List.copyOf(lines);
      if (title.length() > MAX_LINE
          || lines.size() > MAX_LINES
          || lines.stream().anyMatch(line -> line.length() > MAX_LINE))
        throw new IllegalArgumentException("Inspection bounds");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }
  }

  /** Set by the client at setup; mandatory code never loads client classes itself. */
  private static volatile Consumer<View> sink = view -> {};

  private MachineInspection() {}

  public static void sink(Consumer<View> client) {
    sink = client;
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event
        .registrar("1")
        .optional()
        .playToClient(View.TYPE, View.CODEC, (payload, context) -> sink.accept(payload));
  }

  /** The machine's labels: those of the cable ports aimed at it, in face order. */
  static List<String> labels(net.minecraft.server.level.ServerLevel level, BlockPos machine) {
    var labels = new ArrayList<String>();
    for (var side : Direction.values()) {
      var at = machine.relative(side);
      var state = level.getBlockState(at);
      if (state.getBlock() instanceof CablePortBlock
          && state.getValue(BlockStateProperties.FACING) == side.getOpposite()
          && level.getBlockEntity(at) instanceof MachineInterfaceEntity port
          && !port.label().isEmpty()
          && !labels.contains(port.label())) labels.add(port.label());
    }
    return labels;
  }

  /**
   * The inspection of the machine at {@code machine}, or null when it is not a machine on a cable
   * the player may VIEW.
   */
  public static View inspect(ServerPlayer player, BlockPos machine) {
    var level = player.serverLevel();
    if (!LabelToolItem.machine(level, machine)) return null;
    var networks = WorldNetworks.get(player.server);
    var actor = Actors.of(player);
    boolean visible = false;
    for (var side : Direction.values()) {
      var at = machine.relative(side);
      if (LabelToolItem.run(level.getBlockState(at))
          && networks.allows(actor, Positions.of(level, at), NetworkPermissions.Permission.VIEW)) {
        visible = true;
        break;
      }
    }
    if (!visible) return null;
    var labels = labels(level, machine);
    var name = level.getBlockState(machine).getBlock().getName().getString();
    var title = name + " at " + machine.getX() + " " + machine.getY() + " " + machine.getZ();
    var lines = new ArrayList<String>();
    String label;
    if (labels.isEmpty()) {
      lines.add(Component.translatable("gui.factorycore.inspect.unlabelled").getString());
      label = "label";
    } else {
      lines.add(
          Component.translatable("gui.factorycore.inspect.labels", String.join(", ", labels))
              .getString());
      label = labels.getFirst();
    }
    for (var side : Direction.values()) {
      var items = level.getCapability(Capabilities.ItemHandler.BLOCK, machine, side);
      var fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, machine, side);
      var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, machine, side);
      if (items == null && fluids == null && energy == null) continue;
      var offers = new ArrayList<String>();
      if (items != null) offers.add(items.getSlots() + " item slots");
      if (fluids != null) offers.add(fluids.getTanks() + " tanks");
      if (energy != null) offers.add("energy");
      lines.add("side " + side.getSerializedName() + ": " + String.join(", ", offers));
      var target = "machines(\"" + label + "\") side " + side.getSerializedName();
      if (items != null) {
        lines.add("move everything from storage to " + target);
        lines.add("move everything from " + target + " to storage");
      }
      if (fluids != null) {
        lines.add("move fluids from storage to " + target);
        lines.add("move fluids from " + target + " to storage");
      }
      if (energy != null) {
        if (energy.canReceive()) lines.add("move energy from storage to " + target);
        if (energy.canExtract()) lines.add("move energy from " + target + " to storage");
      }
    }
    if (lines.size() > MAX_LINES) lines.subList(MAX_LINES, lines.size()).clear();
    return new View(title.length() > MAX_LINE ? title.substring(0, MAX_LINE) : title, lines);
  }
}
