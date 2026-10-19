package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.EnergyHandlerPort;
import dev.izzy.factorycore.platform.resource.FluidHandlerPort;
import dev.izzy.factorycore.platform.resource.ItemHandlerPort;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** Loaded foreign registrations; neither owns nor serializes a foreign provider's contents. */
public final class WorldConnectors {
  public record Handle(
      NodeRegistry.Handle connector,
      long generation,
      UUID provider,
      UUID backing,
      ResourceKind kind) {
    public Handle {
      Objects.requireNonNull(connector);
      Objects.requireNonNull(provider);
      Objects.requireNonNull(backing);
      Objects.requireNonNull(kind);
      if (generation <= 0
          || (connector.kind() != NodeRegistry.Kind.CONNECTOR
              && connector.kind() != NodeRegistry.Kind.MACHINE_INTERFACE))
        throw new IllegalArgumentException("Invalid external endpoint handle");
    }
  }

  public record Registration(OperationResult.Reason reason, Handle handle) {}

  /** Discover only a currently authorized loaded canonical connector; never loads chunks. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns borrowed levels.
  public Registration discover(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      NodeRegistry.Handle node,
      ResourceKey key) {
    checkThread();
    if ((node.kind() != NodeRegistry.Kind.CONNECTOR
            && node.kind() != NodeRegistry.Kind.MACHINE_INTERFACE)
        || saved.nodes().validate(node) != NodeRegistry.Failure.OK
        || networks.authorize(actor, origin, NetworkPermissions.Permission.VIEW)
            != NetworkAuthority.Decision.ALLOWED)
      return new Registration(OperationResult.Reason.DENIED, null);
    var position = saved.nodes().definition(node).position();
    if (position == null) return new Registration(OperationResult.Reason.OFFLINE, null);
    var from = networks.membership(origin);
    var target = networks.membership(position);
    var view = networks.storage(actor, origin, key, NetworkPermissions.Permission.VIEW);
    if (from.status() != NetworkTopology.Status.VALID
        || target.status() != NetworkTopology.Status.VALID
        || !from.scope().network().equals(target.scope().network())
        || view.decision() != NetworkAuthority.Decision.ALLOWED
        || !view.segments().contains(target.scope().segment())
        || node.kind() == NodeRegistry.Kind.MACHINE_INTERFACE
            && !from.scope().segment().equals(target.scope().segment()))
      return new Registration(OperationResult.Reason.DENIED, null);
    var level =
        server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(position.dimension())));
    if (level == null) return new Registration(OperationResult.Reason.OFFLINE, null);
    var block = BlockPos.of(position.block());
    var chunk = level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
    if (chunk == null
        || !(chunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK)
            instanceof ConnectorEntity owner)
        || !node.equals(owner.handle()))
      return new Registration(OperationResult.Reason.OFFLINE, null);
    if (owner.settings().kind() != key.kind())
      return new Registration(OperationResult.Reason.WRONG_RESOURCE, null);
    return bind(owner);
  }

  public int priority(Handle handle) {
    checkThread();
    var binding = current(handle);
    if (binding == null) throw new IllegalStateException("Stale connector priority");
    return binding.owner.settings().priority();
  }

  public record Observation(
      OperationResult.Reason reason,
      dev.izzy.factorycore.platform.resource.ExternalStockPage page) {}

  /** Shared scheduled callers authenticate VIEW before any provider reads. */
  public Observation observe(
      NetworkPermissions.Actor actor, NodeRegistry.Position origin, Handle handle, int cursor) {
    checkThread();
    if (cursor < 0) throw new IllegalArgumentException("Negative stock cursor");
    if (calling || probing) return new Observation(OperationResult.Reason.DENIED, null);
    var identity =
        new ResourceKey(
            handle.kind(),
            handle.kind() == ResourceKind.ENERGY ? "neoforge:energy" : "factorycore:catalog",
            new byte[0]);
    if (!allowed(actor, origin, handle, identity, NetworkPermissions.Permission.VIEW))
      return new Observation(OperationResult.Reason.DENIED, null);
    var binding = current(handle);
    if (binding == null) return new Observation(OperationResult.Reason.OFFLINE, null);
    if (calling || probing) return new Observation(OperationResult.Reason.DENIED, null);
    calling = true;
    try {
      var settings = binding.owner.settings();
      var page =
          dev.izzy.factorycore.platform.resource.ExternalStockPage.read(
              handle.kind(),
              binding.handler,
              new PlatformResourceCodec(binding.owner.serverLevel().registryAccess()),
              settings.firstSlot(),
              settings.slotCount(),
              cursor);
      if (bindings.get(handle.connector().id()) != binding)
        return new Observation(OperationResult.Reason.OFFLINE, null);
      if (!allowed(actor, origin, handle, identity, NetworkPermissions.Permission.VIEW))
        return new Observation(OperationResult.Reason.DENIED, null);
      return new Observation(OperationResult.Reason.OK, page);
    } catch (RuntimeException fault) {
      binding.owner.quarantine(fault);
      return new Observation(OperationResult.Reason.HANDLER_FAULT, null);
    } finally {
      calling = false;
    }
  }

  private record RateOwner(UUID connector, ResourceKind kind) {}

  private record Provider(NodeRegistry.Position position, ResourceKind kind) {}

  private final class Binding {
    final ConnectorEntity owner;
    final Handle handle;
    final Object handler;
    final List<Provider> providers;
    final ResourcePort delegate;
    final ResourcePort port;
    final Direction face;
    final UUID group;
    final boolean indexed;

    Binding(
        ConnectorEntity owner,
        Object handler,
        List<Provider> providers,
        ResourcePort delegate,
        UUID backing,
        UUID group,
        boolean indexed) {
      this.owner = owner;
      this.group = group;
      this.indexed = indexed;
      face = owner.targetDirection();
      this.handler = handler;
      this.providers = providers;
      this.delegate = delegate;
      handle =
          new Handle(
              owner.handle(),
              owner.generation(),
              owner.providerToken(),
              backing,
              owner.settings().kind());
      port =
          new ResourcePort() {
            @Override
            public UUID backingId() {
              return handle.backing();
            }

            @Override
            public long simulateInsert(ResourceKey key, long amount) {
              return change(Binding.this, key, amount, true, true);
            }

            @Override
            public long insert(ResourceKey key, long amount) {
              return change(Binding.this, key, amount, true, false);
            }

            @Override
            public long extract(ResourceKey key, long amount) {
              return change(Binding.this, key, amount, false, false);
            }
          };
    }
  }

  private final MinecraftServer server;
  private final WorldNetworks networks;
  private final DeviceSavedData saved;
  private final Map<UUID, Binding> bindings = new HashMap<>();
  private final Map<ConnectorEntity, Binding> owners = new IdentityHashMap<>();
  private final Map<Provider, java.util.List<Binding>> providers = new HashMap<>();
  private final Map<Object, java.util.List<Binding>> handlers = new IdentityHashMap<>();
  private final Map<UUID, Binding> backingOwners = new HashMap<>();
  private final Map<UUID, long[]> rateBudgets = new HashMap<>();
  private final Map<RateOwner, long[]> connectorBudgets = new HashMap<>();
  private int providerClaims;
  private int rateTick = -1;
  private boolean probing;
  private boolean calling;

  public WorldConnectors(MinecraftServer server, WorldNetworks networks) {
    this.server = Objects.requireNonNull(server);
    this.networks = Objects.requireNonNull(networks);
    saved = DeviceSavedData.get(server);
  }

  public Registration bind(ConnectorEntity owner) {
    checkThread();
    if (probing) return new Registration(OperationResult.Reason.DENIED, null);
    probing = true;
    try {
      return probe(owner);
    } finally {
      probing = false;
    }
  }

  private Registration probe(ConnectorEntity owner) {
    try {
      owner.validatePlaced();
      if (owner.isRemoved()
          || owner.serverLevel().getServer() != server
          || networks.membership(owner.position()).status() != NetworkTopology.Status.VALID)
        return new Registration(OperationResult.Reason.OFFLINE, null);
      var providerBlock = providerEntity(owner);
      if (providerBlock != null
          && !(providerBlock
              instanceof dev.izzy.factorycore.platform.resource.ExternalBackingProvider)
          && !(providerBlock instanceof net.minecraft.world.Container
              && providerBlock
                  .getClass()
                  .getPackageName()
                  .equals("net.minecraft.world.level.block.entity")))
        return new Registration(OperationResult.Reason.UNSUPPORTED, null);
      boolean vanilla =
          providerBlock instanceof net.minecraft.world.Container
              && providerBlock
                  .getClass()
                  .getPackageName()
                  .equals("net.minecraft.world.level.block.entity");
      if (vanilla && owner.settings().kind() != ResourceKind.ITEM)
        return new Registration(OperationResult.Reason.UNSUPPORTED, null);
      var selected = providerPositions(owner);
      if (selected == null) {
        invalidate(owner);
        return new Registration(OperationResult.Reason.OFFLINE, null);
      }
      if (providerBlock == null) return new Registration(OperationResult.Reason.UNSUPPORTED, null);
      Object handler;
      try {
        handler = owner.capability();
      } catch (RuntimeException fault) {
        owner.quarantine(fault);
        invalidate(owner);
        return new Registration(OperationResult.Reason.HANDLER_FAULT, null);
      }
      if (handler == null) {
        invalidate(owner);
        return new Registration(OperationResult.Reason.OFFLINE, null);
      }
      if (vanilla && !vanillaBacking(owner, handler, selected))
        return new Registration(OperationResult.Reason.UNSUPPORTED, null);
      var settings = owner.settings();
      java.util.List<dev.izzy.factorycore.platform.resource.ExternalBackingProvider.PortWindow>
          partitions;
      try {
        var describedPorts =
            providerBlock
                    instanceof
                    dev.izzy.factorycore.platform.resource.ExternalBackingProvider described
                ? described.factoryCorePorts(settings.kind())
                : java.util.List
                    .<dev.izzy.factorycore.platform.resource.ExternalBackingProvider.PortWindow>
                        of();
        if (describedPorts == null || describedPorts.size() > 64)
          return new Registration(OperationResult.Reason.UNSUPPORTED, null);
        partitions = java.util.List.copyOf(describedPorts);
      } catch (RuntimeException fault) {
        owner.quarantine(fault);
        invalidate(owner);
        return new Registration(OperationResult.Reason.HANDLER_FAULT, null);
      }
      if (!validPartitions(partitions, settings))
        return new Registration(OperationResult.Reason.UNSUPPORTED, null);
      boolean indexed = !partitions.isEmpty();
      if (settings.kind() == ResourceKind.FLUID
          && !settings.wholeFluidHandler()
          && !(handler instanceof dev.izzy.factorycore.platform.resource.IndexedFluidHandler))
        return new Registration(OperationResult.Reason.UNSUPPORTED, null);
      UUID group;
      try {
        group = persistentBacking(owner, selected);
      } catch (IllegalArgumentException invalidIdentity) {
        owner.quarantine(invalidIdentity);
        invalidate(owner);
        return new Registration(OperationResult.Reason.DENIED, null);
      }
      UUID backing =
          indexed ? indexedBacking(group, settings.firstSlot(), settings.slotCount()) : group;
      var previous = bindings.get(owner.handle().id());
      if (previous != null
          && previous.owner == owner
          && previous.handler == handler
          && previous.handle.generation() == owner.generation()
          && previous.handle.provider().equals(owner.providerToken())
          && previous.providers.equals(selected)
          && previous.handle.backing().equals(backing)
          && previous.indexed == indexed)
        return new Registration(OperationResult.Reason.OK, previous.handle);
      if (previous != null && previous.owner != owner)
        return new Registration(OperationResult.Reason.DENIED, null);
      invalidate(owner);
      for (var provider : selected)
        if (overlaps(providers.get(provider), owner, group, indexed))
          return new Registration(OperationResult.Reason.OVERLAP, null);
      if (overlaps(handlers.get(handler), owner, group, indexed))
        return new Registration(OperationResult.Reason.OVERLAP, null);
      if (bindings.size() >= NodeRegistry.MAX_NODES || providerClaims > 16384 - selected.size())
        return new Registration(OperationResult.Reason.TECHNICAL_LIMIT, null);
      if (backingOwners.containsKey(backing))
        return new Registration(OperationResult.Reason.OVERLAP, null);
      if (saved.registry().containsBacking(backing) || saved.nodes().contains(backing))
        throw new IllegalArgumentException("Provider/digital identity collision");
      var codec = new PlatformResourceCodec(owner.serverLevel().registryAccess());
      ResourcePort delegate =
          switch (settings.kind()) {
            case ITEM -> {
              var items = (IItemHandler) handler;
              int count = Math.min(settings.slotCount(), items.getSlots() - settings.firstSlot());
              if (count <= 0)
                throw new IllegalArgumentException("Selected item window is unavailable");
              yield new ItemHandlerPort(backing, items, codec, settings.firstSlot(), count);
            }
            case FLUID ->
                new FluidHandlerPort(
                    backing,
                    (IFluidHandler) handler,
                    codec,
                    settings.firstSlot(),
                    settings.slotCount());
            case ENERGY -> new EnergyHandlerPort(backing, (IEnergyStorage) handler);
          };
      var binding = new Binding(owner, handler, selected, delegate, backing, group, indexed);
      bindings.put(owner.handle().id(), binding);
      owners.put(owner, binding);
      for (var provider : selected)
        providers.computeIfAbsent(provider, unused -> new java.util.ArrayList<>()).add(binding);
      providerClaims += selected.size();
      handlers.computeIfAbsent(handler, unused -> new java.util.ArrayList<>()).add(binding);
      backingOwners.put(backing, binding);
      return new Registration(OperationResult.Reason.OK, binding.handle);
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return new Registration(OperationResult.Reason.DENIED, null);
    }
  }

  private static boolean validPartitions(
      java.util.List<dev.izzy.factorycore.platform.resource.ExternalBackingProvider.PortWindow>
          partitions,
      ConnectorSettings settings) {
    if (partitions == null || partitions.size() > 64) return false;
    if (partitions.isEmpty()) return true;
    if (settings.kind() == ResourceKind.ENERGY || settings.wholeFluidHandler()) return false;
    boolean selected = false;
    for (int index = 0; index < partitions.size(); index++) {
      var part = partitions.get(index);
      if (part == null
          || settings.kind() == ResourceKind.FLUID && part.first() == 0 && part.count() == 64)
        return false;
      if (part.first() == settings.firstSlot() && part.count() == settings.slotCount())
        selected = true;
      for (int otherIndex = 0; otherIndex < index; otherIndex++) {
        var other = partitions.get(otherIndex);
        if ((long) part.first() < (long) other.first() + other.count()
            && (long) other.first() < (long) part.first() + part.count()) return false;
      }
    }
    return selected;
  }

  private static <K> void removeClaim(
      Map<K, java.util.List<Binding>> claims, K key, Binding binding) {
    var list = claims.get(key);
    if (list == null) return;
    list.remove(binding);
    if (list.isEmpty()) claims.remove(key);
  }

  private boolean overlaps(
      java.util.List<Binding> existing, ConnectorEntity owner, UUID group, boolean indexed) {
    if (existing == null) return false;
    var settings = owner.settings();
    for (var binding : existing) {
      var other = binding.owner.settings();
      if (!indexed
          || !binding.indexed
          || !group.equals(binding.group)
          || settings.wholeFluidHandler()
          || other.wholeFluidHandler()
          || (long) settings.firstSlot() < (long) other.firstSlot() + other.slotCount()
              && (long) other.firstSlot() < (long) settings.firstSlot() + settings.slotCount())
        return true;
    }
    return false;
  }

  /** Remembered metadata only, including ownership conflicts; never authorizes resource access. */
  public Handle recoveryRegistration(ConnectorEntity owner) {
    checkThread();
    var binding = owners.get(Objects.requireNonNull(owner));
    if (binding == null
        || !binding.handle.connector().equals(owner.handle())
        || binding.handle.generation() != owner.generation()
        || !binding.handle.provider().equals(owner.providerToken())
        || binding.handle.kind() != owner.settings().kind()
        || binding.face != owner.targetDirection()) return null;
    return binding.handle;
  }

  /** Releases only this physical owner, so a clone cannot unregister the original. */
  public void invalidate(ConnectorEntity owner) {
    checkThread();
    var binding = owners.remove(owner);
    if (binding == null) return;
    bindings.remove(binding.handle.connector().id(), binding);
    saved.transfers().unbindBacking(binding.handle.backing());
    removeClaim(handlers, binding.handler, binding);
    backingOwners.remove(binding.handle.backing(), binding);
    for (var provider : binding.providers) removeClaim(providers, provider, binding);
    providerClaims -= binding.providers.size();
  }

  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  private Binding current(Handle handle) {
    if (saved.nodes().validate(handle.connector()) != NodeRegistry.Failure.OK) return null;
    var location = saved.nodes().definition(handle.connector()).position();
    if (location == null) return null;
    var level =
        server.getLevel(
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(location.dimension())));
    if (level == null) return null;
    var position = BlockPos.of(location.block());
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null
        || !(chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK)
            instanceof ConnectorEntity owner)
        || !handle.connector().equals(owner.handle())) return null;
    var registration = bind(owner);
    return handle.equals(registration.handle()) ? bindings.get(handle.connector().id()) : null;
  }

  /** Reacquire only the saved canonical lease/configuration and exact persistent backing. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  public Registration recover(NodeRegistry.Handle connector, long configuration, UUID backing) {
    checkThread();
    try {
      var location = saved.nodes().definition(connector).position();
      if (location == null) return new Registration(OperationResult.Reason.OFFLINE, null);
      var level =
          server.getLevel(
              net.minecraft.resources.ResourceKey.create(
                  net.minecraft.core.registries.Registries.DIMENSION,
                  net.minecraft.resources.ResourceLocation.parse(location.dimension())));
      if (level == null) return new Registration(OperationResult.Reason.OFFLINE, null);
      var position = BlockPos.of(location.block());
      var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
      if (chunk == null
          || !(chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK)
              instanceof ConnectorEntity owner)
          || !connector.equals(owner.handle())
          || configuration != owner.generation())
        return new Registration(OperationResult.Reason.DENIED, null);
      var current = bind(owner);
      return current.handle() != null && backing.equals(current.handle().backing())
          ? current
          : new Registration(OperationResult.Reason.DENIED, null);
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return new Registration(OperationResult.Reason.DENIED, null);
    }
  }

  public boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Handle handle,
      ResourceKey key,
      NetworkPermissions.Permission operation) {
    checkThread();
    if (key.kind() != handle.kind()
        || networks.authorize(actor, origin, operation) != NetworkAuthority.Decision.ALLOWED
        || saved.nodes().validate(handle.connector()) != NodeRegistry.Failure.OK) return false;
    var location = saved.nodes().definition(handle.connector()).position();
    if (location == null) return false;
    var target = networks.membership(location);
    var from = networks.membership(origin);
    if (target.status() != NetworkTopology.Status.VALID
        || from.status() != NetworkTopology.Status.VALID
        || !target.scope().network().equals(from.scope().network())
        || handle.connector().kind() == NodeRegistry.Kind.MACHINE_INTERFACE
            && !target.scope().segment().equals(from.scope().segment())) return false;
    var view = networks.storage(actor, origin, key, operation);
    if (view.decision() != NetworkAuthority.Decision.ALLOWED
        || !view.segments().contains(target.scope().segment())) return false;
    var binding = current(handle);
    if (binding == null) return false;
    var access = binding.owner.settings().access();
    return !(operation == NetworkPermissions.Permission.WITHDRAW
            && access == ConnectorSettings.Access.WRITE_ONLY
        || operation == NetworkPermissions.Permission.DEPOSIT
            && access == ConnectorSettings.Access.READ_ONLY);
  }

  public ResourcePort port(Handle handle) {
    checkThread();
    var binding = current(handle);
    if (binding == null) throw new IllegalStateException("Stale/offline foreign endpoint");
    return binding.port;
  }

  /** Known owned returns bypass only Stock goals, never endpoint/rate/access/capacity checks. */
  ResourcePort returnPort(Handle handle) {
    checkThread();
    var binding = current(handle);
    if (binding == null) throw new IllegalStateException("Stale return endpoint");
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return handle.backing();
      }

      @Override
      public long simulateInsert(ResourceKey key, long amount) {
        return change(binding, key, amount, true, true, null, true);
      }

      @Override
      public long insert(ResourceKey key, long amount) {
        return change(binding, key, amount, true, false, null, true);
      }

      @Override
      public long extract(ResourceKey key, long amount) {
        throw new IllegalStateException("Owned return port cannot extract");
      }
    };
  }

  public ResourcePort claimedPort(Handle handle, UUID owner) {
    checkThread();
    Objects.requireNonNull(owner);
    var binding = current(handle);
    if (binding == null) throw new IllegalStateException("Stale/offline foreign endpoint");
    return new ResourcePort() {
      @Override
      public UUID backingId() {
        return handle.backing();
      }

      @Override
      public long simulateInsert(ResourceKey key, long amount) {
        return change(binding, key, amount, true, true);
      }

      @Override
      public long insert(ResourceKey key, long amount) {
        return change(binding, key, amount, true, false);
      }

      @Override
      public long extract(ResourceKey key, long amount) {
        return change(binding, key, amount, false, false, owner);
      }
    };
  }

  /** Fresh exact total across a bounded complete window, never a retained observation. */
  public OperationResult stock(Handle handle, ResourceKey key) {
    checkThread();
    if (calling || probing) return new OperationResult(0, OperationResult.Reason.DENIED);
    if (handle.kind() != key.kind())
      return new OperationResult(0, OperationResult.Reason.WRONG_RESOURCE);
    var binding = current(handle);
    if (binding == null) return new OperationResult(0, OperationResult.Reason.OFFLINE);
    calling = true;
    try {
      if (key.kind() == ResourceKind.FLUID
          && binding.owner.settings().wholeFluidHandler()
          && ((IFluidHandler) binding.handler).getTanks() > 64)
        return new OperationResult(0, OperationResult.Reason.UNSUPPORTED);
      return new OperationResult(freshTotal(binding, key), OperationResult.Reason.OK);
    } catch (RuntimeException fault) {
      binding.owner.quarantine(fault);
      return new OperationResult(0, OperationResult.Reason.HANDLER_FAULT);
    } finally {
      calling = false;
    }
  }

  OperationResult matchingStock(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      Handle handle,
      dev.izzy.factorycore.core.resource.ResourceFilter filter) {
    checkThread();
    var identity =
        new ResourceKey(
            handle.kind(),
            handle.kind() == ResourceKind.ENERGY ? "neoforge:energy" : "factorycore:catalog",
            new byte[0]);
    if (filter.kind() != handle.kind()
        || calling
        || probing
        || !allowed(actor, origin, handle, identity, NetworkPermissions.Permission.VIEW))
      return new OperationResult(0, OperationResult.Reason.DENIED);
    var binding = current(handle);
    if (binding == null) return new OperationResult(0, OperationResult.Reason.OFFLINE);
    var result = matching(binding, filter);
    return current(handle) != binding
            || !allowed(actor, origin, handle, identity, NetworkPermissions.Permission.VIEW)
        ? new OperationResult(0, OperationResult.Reason.OFFLINE)
        : result;
  }

  private OperationResult matching(
      Binding binding, dev.izzy.factorycore.core.resource.ResourceFilter filter) {
    if (calling || probing)
      throw new IllegalStateException("Machine stock cannot reenter provider reads");
    calling = true;
    try {
      if (binding.handle.kind() == ResourceKind.FLUID
          && binding.owner.settings().wholeFluidHandler()
          && ((IFluidHandler) binding.handler).getTanks() > 64)
        return new OperationResult(0, OperationResult.Reason.UNSUPPORTED);
      var settings = binding.owner.settings();
      var codec = new PlatformResourceCodec(binding.owner.serverLevel().registryAccess());
      var tags = new dev.izzy.factorycore.platform.resource.PlatformFilterTags(server);
      long total = 0;
      int cursor = 0;
      for (int i = 0; i < 4 && cursor >= 0; i++) {
        var page =
            dev.izzy.factorycore.platform.resource.ExternalStockPage.read(
                binding.handle.kind(),
                binding.handler,
                codec,
                settings.firstSlot(),
                settings.slotCount(),
                cursor);
        for (var row : page.entries())
          if (filter.matches(row.key(), tags)) total = Math.addExact(total, row.stored());
        cursor = page.next();
      }
      if (cursor >= 0) return new OperationResult(0, OperationResult.Reason.UNSUPPORTED);
      return new OperationResult(total, OperationResult.Reason.OK);
    } catch (RuntimeException failure) {
      binding.owner.quarantine(failure);
      return new OperationResult(0, OperationResult.Reason.HANDLER_FAULT);
    } finally {
      calling = false;
    }
  }

  private long stockRoom(Binding binding, ResourceKey key, long amount) {
    if (!(binding.owner instanceof MachineInterfaceEntity)) return amount;
    var deployment = saved.machinePresets().get(binding.handle.connector().id());
    if (deployment == null
        || deployment.mode() != dev.izzy.factorycore.core.storage.MachinePresets.Mode.STOCK
        || deployment.leaseGeneration() != binding.handle.connector().generation()
        || deployment.configuration() != binding.handle.generation()
        || !deployment.face().name().equals(binding.owner.targetDirection().name())
        || !deployment
            .filter()
            .matches(key, new dev.izzy.factorycore.platform.resource.PlatformFilterTags(server)))
      return amount;
    if (!((MachineInterfaceEntity) binding.owner).presetAuthorized(deployment)) return 0;
    var resident = matching(binding, deployment.filter());
    if (current(binding.handle) != binding
        || !deployment.equals(saved.machinePresets().get(binding.handle.connector().id()))
        || !((MachineInterfaceEntity) binding.owner).presetAuthorized(deployment)
        || !deployment
            .filter()
            .matches(key, new dev.izzy.factorycore.platform.resource.PlatformFilterTags(server)))
      return 0;
    return resident.reason() == OperationResult.Reason.OK
        ? Math.min(
            amount,
            Math.max(0, deployment.target() - Math.min(deployment.target(), resident.amount())))
        : 0;
  }

  private long freshTotal(Binding binding, ResourceKey key) {
    if (key.kind() == ResourceKind.FLUID
        && binding.owner.settings().wholeFluidHandler()
        && ((IFluidHandler) binding.handler).getTanks() > 64) return 0;
    var settings = binding.owner.settings();
    var codec = new PlatformResourceCodec(binding.owner.serverLevel().registryAccess());
    long total = 0;
    int cursor = 0;
    for (int step = 0; step < 4 && cursor >= 0; step++) {
      var page =
          dev.izzy.factorycore.platform.resource.ExternalStockPage.read(
              key.kind(),
              binding.handler,
              codec,
              settings.firstSlot(),
              settings.slotCount(),
              cursor);
      for (var entry : page.entries())
        if (entry.key().equals(key)) total = Math.addExact(total, entry.stored());
      cursor = page.next();
    }
    return cursor >= 0 || bindings.get(binding.handle.connector().id()) != binding ? 0 : total;
  }

  public boolean inProviderCall() {
    checkThread();
    return calling || probing;
  }

  /** Rate admission only; no foreign call or stock observation is implied. */
  public OperationResult limit(Handle handle, boolean insertion, long requested) {
    checkThread();
    OperationResult.requireQuantity(requested);
    var binding = current(handle);
    if (binding == null) return new OperationResult(0, OperationResult.Reason.OFFLINE);
    if (processingLeased(binding)) return new OperationResult(0, OperationResult.Reason.BUSY);
    int tick = server.getTickCount();
    if (tick != rateTick) {
      rateTick = tick;
      rateBudgets.clear();
      connectorBudgets.clear();
    }
    var budget = rateBudgets.get(binding.group);
    var identity = new RateOwner(binding.handle.connector().id(), binding.handle.kind());
    if (requested > 0
        && (budget == null && rateBudgets.size() >= NodeRegistry.MAX_NODES
            || !connectorBudgets.containsKey(identity)
                && connectorBudgets.size() >= 3 * NodeRegistry.MAX_NODES))
      return new OperationResult(0, OperationResult.Reason.TECHNICAL_LIMIT);
    var local = connectorBudgets.get(identity);
    int direction = insertion ? 0 : 1;
    long amount =
        Math.min(
            requested,
            rateRoom(
                binding,
                budget == null ? 0 : budget[direction],
                local == null ? 0 : local[direction]));
    return new OperationResult(
        amount,
        amount == requested ? OperationResult.Reason.OK : OperationResult.Reason.RATE_LIMIT);
  }

  /**
   * Metadata-only reciprocal processing admission: foreign holds or accepted staging retain their
   * provider ownership even after every interface unregisters. Never queries a capability or stock.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  public OperationResult.Reason processingAdmission(NodeRegistry.Position target) {
    checkThread();
    var dimension =
        net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse(target.dimension()));
    var level = server.getLevel(dimension);
    if (level == null) return OperationResult.Reason.RESERVED;
    var position = BlockPos.of(target.block());
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null) return OperationResult.Reason.RESERVED;
    var entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
    if (entity == null) return OperationResult.Reason.OK;
    try {
      for (var kind : ResourceKind.values()) {
        var providers = providerPositions(level, position, kind);
        if (providers == null) return OperationResult.Reason.RESERVED;
        var processing = dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.get(server);
        for (var provider : providers)
          if (processing.machineLeased(provider.position())) return OperationResult.Reason.BUSY;
        var ids = new java.util.ArrayList<UUID>();
        for (var provider : providers) {
          var block = BlockPos.of(provider.position().block());
          var partChunk = level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
          var part =
              partChunk == null
                  ? null
                  : partChunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK);
          if (part == null) return OperationResult.Reason.RESERVED;
          var id =
              ExternalProviderIdentity.read(
                  part.getPersistentData().get(ExternalProviderIdentity.FIELD),
                  saved.registry().world());
          if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) continue;
        if (ids.size() != providers.size()) return OperationResult.Reason.RESERVED;
        var group = ExternalProviderIdentity.group(saved.registry().world(), kind, ids);
        if (ownedBacking(group)) return OperationResult.Reason.RESERVED;
        if (entity
            instanceof dev.izzy.factorycore.platform.resource.ExternalBackingProvider described) {
          var ports = described.factoryCorePorts(kind);
          if (ports == null || ports.size() > 64) return OperationResult.Reason.RESERVED;
          for (var port : ports) {
            if (port == null) return OperationResult.Reason.RESERVED;
            var backing = indexedBacking(group, port.first(), port.count());
            if (ownedBacking(backing)) return OperationResult.Reason.RESERVED;
          }
        }
      }
      return OperationResult.Reason.OK;
    } catch (RuntimeException invalidMetadata) {
      return OperationResult.Reason.RESERVED;
    }
  }

  /**
   * Capture/read processing provider fingerprints using only loaded persistent component metadata.
   * Initialization is allowed only before new dispatch; resume/cleanup must pass false. Empty means
   * unavailable/unprovable identity. No capability getter or resource method is invoked.
   */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  public Map<ResourceKind, UUID> processingIdentity(
      NodeRegistry.Position target, boolean initialize) {
    checkThread();
    var dimension =
        net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse(target.dimension()));
    var level = server.getLevel(dimension);
    if (level == null) return Map.of();
    var position = BlockPos.of(target.block());
    var targetChunk =
        level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    var targetEntity =
        targetChunk == null
            ? null
            : targetChunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
    if (!(targetEntity instanceof dev.izzy.factorycore.platform.resource.ExternalBackingProvider)
        && !(targetEntity instanceof net.minecraft.world.Container
            && targetEntity
                .getClass()
                .getPackageName()
                .equals("net.minecraft.world.level.block.entity"))) return Map.of();
    var identities = new IdentityHashMap<BlockEntity, UUID>();
    var missing = new java.util.ArrayList<BlockEntity>();
    var result = new java.util.EnumMap<ResourceKind, UUID>(ResourceKind.class);
    try {
      for (var kind : ResourceKind.values()) {
        var members = providerPositions(level, position, kind);
        if (members == null) return Map.of();
        var components = new java.util.ArrayList<UUID>();
        var types = new StringBuilder();
        for (var member : members) {
          var block = BlockPos.of(member.position().block());
          var chunk = level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
          var entity =
              chunk == null
                  ? null
                  : chunk.getBlockEntity(block, LevelChunk.EntityCreationType.CHECK);
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
              .append(
                  net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                      entity.getType()));
        }
        var group = ExternalProviderIdentity.group(saved.registry().world(), kind, components);
        result.put(
            kind,
            UUID.nameUUIDFromBytes(
                ("factorycore:processing-provider:v1:" + group + types)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

  private boolean ownedBacking(UUID backing) {
    return saved.externalClaims().hasBacking(backing)
        || saved.transfers().unresolved(backing) != null;
  }

  private static UUID indexedBacking(UUID group, int first, int count) {
    return UUID.nameUUIDFromBytes(
        ("factorycore:indexed-port:v1:" + group + ":" + first + ":" + count)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private boolean processingLeased(Binding binding) {
    var jobs = dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.get(server);
    for (var provider : binding.providers) if (jobs.machineLeased(provider.position())) return true;
    return false;
  }

  private long rateRoom(Binding binding, long groupUsed, long localUsed) {
    var settings = binding.owner.settings();
    int groupRate =
        binding.indexed ? settings.kind() == ResourceKind.ITEM ? 128 : 1000 : settings.rate();
    return Math.max(0, Math.min(groupRate - groupUsed, settings.rate() - localUsed));
  }

  private long change(
      Binding binding, ResourceKey key, long requested, boolean insertion, boolean simulate) {
    return change(binding, key, requested, insertion, simulate, null, false);
  }

  private long change(
      Binding binding,
      ResourceKey key,
      long requested,
      boolean insertion,
      boolean simulate,
      UUID claimOwner) {
    return change(binding, key, requested, insertion, simulate, claimOwner, false);
  }

  private long change(
      Binding binding,
      ResourceKey key,
      long requested,
      boolean insertion,
      boolean simulate,
      UUID claimOwner,
      boolean ownedReturn) {
    checkThread();
    if (calling)
      throw new IllegalStateException("Foreign ports cannot reenter mutation or simulation");
    OperationResult.requireQuantity(requested);
    if (requested == 0 || key.kind() != binding.handle.kind() || current(binding.handle) != binding)
      return 0;
    if (processingLeased(binding)) return 0;
    var settings = binding.owner.settings();
    if (insertion && settings.access() == ConnectorSettings.Access.READ_ONLY
        || !insertion && settings.access() == ConnectorSettings.Access.WRITE_ONLY) return 0;
    int tick = server.getTickCount();
    if (tick != rateTick) {
      rateTick = tick;
      rateBudgets.clear();
      connectorBudgets.clear();
    }
    var budgetKey = binding.group;
    if (!rateBudgets.containsKey(budgetKey) && rateBudgets.size() >= NodeRegistry.MAX_NODES)
      return 0;
    var identity = new RateOwner(binding.handle.connector().id(), binding.handle.kind());
    if (!connectorBudgets.containsKey(identity)
        && connectorBudgets.size() >= 3 * NodeRegistry.MAX_NODES) return 0;
    var local = connectorBudgets.computeIfAbsent(identity, unused -> new long[2]);
    var budget = rateBudgets.computeIfAbsent(budgetKey, unused -> new long[2]);
    int direction = insertion ? 0 : 1;
    long amount = Math.min(requested, rateRoom(binding, budget[direction], local[direction]));
    if (insertion && !ownedReturn) amount = stockRoom(binding, key, amount);
    if (amount == 0) return 0;
    final long offered = amount;
    LongSupplier call =
        () ->
            insertion
                ? simulate
                    ? binding.delegate.simulateInsert(key, offered)
                    : binding.delegate.insert(key, offered)
                : claimOwner != null
                        || saved.externalClaims().reserved(binding.handle.backing(), key) > 0
                    ? new dev.izzy.factorycore.core.resource.ExternalClaimPort(
                            binding.delegate,
                            saved.externalClaims(),
                            claimOwner,
                            resource -> freshTotal(binding, resource))
                        .extract(key, offered)
                    : binding.delegate.extract(key, offered);
    if (!simulate) {
      budget[direction] += amount;
      local[direction] += amount;
    }
    calling = true;
    try {
      long actual = call.getAsLong();
      if (actual < 0 || actual > amount)
        throw new IllegalStateException("Invalid connector handler quantity");
      if (!simulate) {
        budget[direction] -= amount - actual;
        local[direction] -= amount - actual;
      }
      return actual;
    } catch (RuntimeException fault) {
      binding.owner.quarantine(fault);
      throw fault;
    } finally {
      calling = false;
    }
  }

  /** Validate all saved markers before initializing any missing component. */
  private UUID persistentBacking(ConnectorEntity owner, List<Provider> selected) {
    var components = new java.util.ArrayList<BlockEntity>(selected.size());
    var ids = new java.util.ArrayList<UUID>(selected.size());
    var missing = new java.util.ArrayList<Boolean>(selected.size());
    UUID world = saved.registry().world();
    for (var provider : selected) {
      var position = BlockPos.of(provider.position().block());
      var chunk =
          owner
              .serverLevel()
              .getChunkSource()
              .getChunkNow(position.getX() >> 4, position.getZ() >> 4);
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

  private boolean vanillaBacking(ConnectorEntity owner, Object handler, List<Provider> selected) {
    boolean sided =
        handler.getClass() == net.neoforged.neoforge.items.wrapper.SidedInvWrapper.class;
    if (!sided && handler.getClass() != net.neoforged.neoforge.items.wrapper.InvWrapper.class)
      return false;
    for (var provider : selected) {
      var position = BlockPos.of(provider.position().block());
      var chunk =
          owner
              .serverLevel()
              .getChunkSource()
              .getChunkNow(position.getX() >> 4, position.getZ() >> 4);
      if (chunk == null) return false;
      var entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
      if (!(sided ? sidedView(handler, entity) : invView(handler, entity))) return false;
    }
    return true;
  }

  private static boolean invView(Object handler, Object entity) {
    var container = ((net.neoforged.neoforge.items.wrapper.InvWrapper) handler).getInv();
    return container == entity
        || container instanceof net.minecraft.world.CompoundContainer combined
            && entity instanceof net.minecraft.world.Container part
            && combined.contains(part);
  }

  /** SidedInvWrapper exposes no container getter; its equality compares container and face. */
  private static boolean sidedView(Object handler, Object entity) {
    if (!(entity instanceof net.minecraft.world.WorldlyContainer worldly)) return false;
    for (var side : net.minecraft.core.Direction.values())
      if (handler.equals(new net.neoforged.neoforge.items.wrapper.SidedInvWrapper(worldly, side)))
        return true;
    return false;
  }

  private BlockEntity providerEntity(ConnectorEntity owner) {
    var position = owner.getBlockPos().relative(owner.targetDirection());
    var chunk =
        owner
            .serverLevel()
            .getChunkSource()
            .getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    return chunk == null
        ? null
        : chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
  }

  /** Normalize vanilla double chests and check both chunks before any capability lookup. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed level.
  private List<Provider> providerPositions(ConnectorEntity owner) {
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
  private List<Provider> providerPositions(
      ServerLevel level, BlockPos position, ResourceKind kind) {
    var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
    if (chunk == null) return null;
    BlockEntity entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK);
    if (entity instanceof EnergyBankEntity
        || entity instanceof BufferedInterfaceEntity
        || entity instanceof StorageDriveEntity
        || entity instanceof ConnectorEntity)
      throw new IllegalArgumentException(
          "Digital storage uses canonical handles, not foreign registration");
    if (entity
        instanceof dev.izzy.factorycore.platform.resource.ExternalBackingProvider described) {
      List<BlockPos> blocks = described.factoryCoreBackingPositions(kind);
      if (blocks == null)
        throw new IllegalArgumentException("Invalid provider backing description");
      int count = blocks.size();
      if (count < 1 || count > 64)
        throw new IllegalArgumentException("Invalid provider backing description");
      var copied = new java.util.ArrayList<BlockPos>(count);
      for (int index = 0; index < count; index++) {
        var block = blocks.get(index);
        if (block == null || !BlockPos.of(block.asLong()).equals(block))
          throw new IllegalArgumentException("Invalid backing block position");
        copied.add(block.immutable());
      }
      if (!copied.contains(position) || new java.util.HashSet<>(copied).size() != copied.size())
        throw new IllegalArgumentException("Invalid provider backing description");
      for (var block : copied) {
        var backingChunk = level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
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
          .sorted(java.util.Comparator.comparingLong(provider -> provider.position().block()))
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
    var otherChunk =
        level.getChunkSource().getChunkNow(otherPosition.getX() >> 4, otherPosition.getZ() >> 4);
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

  private void checkThread() {
    if (!server.isSameThread()) throw new IllegalStateException("Connectors require server thread");
  }
}
