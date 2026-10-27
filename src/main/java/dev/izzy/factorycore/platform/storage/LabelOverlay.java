package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkNodeBlock;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * What the Label Tool shows in the world while held, in the player's chosen {@link Mode}.
 *
 * <p>Q11, {@link Mode#LABELS}: the labels of machines and Signal Interfaces within {@link #RANGE}
 * blocks, and a marker on each unlabelled machine that touches a cable, built from the block
 * entities of loaded chunks in range; the nearest {@link #MAX_ENTRIES} are kept.
 *
 * <p>Q12, {@link Mode#NETWORK}: the network of the block the player looks at, walked from there
 * along wired faces through loaded chunks (at most {@link #MAX_NETWORK} blocks): its cables, ports,
 * devices and gateways, coloured by branch, and the machines its cable ports reach.
 *
 * <p>Every {@link #INTERVAL} ticks the server rebuilds the snapshot and sends it when it changed;
 * putting the tool away, or {@link Mode#OFF}, sends an empty one. Entries need VIEW where they are.
 * Server thread only.
 */
public final class LabelOverlay {
  public static final int RANGE = 32, MAX_ENTRIES = 256, MAX_NETWORK = 1_024, INTERVAL = 10;

  /** What the overlay shows; the overlay key cycles through them in order. */
  public enum Mode {
    LABELS,
    NETWORK,
    OFF;

    public Mode next() {
      return values()[(ordinal() + 1) % values().length];
    }
  }

  /** What an entry outlines or names. */
  public enum Kind {
    LABEL,
    MARKER,
    CABLE,
    PORT,
    DEVICE,
    GATEWAY,
    MACHINE
  }

  /**
   * One shown block. {@code label} is a label's text (empty otherwise); {@code branch} numbers the
   * network's branches in the order the walk met them, for colours (0 in label mode).
   */
  public record Entry(long pos, String label, Kind kind, int branch) {
    public Entry {
      Objects.requireNonNull(kind);
      if (label.length() > LabelToolItem.MAX_LABEL || branch < 0 || branch > 255)
        throw new IllegalArgumentException("Overlay entry bounds");
    }

    /** A label mode entry: the label, or a marker when it is empty. */
    public Entry(long pos, String label) {
      this(pos, label, label.isEmpty() ? Kind.MARKER : Kind.LABEL, 0);
    }
  }

  /** The current overlay for one player; empty when the tool is put away. */
  public record Snapshot(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<Snapshot> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "label_overlay"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.entries.size());
              for (var entry : value.entries) {
                buffer.writeLong(entry.pos());
                buffer.writeUtf(entry.label(), LabelToolItem.MAX_LABEL);
                buffer.writeEnum(entry.kind());
                buffer.writeByte(entry.branch());
              }
            },
            buffer -> {
              int size = buffer.readVarInt();
              if (size < 0 || size > MAX_NETWORK)
                throw new IllegalArgumentException("Overlay entry count");
              var entries = new ArrayList<Entry>(size);
              for (int index = 0; index < size; index++)
                entries.add(
                    new Entry(
                        buffer.readLong(),
                        buffer.readUtf(LabelToolItem.MAX_LABEL),
                        buffer.readEnum(Kind.class),
                        buffer.readUnsignedByte()));
              return new Snapshot(entries);
            });

    public Snapshot {
      entries = List.copyOf(entries);
      if (entries.size() > MAX_NETWORK) throw new IllegalArgumentException("Overlay entry count");
    }

    @Override
    public Type<Snapshot> type() {
      return TYPE;
    }
  }

  /** Set by the client at setup; mandatory code never loads client classes itself. */
  private static volatile Consumer<Snapshot> sink = snapshot -> {};

  /** Q12: the overlay key's choice, from the client. */
  public record SetMode(Mode mode) implements CustomPacketPayload {
    public static final Type<SetMode> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "label_overlay_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetMode> CODEC =
        StreamCodec.of(
            (buffer, value) -> buffer.writeEnum(value.mode),
            buffer -> new SetMode(buffer.readEnum(Mode.class)));

    public SetMode {
      Objects.requireNonNull(mode);
    }

    @Override
    public Type<SetMode> type() {
      return TYPE;
    }
  }

  /** Each player's overlay mode (runtime only; labels until chosen). */
  private static final ServerScoped<Map<UUID, Mode>> MODES =
      ServerScoped.of((MinecraftServer server) -> new HashMap<>());

  public static Mode mode(ServerPlayer player) {
    return MODES.get(player.server).getOrDefault(player.getUUID(), Mode.LABELS);
  }

  public static void setMode(ServerPlayer player, Mode mode) {
    MODES.get(player.server).put(player.getUUID(), mode);
  }

  /** The last snapshot sent to each player (runtime only). */
  private static final ServerScoped<Map<UUID, List<Entry>>> SENT =
      ServerScoped.of((MinecraftServer server) -> new HashMap<>());

  private LabelOverlay() {}

  public static void sink(Consumer<Snapshot> client) {
    sink = client;
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event
        .registrar("1")
        .optional()
        .playToClient(Snapshot.TYPE, Snapshot.CODEC, (payload, context) -> sink.accept(payload))
        .playToServer(
            SetMode.TYPE,
            SetMode.CODEC,
            (payload, context) -> {
              if (context.player() instanceof ServerPlayer player) setMode(player, payload.mode());
            });
  }

  private static boolean holding(ServerPlayer player) {
    return player.getMainHandItem().is(LabelToolContent.ITEM.get())
        || player.getOffhandItem().is(LabelToolContent.ITEM.get());
  }

  public static void tick(ServerTickEvent.Post event) {
    var server = event.getServer();
    if (server.getTickCount() % INTERVAL != 0) return;
    var sent = SENT.get(server);
    sent.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    MODES.get(server).keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    STARTS.get(server).keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    for (var player : server.getPlayerList().getPlayers()) {
      if (!player.connection.hasChannel(Snapshot.TYPE)) continue;
      var entries =
          !holding(player)
              ? List.<Entry>of()
              : switch (mode(player)) {
                case LABELS -> snapshot(player);
                case NETWORK -> network(player, looked(player));
                case OFF -> List.<Entry>of();
              };
      var before = sent.getOrDefault(player.getUUID(), List.of());
      if (entries.equals(before)) continue;
      sent.put(player.getUUID(), entries);
      PacketDistributor.sendToPlayer(player, new Snapshot(entries));
    }
  }

  /** The overlay {@code player} sees now: labels and markers in range, nearest first. */
  public static List<Entry> snapshot(ServerPlayer player) {
    var level = player.serverLevel();
    var center = player.blockPosition();
    var networks = WorldNetworks.get(player.server);
    var actor = Actors.of(player);
    var labels = new LinkedHashMap<BlockPos, String>();
    var markers = new ArrayList<BlockPos>();
    int reach = (RANGE >> 4) + 1;
    for (int cx = (center.getX() >> 4) - reach; cx <= (center.getX() >> 4) + reach; cx++)
      for (int cz = (center.getZ() >> 4) - reach; cz <= (center.getZ() >> 4) + reach; cz++) {
        var chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) continue;
        for (var entity : chunk.getBlockEntities().values()) {
          var at = entity.getBlockPos();
          if (at.distSqr(center) > (double) RANGE * RANGE) continue;
          if (entity instanceof MachineInterfaceEntity port && port.cablePort()) {
            if (!port.label().isEmpty()
                && networks.allows(actor, port.position(), NetworkPermissions.Permission.VIEW))
              labels.putIfAbsent(
                  at.relative(port.getBlockState().getValue(BlockStateProperties.FACING)),
                  port.label());
          } else if (entity instanceof SignalInterfaceEntity signal) {
            if (!signal.label().isEmpty()
                && networks.allows(actor, signal.origin(), NetworkPermissions.Permission.VIEW))
              labels.putIfAbsent(at.immutable(), signal.label());
          } else if (LabelToolItem.machine(level, at))
            for (var side : Direction.values()) {
              var next = at.relative(side);
              if (LabelToolItem.run(level.getBlockState(next))
                  && networks.allows(
                      actor, Positions.of(level, next), NetworkPermissions.Permission.VIEW)) {
                markers.add(at.immutable());
                break;
              }
            }
        }
      }
    var entries = new ArrayList<Entry>(labels.size() + markers.size());
    for (var label : labels.entrySet())
      entries.add(new Entry(label.getKey().asLong(), label.getValue()));
    for (var marker : markers)
      if (!labels.containsKey(marker)) entries.add(new Entry(marker.asLong(), ""));
    return nearest(entries, center, MAX_ENTRIES);
  }

  /** How far the network view looks for a block to start from. */
  public static final double LOOK = 16;

  /** Each player's last network start, kept while they look at open air (runtime only). */
  private static final ServerScoped<Map<UUID, BlockPos>> STARTS =
      ServerScoped.of((MinecraftServer server) -> new HashMap<>());

  /**
   * The block the player looks at within {@link #LOOK} blocks, or, when that is air, the one they
   * looked at last, so the view stays while they look around.
   */
  private static BlockPos looked(ServerPlayer player) {
    var starts = STARTS.get(player.server);
    var hit = player.pick(LOOK, 1.0f, false);
    if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
      starts.put(player.getUUID(), block.getBlockPos());
      return block.getBlockPos();
    }
    return starts.get(player.getUUID());
  }

  /**
   * Q12: the network around {@code start} (a networked block, or a machine beside one): every block
   * reached along wired faces through loaded chunks, at most {@link #MAX_NETWORK} entries, each
   * with its branch, and the machines its cable ports are aimed at. Blocks whose network is
   * rebuilding, or that the player may not VIEW, are left out. Empty when {@code start} is neither.
   */
  public static List<Entry> network(ServerPlayer player, BlockPos start) {
    if (start == null) return List.of();
    var level = player.serverLevel();
    var networks = WorldNetworks.get(player.server);
    var actor = Actors.of(player);
    var queue = new ArrayDeque<BlockPos>();
    var visited = new HashSet<BlockPos>();
    if (level.getBlockState(start).getBlock() instanceof NetworkedBlock) queue.add(start);
    else
      for (var side : Direction.values()) {
        var next = start.relative(side);
        if (level.isLoaded(next) && level.getBlockState(next).getBlock() instanceof NetworkedBlock)
          queue.add(next);
      }
    visited.addAll(queue);
    var branches = new HashMap<UUID, Integer>();
    UUID network = null;
    var entries = new ArrayList<Entry>();
    while (!queue.isEmpty() && entries.size() < MAX_NETWORK) {
      var at = queue.poll();
      var state = level.getBlockState(at);
      var position = Positions.of(level, at);
      var member = networks.membership(position);
      if (member.status() != NetworkTopology.Status.VALID
          || !networks.allows(actor, position, NetworkPermissions.Permission.VIEW)) continue;
      var scope = member.scope();
      if (network == null) network = scope.network();
      if (!network.equals(scope.network())) continue;
      int branch = branches.computeIfAbsent(scope.segment(), segment -> branches.size() % 256);
      var block = state.getBlock();
      var kind =
          block instanceof NetworkCableBlock
              ? Kind.CABLE
              : block instanceof CablePortBlock || block instanceof PortBlock
                  ? Kind.PORT
                  : block instanceof NetworkNodeBlock node
                          && node.kind() == NodeRegistry.Kind.GATEWAY
                      ? Kind.GATEWAY
                      : Kind.DEVICE;
      entries.add(new Entry(at.asLong(), "", kind, branch));
      if (block instanceof CablePortBlock && entries.size() < MAX_NETWORK) {
        var machine = at.relative(state.getValue(BlockStateProperties.FACING));
        if (level.isLoaded(machine) && LabelToolItem.machine(level, machine))
          entries.add(new Entry(machine.asLong(), "", Kind.MACHINE, branch));
      }
      for (var side : Direction.values()) {
        var next = at.relative(side);
        if (!level.isLoaded(next) || visited.contains(next)) continue;
        var nextState = level.getBlockState(next);
        if (NetworkedBlock.wired(state, side)
            && NetworkedBlock.wired(nextState, side.getOpposite())) {
          visited.add(next);
          queue.add(next);
        }
      }
    }
    return List.copyOf(entries);
  }

  /** The {@code max} entries nearest {@code center}, nearest first (ties by position). */
  static List<Entry> nearest(List<Entry> entries, BlockPos center, int max) {
    return entries.stream()
        .sorted(
            Comparator.comparingDouble((Entry entry) -> BlockPos.of(entry.pos()).distSqr(center))
                .thenComparingLong(Entry::pos))
        .limit(max)
        .toList();
  }
}
