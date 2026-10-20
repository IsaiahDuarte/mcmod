package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformFilterTags;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers.Buffered;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers.Digital;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers.Endpoint;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers.External;
import dev.izzy.factorycore.platform.storage.WorldStorageTransfers.PlayerInventory;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Endpoint layer of {@link WorldStorageTransfers}: resolves endpoints to live resource ports,
 * checks each endpoint's current authority, recovers persisted endpoint references and owns the
 * per-buffer rate budgets and player-inventory endpoints. It never schedules or queues work.
 */
final class TransferEndpoints {
  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final DeviceSavedData saved;
  private WorldPlayerInventories players;

  TransferEndpoints(MinecraftServer server, WorldNetworks networks, DeviceSavedData saved) {
    this.server = server;
    this.networks = networks;
    this.saved = saved;
  }

  WorldPlayerInventories players() {
    if (players == null) players = new WorldPlayerInventories(server);
    return players;
  }

  BufferPort bufferPort(Buffered endpoint) {
    return new BufferPort(endpoint);
  }

  /** {@code ownedReturn}: a validated return of already-owned staging into its buffer. */
  BufferPort bufferPort(Buffered endpoint, boolean ownedReturn) {
    return new BufferPort(endpoint, ownedReturn);
  }

  Port digitalPort(DeviceRegistry.Handle handle) {
    return new Port(handle);
  }

  /** Drops a disconnected player's endpoints without creating the inventory registry. */
  void disconnected(ServerPlayer player) {
    if (players != null) players.disconnected(player);
  }

  boolean compatible(Endpoint endpoint, ResourceKey key) {
    if (endpoint instanceof Buffered || endpoint instanceof PlayerInventory) {
      if (key.kind() != ResourceKind.ITEM) return false;
      try {
        new PlatformResourceCodec(server.registryAccess()).itemStack(key, 1);
        return true;
      } catch (RuntimeException unsupported) {
        return false;
      }
    }
    return endpoint instanceof Digital digital
        ? saved.registry().definition(digital.handle()).kind() == key.kind()
        : ((External) endpoint).handle().kind() == key.kind();
  }

  Endpoint recover(UUID world, TransferContext.Endpoint reference) {
    try {
      if (reference.type() == TransferContext.Type.DIGITAL) {
        var handle = new DeviceRegistry.Handle(world, reference.backing(), reference.generation());
        return physical(handle) == null ? null : new Digital(handle);
      }
      if (reference.type() == TransferContext.Type.BUFFER) {
        var endpoint =
            new Buffered(
                new NodeRegistry.Handle(
                    world,
                    reference.device(),
                    reference.generation(),
                    NodeRegistry.Kind.BUFFERED_INTERFACE),
                reference.configuration());
        return physical(endpoint) == null ? null : endpoint;
      }
      var registration =
          networks
              .connectors()
              .recover(
                  new NodeRegistry.Handle(
                      world,
                      reference.device(),
                      reference.generation(),
                      saved.nodes().kind(reference.device())),
                  reference.configuration(),
                  reference.backing());
      return registration.handle() == null ? null : new External(registration.handle());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  /** Owned returns may survive preset replacement, but never lease/world/physical replacement. */
  Endpoint recoverOwnedBufferSource(UUID world, TransferContext.Endpoint reference) {
    try {
      var handle =
          new NodeRegistry.Handle(
              world,
              reference.backing(),
              reference.generation(),
              NodeRegistry.Kind.BUFFERED_INTERFACE);
      var holder = physical(new Buffered(handle, reference.configuration()), true);
      return holder == null ? null : new Buffered(handle, holder.reference().configuration());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  static ResourcePort bounded(ResourcePort delegate, ResourceKey key) {
    long quantum = key.kind() == ResourceKind.ITEM ? 128 : 1000;
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return delegate.backingId();
      }

      @Override
      public long simulateInsert(ResourceKey resource, long amount) {
        return delegate.simulateInsert(resource, Math.min(amount, quantum));
      }

      @Override
      public long insert(ResourceKey resource, long amount) {
        return delegate.insert(resource, Math.min(amount, quantum));
      }

      @Override
      public long extract(ResourceKey resource, long amount) {
        throw new IllegalStateException("Recovery never repeats extraction");
      }
    };
  }

  static ResourcePort unavailable(UUID backing) {
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return backing;
      }

      @Override
      public long simulateInsert(ResourceKey key, long amount) {
        return 0;
      }

      @Override
      public long insert(ResourceKey key, long amount) {
        return 0;
      }

      @Override
      public long extract(ResourceKey key, long amount) {
        throw new IllegalStateException("Unavailable recovery endpoint cannot extract");
      }
    };
  }

  TransferContext context(NodeRegistry.Position origin, Endpoint source, Endpoint destination) {
    var scope = networks.membership(origin).scope();
    return new TransferContext(
        saved.registry().world(),
        scope.network(),
        scope.segment(),
        origin,
        reference(source),
        reference(destination));
  }

  TransferContext.Endpoint reference(Endpoint endpoint) {
    if (endpoint instanceof PlayerInventory player)
      return new TransferContext.Endpoint(
          TransferContext.Type.PLAYER,
          player.principal(),
          player.backing(),
          player.connection(),
          player.slot() < 0 ? 37 : player.slot() + 1);
    if (endpoint instanceof Buffered buffer)
      return new TransferContext.Endpoint(
          TransferContext.Type.BUFFER,
          buffer.handle().id(),
          buffer.handle().id(),
          buffer.handle().generation(),
          buffer.configuration());
    if (endpoint instanceof Digital digital)
      return new TransferContext.Endpoint(
          TransferContext.Type.DIGITAL,
          digital.handle().backing(),
          digital.handle().backing(),
          digital.handle().generation(),
          0);
    var handle = ((External) endpoint).handle();
    return new TransferContext.Endpoint(
        TransferContext.Type.EXTERNAL,
        handle.connector().id(),
        handle.backing(),
        handle.connector().generation(),
        handle.generation());
  }

  ResourcePort port(Endpoint endpoint, NodeRegistry.Position origin) {
    ResourcePort delegate;
    if (endpoint instanceof PlayerInventory player) delegate = players().port(player, origin);
    else if (endpoint instanceof Buffered buffer) delegate = new BufferPort(buffer);
    else
      delegate =
          endpoint instanceof Digital digital
              ? new Port(digital.handle())
              : networks.connectors().port(((External) endpoint).handle());
    return routedPort(endpoint, origin, delegate);
  }

  /** Current endpoint coordinate; stale holders never acquire a new route implicitly. */
  NodeRegistry.Position endpointPosition(Endpoint endpoint, NodeRegistry.Position origin) {
    try {
      if (endpoint instanceof PlayerInventory) return origin;
      if (endpoint instanceof Digital digital) {
        var location = saved.registry().definition(digital.handle()).location();
        return location == null
            ? null
            : new NodeRegistry.Position(location.dimension(), location.position());
      }
      var handle =
          endpoint instanceof Buffered buffer
              ? buffer.handle()
              : ((External) endpoint).handle().connector();
      return saved.nodes().definition(handle).position();
    } catch (IllegalArgumentException | IllegalStateException stale) {
      return null;
    }
  }

  /**
   * Transfer staging belongs to the accepted origin segment; each actual endpoint leg is metered.
   */
  ResourcePort routedPort(Endpoint endpoint, NodeRegistry.Position origin, ResourcePort delegate) {
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return delegate.backingId();
      }

      @Override
      public long simulateInsert(ResourceKey key, long offered) {
        var position = endpointPosition(endpoint, origin);
        if (position == null) return 0;
        long fit = networks.movementAvailable(origin, position, key, offered);
        return fit == 0 ? 0 : delegate.simulateInsert(key, fit);
      }

      @Override
      public long insert(ResourceKey key, long offered) {
        return mutate(key, offered, true);
      }

      @Override
      public long extract(ResourceKey key, long offered) {
        return mutate(key, offered, false);
      }

      private long mutate(ResourceKey key, long offered, boolean inserting) {
        var position = endpointPosition(endpoint, origin);
        if (position == null) return 0;
        var permit = networks.movement(origin, position, key, offered);
        if (permit.amount() == 0) {
          permit.finish(0);
          return 0;
        }
        long actual =
            inserting
                ? delegate.insert(key, permit.amount())
                : delegate.extract(key, permit.amount());
        permit.finish(actual);
        return actual;
      }
    };
  }

  private static final class BufferBudget {
    int tick;
    long inserted;
    long extracted;

    BufferBudget(int tick) {
      this.tick = tick;
    }
  }

  private final Map<UUID, BufferBudget> bufferBudgets = new HashMap<>();

  BufferBudget bufferBudget(UUID backing) {
    int tick = server.getTickCount();
    var budget = bufferBudgets.get(backing);
    if (budget == null) {
      if (bufferBudgets.size() == BufferRegistry.MAX_BUFFERS)
        throw new IllegalStateException("Buffer rate metadata limit");
      budget = new BufferBudget(tick);
      bufferBudgets.put(backing, budget);
    } else if (budget.tick != tick) {
      budget.tick = tick;
      budget.inserted = 0;
      budget.extracted = 0;
    }
    return budget;
  }

  final class BufferPort implements ResourcePort {
    private final Buffered endpoint;
    private final BufferedInterfaceEntity owner;
    private final boolean ownedReturn;

    BufferPort(Buffered endpoint) {
      this(endpoint, false);
    }

    BufferPort(Buffered endpoint, boolean ownedReturn) {
      this.ownedReturn = ownedReturn;
      this.endpoint = endpoint;
      owner = physical(endpoint);
    }

    private int rate() {
      var deployment = saved.buffers().deployment(backingId());
      int rate = deployment == null ? 64 : deployment.policy().rate();
      // Defensive: the physical tier bounds a canonical policy even if they ever disagree.
      return owner == null ? rate : Math.min(rate, owner.tier().bufferRate());
    }

    private long stockRoom(ResourceKey key, long amount) {
      var deployment = saved.buffers().deployment(backingId());
      if (ownedReturn || deployment == null || deployment.mode() != BufferRegistry.Mode.STOCK)
        return amount;
      var tags = new PlatformFilterTags(server);
      var filter = deployment.policy().filter();
      if (!filter.matches(key, tags)) return amount;
      long resident = 0;
      for (var slot : owner.inventory().snapshot().slots())
        if (slot.key() != null && filter.matches(slot.key(), tags)) resident += slot.count();
      return Math.min(amount, Math.max(0, deployment.policy().target() - resident));
    }

    private boolean current() {
      try {
        return owner != null && physical(endpoint) == owner;
      } catch (IllegalArgumentException | IllegalStateException unavailable) {
        return false;
      }
    }

    OperationResult available(ResourceKey key, long amount) {
      if (!current()) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      long rate = Math.max(0, rate() - bufferBudget(backingId()).extracted);
      if (rate == 0 && amount > 0) return new OperationResult(0, OperationResult.Reason.RATE_LIMIT);
      long available = Math.min(Math.min(rate, amount), owner.inventory().total(key));
      return new OperationResult(
          available,
          amount == 0 || available > 0
              ? OperationResult.Reason.OK
              : OperationResult.Reason.SHORTAGE);
    }

    OperationResult admit(ResourceKey key, long amount) {
      if (!current()) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      long rate = Math.max(0, rate() - bufferBudget(backingId()).inserted);
      if (rate == 0 && amount > 0) return new OperationResult(0, OperationResult.Reason.RATE_LIMIT);
      long wanted = stockRoom(key, Math.min(amount, rate));
      if (wanted == 0 && amount > 0) return new OperationResult(0, OperationResult.Reason.FULL);
      return owner.inventory().admitInsert(key, wanted);
    }

    @Override
    public UUID backingId() {
      return endpoint.handle().id();
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      return admit(key, amount).amount();
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      if (!current()) return 0;
      var budget = bufferBudget(backingId());
      long accepted =
          owner
              .inventory()
              .insert(key, stockRoom(key, Math.min(amount, Math.max(0, rate() - budget.inserted))));
      budget.inserted += accepted;
      return accepted;
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      if (!current()) return 0;
      var budget = bufferBudget(backingId());
      long extracted =
          owner.inventory().extract(key, Math.min(amount, Math.max(0, rate() - budget.extracted)));
      budget.extracted += extracted;
      return extracted;
    }
  }

  boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Endpoint endpoint,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    if (endpoint instanceof PlayerInventory player)
      return key.kind() == ResourceKind.ITEM && players().allowed(actor, origin, player, operation);

    if (endpoint instanceof Buffered buffer) return allowed(actor, origin, buffer, key, operation);
    return endpoint instanceof Digital digital
        ? allowed(actor, origin, digital.handle(), key, operation)
        : networks
            .connectors()
            .allowed(actor, origin, ((External) endpoint).handle(), key, operation);
  }

  boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Buffered endpoint,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    try {
      var holder = physical(endpoint);
      if (key.kind() != ResourceKind.ITEM || holder == null) return false;
      var target = networks.membership(holder.position());
      var from = networks.membership(origin);
      if (target.status() != NetworkTopology.Status.VALID
          || from.status() != NetworkTopology.Status.VALID
          || !target.scope().network().equals(from.scope().network())) return false;
      var view = networks.storage(actor, origin, key, operation);
      return view.decision() == NetworkAuthority.Decision.ALLOWED
          && view.segments().contains(target.scope().segment());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return false;
    }
  }

  BufferedInterfaceEntity physical(Buffered endpoint) {
    return physical(endpoint, false);
  }

  BufferedInterfaceEntity physical(Buffered endpoint, boolean ownedReturn) {
    var location = saved.nodes().definition(endpoint.handle()).position();
    if (location == null) return null;
    var level = server.getLevel(Loaded.dimension(location.dimension()));
    if (level == null) return null;
    var position = BlockPos.of(location.block());
    var chunk = Loaded.chunk(level, position);
    if (chunk == null
        || !(chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK)
            instanceof BufferedInterfaceEntity holder)
        || !endpoint.handle().equals(holder.handle())
        || !ownedReturn && holder.reference().configuration() != endpoint.configuration())
      return null;
    holder.validatePlaced();
    return holder;
  }

  boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      DeviceRegistry.Handle handle,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    try {
      var definition = saved.registry().definition(handle);
      if (definition.kind() != key.kind()
          || definition.location() == null
          || physical(handle) == null) return false;
      var location = definition.location();
      var target =
          networks.membership(new NodeRegistry.Position(location.dimension(), location.position()));
      var from = networks.membership(origin);
      if (target.status() != NetworkTopology.Status.VALID
          || from.status() != NetworkTopology.Status.VALID
          || !target.scope().network().equals(from.scope().network())) return false;
      var view = networks.storage(actor, origin, key, operation);
      return view.decision() == NetworkAuthority.Decision.ALLOWED
          && view.segments().contains(target.scope().segment());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return false;
    }
  }

  BlockEntity physical(DeviceRegistry.Handle handle) {
    var location = saved.registry().definition(handle).location();
    if (location == null) return null;
    var dimension = Loaded.dimension(location.dimension());
    ServerLevel level = server.getLevel(dimension);
    if (level == null) return null;
    var position = BlockPos.of(location.position());
    var chunk = Loaded.chunk(level, position);
    if (chunk == null) return null;
    var entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
    if (entity == null || entity.isRemoved()) return null;
    if (entity instanceof StorageDriveEntity drive
        && drive.diagnostic().isEmpty()
        && location.slot() < 4) {
      var stack = drive.cell(location.slot());
      return !stack.isEmpty() && handle.equals(CellHandleData.read(stack)) ? drive : null;
    }
    if (entity instanceof EnergyBankEntity bank
        && location.slot() == 0
        && handle.equals(bank.handle())) {
      bank.validatePlaced();
      return bank;
    }
    return null;
  }

  final class Port implements ResourcePort {
    private final DeviceRegistry.Handle handle;

    Port(DeviceRegistry.Handle handle) {
      this.handle = handle;
    }

    @Override
    public UUID backingId() {
      return handle.backing();
    }

    OperationResult admit(ResourceKey key, long amount) {
      var entity = physical(handle);
      if (entity == null) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      return entity instanceof EnergyBankEntity bank
          ? saved.energyBanks().receive(handle, bank.tier(), amount, true)
          : saved.registry().simulateInsert(handle, key, amount);
    }

    @Override
    public long simulateInsert(ResourceKey key, long amount) {
      return admit(key, amount).amount();
    }

    @Override
    public long insert(ResourceKey key, long amount) {
      var entity = physical(handle);
      if (entity == null || entity instanceof EnergyBankEntity && !key.equals(ResourceKey.ENERGY))
        return 0;
      return (entity instanceof EnergyBankEntity bank
              ? saved.energyBanks().receive(handle, bank.tier(), amount, false)
              : saved.registry().insert(handle, key, amount))
          .amount();
    }

    @Override
    public long extract(ResourceKey key, long amount) {
      var entity = physical(handle);
      if (entity == null || entity instanceof EnergyBankEntity && !key.equals(ResourceKey.ENERGY))
        return 0;
      return (entity instanceof EnergyBankEntity bank
              ? saved.energyBanks().extract(handle, bank.tier(), amount, false)
              : saved.registry().extract(handle, key, amount, null))
          .amount();
    }
  }
}
