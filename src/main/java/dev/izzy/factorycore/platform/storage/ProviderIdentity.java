package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.resource.ExternalBackingProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

/**
 * Physical provider identity for {@link WorldConnectors}: which loaded blocks back a connector or
 * leased machine (double chests included) and the world-scoped persistent identity of those blocks.
 * Reads loaded metadata only; never calls a capability, moves resources or loads chunks.
 */
final class ProviderIdentity {
  /** One physical backing block for one resource kind. */
  record Provider(NodeRegistry.Position position, ResourceKind kind) {}

  private final MinecraftServer server;
  private final DeviceSavedData saved;

  ProviderIdentity(MinecraftServer server, DeviceSavedData saved) {
    this.server = server;
    this.saved = saved;
  }

  /**
   * Capture/read processing provider fingerprints using only loaded persistent component metadata.
   * Initialization is allowed only before new dispatch; resume/cleanup must pass false. Empty means
   * unavailable/unprovable identity. No capability getter or resource method is invoked.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  Map<ResourceKind, UUID> processingIdentity(NodeRegistry.Position target, boolean initialize) {
    var dimension = Loaded.dimension(target.dimension());
    var level = server.getLevel(dimension);
    if (level == null) return Map.of();
    var position = BlockPos.of(target.block());
    var targetEntity = Loaded.blockEntity(level, position);
    if (!(targetEntity instanceof ExternalBackingProvider)
        && !(targetEntity instanceof Container
            && targetEntity
                .getClass()
                .getPackageName()
                .equals("net.minecraft.world.level.block.entity"))) return Map.of();
    var identities = new IdentityHashMap<BlockEntity, UUID>();
    var missing = new ArrayList<BlockEntity>();
    var result = new EnumMap<ResourceKind, UUID>(ResourceKind.class);
    try {
      for (var kind : ResourceKind.values()) {
        var members = providerPositions(level, position, kind);
        if (members == null) return Map.of();
        var components = new ArrayList<UUID>();
        var types = new StringBuilder();
        for (var member : members) {
          var block = BlockPos.of(member.position().block());
          var entity = Loaded.blockEntity(level, block);
          if (entity == null || entity.isRemoved()) return Map.of();
          var id = identities.get(entity);
          if (id == null) {
            id =
                ExternalProviderIdentity.read(
                    entity.getPersistentData().get(ExternalProviderIdentity.FIELD),
                    saved.registry().world());
            if (id == null) {
              if (!initialize) return Map.of();
              id = UUID.randomUUID();
              missing.add(entity);
            }
            identities.put(entity, id);
          }
          components.add(id);
          types
              .append(':')
              .append(member.position().block())
              .append(':')
              .append(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
        }
        var group = ExternalProviderIdentity.group(saved.registry().world(), kind, components);
        result.put(
            kind,
            UUID.nameUUIDFromBytes(
                ("factorycore:processing-provider:v1:" + group + types)
                    .getBytes(StandardCharsets.UTF_8)));
      }
      // Validate every group before initializing any persistent identity marker.
      for (var entity : missing) {
        entity
            .getPersistentData()
            .put(
                ExternalProviderIdentity.FIELD,
                ExternalProviderIdentity.write(saved.registry().world(), identities.get(entity)));
        entity.setChanged();
      }
      return Map.copyOf(result);
    } catch (RuntimeException invalidMetadata) {
      return Map.of();
    }
  }

  /** Validate all saved markers before initializing any missing component. */
  UUID persistentBacking(ConnectorEntity owner, List<Provider> selected) {
    var components = new ArrayList<BlockEntity>(selected.size());
    var ids = new ArrayList<UUID>(selected.size());
    var missing = new ArrayList<Boolean>(selected.size());
    UUID world = saved.registry().world();
    for (var provider : selected) {
      var position = BlockPos.of(provider.position().block());
      var chunk = Loaded.chunk(owner.serverLevel(), position);
      if (chunk == null) throw new IllegalStateException("Provider component unloaded");
      var entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
      if (entity == null || entity.isRemoved())
        throw new IllegalArgumentException(
            "Provider backing component needs persistent block-entity identity");
      var id =
          ExternalProviderIdentity.read(
              entity.getPersistentData().get(ExternalProviderIdentity.FIELD), world);
      components.add(entity);
      missing.add(id == null);
      ids.add(id == null ? UUID.randomUUID() : id);
    }
    UUID backing = ExternalProviderIdentity.group(world, owner.settings().kind(), ids);
    for (int index = 0; index < components.size(); index++)
      if (missing.get(index)) {
        var entity = components.get(index);
        entity
            .getPersistentData()
            .put(
                ExternalProviderIdentity.FIELD,
                ExternalProviderIdentity.write(world, ids.get(index)));
        entity.setChanged();
      }
    return backing;
  }

  boolean vanillaBacking(ConnectorEntity owner, Object handler, List<Provider> selected) {
    boolean sided = handler.getClass() == SidedInvWrapper.class;
    if (!sided && handler.getClass() != InvWrapper.class) return false;
    for (var provider : selected) {
      var position = BlockPos.of(provider.position().block());
      var chunk = Loaded.chunk(owner.serverLevel(), position);
      if (chunk == null) return false;
      var entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
      if (!(sided ? sidedView(handler, entity) : invView(handler, entity))) return false;
    }
    return true;
  }

  private static boolean invView(Object handler, Object entity) {
    var container = ((InvWrapper) handler).getInv();
    return container == entity
        || container instanceof CompoundContainer combined
            && entity instanceof Container part
            && combined.contains(part);
  }

  /** SidedInvWrapper exposes no container getter; its equality compares container and face. */
  private static boolean sidedView(Object handler, Object entity) {
    if (!(entity instanceof WorldlyContainer worldly)) return false;
    for (var side : Direction.values())
      if (handler.equals(new SidedInvWrapper(worldly, side))) return true;
    return false;
  }

  BlockEntity providerEntity(ConnectorEntity owner) {
    var position = owner.getBlockPos().relative(owner.targetDirection());
    var chunk = Loaded.chunk(owner.serverLevel(), position);
    return chunk == null
        ? null
        : chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
  }

  /** Normalize vanilla double chests and check both chunks before any capability lookup. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  List<Provider> providerPositions(ConnectorEntity owner) {
    try {
      return providerPositions(
          owner.serverLevel(),
          owner.getBlockPos().relative(owner.targetDirection()),
          owner.settings().kind());
    } catch (RuntimeException fault) {
      owner.quarantine(fault);
      throw fault;
    }
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  List<Provider> providerPositions(ServerLevel level, BlockPos position, ResourceKind kind) {
    var chunk = Loaded.chunk(level, position);
    if (chunk == null) return null;
    BlockEntity entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
    if (entity instanceof EnergyBankEntity
        || entity instanceof BufferedInterfaceEntity
        || entity instanceof StorageDriveEntity
        || entity instanceof ConnectorEntity)
      throw new IllegalArgumentException(
          "Digital storage uses canonical handles, not foreign registration");
    if (entity instanceof ExternalBackingProvider described) {
      List<BlockPos> blocks = described.factoryCoreBackingPositions(kind);
      if (blocks == null)
        throw new IllegalArgumentException("Invalid provider backing description");
      int count = blocks.size();
      if (count < 1 || count > 64)
        throw new IllegalArgumentException("Invalid provider backing description");
      var copied = new ArrayList<BlockPos>(count);
      for (int index = 0; index < count; index++) {
        var block = blocks.get(index);
        if (block == null || !BlockPos.of(block.asLong()).equals(block))
          throw new IllegalArgumentException("Invalid backing block position");
        copied.add(block.immutable());
      }
      if (!copied.contains(position) || new HashSet<>(copied).size() != copied.size())
        throw new IllegalArgumentException("Invalid provider backing description");
      for (var block : copied) {
        var backingChunk = Loaded.chunk(level, block);
        if (backingChunk == null) return null;
        var part = backingChunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK);
        if (part instanceof EnergyBankEntity
            || part instanceof BufferedInterfaceEntity
            || part instanceof StorageDriveEntity
            || part instanceof ConnectorEntity)
          throw new IllegalArgumentException("Declared backing overlaps canonical digital storage");
      }
      return copied.stream()
          .map(
              block ->
                  new Provider(
                      new NodeRegistry.Position(
                          level.dimension().location().toString(), block.asLong()),
                      kind))
          .sorted(Comparator.comparingLong(provider -> provider.position().block()))
          .toList();
    }
    var state = chunk.getBlockState(position);
    var first =
        new Provider(
            new NodeRegistry.Position(level.dimension().location().toString(), position.asLong()),
            kind);
    if (!(state.getBlock() instanceof ChestBlock)
        || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return List.of(first);
    var otherPosition = position.relative(ChestBlock.getConnectedDirection(state));
    var otherChunk = Loaded.chunk(level, otherPosition);
    if (otherChunk == null) return null;
    var second =
        new Provider(
            new NodeRegistry.Position(
                level.dimension().location().toString(), otherPosition.asLong()),
            kind);
    return position.asLong() < otherPosition.asLong()
        ? List.of(first, second)
        : List.of(second, first);
  }
}
