package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.TransferContext;
import dev.izzy.factorycore.platform.ServerThread;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authenticated inventory bindings; they are neither stock rows nor canonical quantity
 * copies.
 */
final class WorldPlayerInventories {
  static final int MAX_PLAYERS = 256;
  static final int SLOTS = 36;
  static final long RATE = 128;

  private record Binding(ServerPlayer player, long nonce, UUID backing) {}

  private static final class Budget {
    long inserted;
    long extracted;
  }

  private final MinecraftServer server;
  private final DeviceSavedData saved;
  private final PlatformResourceCodec codec;
  private final Map<UUID, Binding> bindings = new HashMap<>();
  private final Map<UUID, Budget> budgets = new HashMap<>();

  private record Reach(NodeRegistry.Position origin, double squared) {}

  /** Wireless sessions extend a player's reach to one origin; wired reach is 8 blocks. */
  private final Map<UUID, Reach> reaches = new HashMap<>();

  private int budgetTick = -1;

  WorldPlayerInventories(MinecraftServer server) {
    this.server = server;
    saved = DeviceSavedData.get(server);
    codec = new PlatformResourceCodec(server.registryAccess());
  }

  WorldStorageTransfers.PlayerInventory endpoint(ServerPlayer player, int slot) {
    checkThread();
    if (slot < -1 || slot > SLOTS)
      throw new IllegalArgumentException("Invalid player inventory slot");
    if (server.getPlayerList().getPlayer(player.getUUID()) != player
        || player.isRemoved()
        || !player.isAlive()
        || player.serverLevel().getServer() != server)
      throw new IllegalStateException("Player is not authenticated online");
    var iterator = bindings.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (server.getPlayerList().getPlayer(entry.getKey()) != entry.getValue().player()) {
        saved.transfers().unbindBacking(entry.getValue().backing());
        iterator.remove();
      }
    }
    var backing = TransferContext.playerBacking(saved.registry().world(), player.getUUID());
    if (saved.registry().containsBacking(backing) || saved.nodes().contains(backing))
      throw new IllegalStateException("Player inventory backing collision");
    var binding = bindings.get(player.getUUID());
    if (binding == null) {
      if (bindings.size() == MAX_PLAYERS)
        throw new IllegalStateException("Player inventory binding limit");
      binding =
          new Binding(player, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), backing);
      bindings.put(player.getUUID(), binding);
    }
    budget(backing);
    return new WorldStorageTransfers.PlayerInventory(
        player.getUUID(),
        backing,
        binding.nonce(),
        slot,
        slot == SLOTS && player.containerMenu instanceof TerminalMenu menu ? menu.session() : null);
  }

  private Binding current(WorldStorageTransfers.PlayerInventory endpoint) {
    var binding = bindings.get(endpoint.principal());
    return binding != null
            && binding.nonce() == endpoint.connection()
            && binding.backing().equals(endpoint.backing())
            && (endpoint.slot() != SLOTS
                || binding.player().containerMenu instanceof TerminalMenu menu
                    && menu.session().equals(endpoint.menuSession())
                    && menu.stillValid(binding.player()))
            && server.getPlayerList().getPlayer(endpoint.principal()) == binding.player()
            && !binding.player().isRemoved()
            && binding.player().isAlive()
            && binding.player().serverLevel().getServer() == server
        ? binding
        : null;
  }

  void reach(ServerPlayer player, NodeRegistry.Position origin, double squared) {
    checkThread();
    if (squared <= 64) reaches.remove(player.getUUID());
    else reaches.put(player.getUUID(), new Reach(origin, squared));
  }

  private double reachSquared(UUID player, NodeRegistry.Position origin) {
    var reach = reaches.get(player);
    return reach != null && reach.origin().equals(origin) ? reach.squared() : 64;
  }

  void disconnected(ServerPlayer player) {
    reaches.remove(player.getUUID());
    checkThread();
    var binding = bindings.get(player.getUUID());
    if (binding != null && binding.player() == player) {
      bindings.remove(player.getUUID());
      saved.transfers().unbindBacking(binding.backing());
    }
  }

  boolean allowed(
      NetworkPermissions.Actor actor,
      NodeRegistry.Position origin,
      WorldStorageTransfers.PlayerInventory endpoint,
      NetworkPermissions.Permission operation) {
    checkThread();
    var binding = current(endpoint);
    if (binding == null || !actor.id().equals(endpoint.principal())) return false;
    var player = binding.player();
    return player.serverLevel().dimension().location().toString().equals(origin.dimension())
        && player.distanceToSqr(Vec3.atCenterOf(BlockPos.of(origin.block())))
            <= reachSquared(player.getUUID(), origin)
        && (operation != NetworkPermissions.Permission.WITHDRAW || endpoint.slot() >= 0);
  }

  WorldStorageTransfers.PlayerInventory recover(TransferContext.Endpoint reference, UUID actor) {
    checkThread();
    if (!reference.device().equals(actor)) return null;
    var player = server.getPlayerList().getPlayer(actor);
    if (player == null) return null;
    try {
      return endpoint(
          player, reference.configuration() >= 37 ? -1 : (int) reference.configuration() - 1);
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return null;
    }
  }

  PlayerPort port(WorldStorageTransfers.PlayerInventory endpoint, NodeRegistry.Position origin) {
    checkThread();
    return new PlayerPort(endpoint, current(endpoint), origin);
  }

  private Budget budget(UUID backing) {
    int tick = server.getTickCount();
    if (budgetTick != tick) {
      budgets.clear();
      budgetTick = tick;
    }
    if (!budgets.containsKey(backing) && budgets.size() == MAX_PLAYERS)
      throw new IllegalStateException("Player inventory rate-owner limit");
    return budgets.computeIfAbsent(backing, ignored -> new Budget());
  }

  final class PlayerPort implements ResourcePort {
    private final WorldStorageTransfers.PlayerInventory endpoint;
    private final Binding captured;
    private final NodeRegistry.Position origin;
    private final net.minecraft.world.inventory.AbstractContainerMenu cursorMenu;

    PlayerPort(
        WorldStorageTransfers.PlayerInventory endpoint,
        Binding captured,
        NodeRegistry.Position origin) {
      this.endpoint = endpoint;
      this.captured = captured;
      this.origin = origin;
      cursorMenu =
          endpoint.slot() == SLOTS && captured != null ? captured.player().containerMenu : null;
    }

    private boolean usable() {
      return captured != null
          && current(endpoint) == captured
          && (endpoint.slot() != SLOTS
              || cursorMenu instanceof TerminalMenu menu
                  && captured.player().containerMenu == cursorMenu
                  && menu.stillValid(captured.player()))
          && captured
              .player()
              .serverLevel()
              .dimension()
              .location()
              .toString()
              .equals(origin.dimension())
          && captured.player().distanceToSqr(Vec3.atCenterOf(BlockPos.of(origin.block())))
              <= reachSquared(captured.player().getUUID(), origin);
    }

    private boolean matches(ItemStack stack, ResourceKey key) {
      if (stack.isEmpty()) return false;
      try {
        return codec.itemKey(stack).equals(key);
      } catch (IllegalArgumentException | UncheckedIOException unsupported) {
        return false;
      }
    }

    @Override
    public UUID backingId() {
      return endpoint.backing();
    }

    OperationResult available(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      if (!usable()) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      if (key.kind() != ResourceKind.ITEM || endpoint.slot() < 0)
        return new OperationResult(0, OperationResult.Reason.WRONG_RESOURCE);
      long rate = RATE - budget(backingId()).extracted;
      if (amount > 0 && rate == 0) return new OperationResult(0, OperationResult.Reason.RATE_LIMIT);
      var stack =
          endpoint.slot() == SLOTS
              ? cursorMenu.getCarried()
              : captured.player().getInventory().getItem(endpoint.slot());
      long available = matches(stack, key) ? Math.min(Math.min(amount, rate), stack.getCount()) : 0;
      return new OperationResult(
          available,
          available == 0 && amount > 0
              ? OperationResult.Reason.SHORTAGE
              : OperationResult.Reason.OK);
    }

    OperationResult admit(ResourceKey key, long amount) {
      OperationResult.requireQuantity(amount);
      if (!usable()) return new OperationResult(0, OperationResult.Reason.OFFLINE);
      if (key.kind() != ResourceKind.ITEM)
        return new OperationResult(0, OperationResult.Reason.WRONG_RESOURCE);
      long rate = RATE - budget(backingId()).inserted;
      if (rate == 0 && amount > 0) return new OperationResult(0, OperationResult.Reason.RATE_LIMIT);
      long accepted = insertion(key, Math.min(amount, rate), true);
      return new OperationResult(
          accepted,
          accepted == amount
              ? OperationResult.Reason.OK
              : accepted == 0 ? OperationResult.Reason.FULL : OperationResult.Reason.OK);
    }

    @Override
    public long simulateInsert(ResourceKey key, long offered) {
      return admit(key, offered).amount();
    }

    private long insertion(ResourceKey key, long offered, boolean simulate) {
      var nativeStack = codec.itemStack(key, 1);
      int limit =
          Math.min(
              64,
              Math.min(
                  nativeStack.getMaxStackSize(),
                  captured.player().getInventory().getMaxStackSize(nativeStack)));
      if (endpoint.slot() == SLOTS) {
        var current = cursorMenu.getCarried();
        if (!current.isEmpty() && !matches(current, key)) return 0;
        int accepted = (int) Math.min(offered, Math.max(0, limit - current.getCount()));
        if (!simulate && accepted > 0)
          cursorMenu.setCarried(codec.itemStack(key, current.getCount() + accepted));
        return accepted;
      }
      var inventory = captured.player().getInventory();
      long accepted = 0;
      int first = endpoint.slot() < 0 ? 0 : endpoint.slot();
      int end = endpoint.slot() < 0 ? SLOTS : endpoint.slot() + 1;
      for (int pass = 0; pass < 2 && accepted < offered; pass++)
        for (int slot = first; slot < end && accepted < offered; slot++) {
          if (!usable()) return accepted;
          var current = inventory.getItem(slot);
          boolean empty = current.isEmpty();
          if (pass == 0 ? empty || !matches(current, key) : !empty) continue;
          int moved =
              (int)
                  Math.min(
                      offered - accepted, Math.max(0, limit - (empty ? 0 : current.getCount())));
          if (moved == 0) continue;
          if (!simulate)
            inventory.setItem(slot, codec.itemStack(key, (empty ? 0 : current.getCount()) + moved));
          accepted += moved;
        }
      if (!simulate && accepted > 0) inventory.setChanged();
      return accepted;
    }

    @Override
    public long insert(ResourceKey key, long offered) {
      OperationResult.requireQuantity(offered);
      if (!usable() || key.kind() != ResourceKind.ITEM) return 0;
      var budget = budget(backingId());
      long accepted = insertion(key, Math.min(offered, RATE - budget.inserted), false);
      budget.inserted += accepted;
      StorageStats.award(captured.player(), StorageStats.ITEMS_TAKEN, accepted);
      return accepted;
    }

    @Override
    public long extract(ResourceKey key, long requested) {
      var available = available(key, requested).amount();
      if (available == 0) return 0;
      ItemStack extracted;
      if (endpoint.slot() == SLOTS) {
        extracted = cursorMenu.getCarried().split((int) available);
      } else
        extracted = captured.player().getInventory().removeItem(endpoint.slot(), (int) available);
      long actual = extracted.getCount();
      if (actual < 0 || actual > available || actual > 0 && !matches(extracted, key))
        throw new IllegalStateException(
            "Player inventory extraction did not match accepted identity/count");
      if (actual > 0) captured.player().getInventory().setChanged();
      budget(backingId()).extracted += actual;
      StorageStats.award(captured.player(), StorageStats.ITEMS_STORED, actual);
      return actual;
    }
  }

  private void checkThread() {
    ServerThread.require(server, "Player inventory");
  }
}
