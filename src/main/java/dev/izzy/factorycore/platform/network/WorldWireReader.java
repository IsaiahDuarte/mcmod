package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.network.WiredDiscovery;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveBlock;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Read-only, nonloading owner-thread adapter. Region coordination supplies immutable cached
 * geometry.
 */
public final class WorldWireReader implements Function<NodeRegistry.Position, WiredDiscovery.Wire> {
  private final ServerLevel level;
  private final DeviceSavedData saved;
  private final Function<NodeRegistry.Position, WiredDiscovery.Wire> cached;

  public WorldWireReader(
      ServerLevel level, Function<NodeRegistry.Position, WiredDiscovery.Wire> cached) {
    this.level = Objects.requireNonNull(level);
    this.cached = Objects.requireNonNull(cached);
    saved = DeviceSavedData.get(level.getServer());
  }

  @Override
  public WiredDiscovery.Wire apply(NodeRegistry.Position position) {
    if (!level.getServer().isSameThread())
      throw new IllegalStateException("World probe requires server thread");
    if (!position.dimension().equals(level.dimension().location().toString()))
      throw new IllegalArgumentException("Foreign discovery dimension");
    var block = BlockPos.of(position.block());
    var chunk = level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
    if (chunk == null) {
      var previous = cached.apply(position);
      return previous == null ? null : previous.unloaded();
    }
    var state = chunk.getBlockState(block);
    if (state.getBlock() instanceof NetworkNodeBlock nodeBlock) {
      var entity = chunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK);
      var node = entity instanceof NetworkNodeEntity structural ? structural : null;
      boolean valid = false;
      if (node != null && !node.isRemoved()) {
        try {
          node.validatePlaced();
          valid = true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
          valid = false;
        }
      }
      UUID canonical =
          node != null && node.handle() != null ? node.handle().id() : id(position, "unowned");
      if (nodeBlock.kind() == NodeRegistry.Kind.GATEWAY) {
        var upstream = id(position, "upstream");
        var downstream = id(position, "downstream");
        Direction facing = state.getValue(BlockStateProperties.FACING);
        var policy =
            valid
                ? saved.nodes().definition(node.handle()).policy()
                : new GatewayPolicy(Set.of(), Set.of(), Set.of());
        return new WiredDiscovery.Wire(
            List.of(
                new NetworkTopology.Node(upstream, NetworkTopology.Role.CABLE, "", true),
                new NetworkTopology.Node(downstream, NetworkTopology.Role.CABLE, "", true)),
            List.of(port(block, facing.getOpposite(), upstream), port(block, facing, downstream)),
            new NetworkTopology.Gateway(canonical, upstream, downstream, policy, valid));
      }
      return ordinary(block, canonical, NetworkTopology.Role.ROOT, valid);
    }
    if (state.is(NetworkContent.CABLE.get()))
      return ordinary(block, id(position, "wire"), NetworkTopology.Role.CABLE, true);
    if (!(state.getBlock() instanceof StorageDriveBlock)) return null;
    var entity = chunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK);
    boolean loaded =
        entity instanceof StorageDriveEntity drive
            && !drive.isRemoved()
            && drive.diagnostic().isEmpty();
    return ordinary(block, id(position, "wire"), NetworkTopology.Role.STORAGE, loaded);
  }

  private WiredDiscovery.Wire ordinary(
      BlockPos block, UUID id, NetworkTopology.Role role, boolean loaded) {
    var ports = new ArrayList<WiredDiscovery.Port>(6);
    for (var face : Direction.values()) ports.add(port(block, face, id));
    return new WiredDiscovery.Wire(
        List.of(new NetworkTopology.Node(id, role, "", loaded)), ports, null);
  }

  private WiredDiscovery.Port port(BlockPos block, Direction face, UUID vertex) {
    return new WiredDiscovery.Port(
        new NodeRegistry.Position(
            level.dimension().location().toString(), block.relative(face).asLong()),
        vertex);
  }

  private UUID id(NodeRegistry.Position position, String role) {
    return UUID.nameUUIDFromBytes(
        ("factorycore:wire:"
                + saved.registry().world()
                + ":"
                + position.dimension()
                + ":"
                + position.block()
                + ":"
                + role)
            .getBytes(StandardCharsets.UTF_8));
  }
}
