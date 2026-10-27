package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Q11: while a player holds the Label Tool, the labels of machines and Signal Interfaces within
 * {@link #RANGE} blocks, and a marker on each unlabelled machine that touches a cable, so the
 * player sees what is labelled. Every {@link #INTERVAL} ticks the server rebuilds the snapshot from
 * the block entities of loaded chunks in range and sends it when it changed; putting the tool away
 * sends an empty one. Entries need VIEW where the label or cable is, and the nearest {@link
 * #MAX_ENTRIES} are kept. Server thread only.
 */
public final class LabelOverlay {
  public static final int RANGE = 32, MAX_ENTRIES = 256, INTERVAL = 10;

  /** A labelled block (the label) or an unlabelled machine on a cable (an empty label). */
  public record Entry(long pos, String label) {
    public Entry {
      if (label.length() > LabelToolItem.MAX_LABEL)
        throw new IllegalArgumentException("Overlay label bounds");
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
              }
            },
            buffer -> {
              int size = buffer.readVarInt();
              if (size < 0 || size > MAX_ENTRIES)
                throw new IllegalArgumentException("Overlay entry count");
              var entries = new ArrayList<Entry>(size);
              for (int index = 0; index < size; index++)
                entries.add(new Entry(buffer.readLong(), buffer.readUtf(LabelToolItem.MAX_LABEL)));
              return new Snapshot(entries);
            });

    public Snapshot {
      entries = List.copyOf(entries);
      if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Overlay entry count");
    }

    @Override
    public Type<Snapshot> type() {
      return TYPE;
    }
  }

  /** Set by the client at setup; mandatory code never loads client classes itself. */
  private static volatile Consumer<Snapshot> sink = snapshot -> {};

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
        .playToClient(Snapshot.TYPE, Snapshot.CODEC, (payload, context) -> sink.accept(payload));
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
    for (var player : server.getPlayerList().getPlayers()) {
      if (!player.connection.hasChannel(Snapshot.TYPE)) continue;
      var entries = holding(player) ? snapshot(player) : List.<Entry>of();
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
