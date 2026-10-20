package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.session.MenuSession;
import java.security.MessageDigest;
import java.util.*;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One physical access session. Exact selections and accepted operation ownership stay server-side.
 */
public final class TerminalMenu extends AbstractContainerMenu {
  private final MenuSession protocol;
  private final Player viewer;
  private final TerminalAccess terminal;
  private final BlockPos position;
  private UUID query, route;
  private ResourceKey after, next;
  private List<CatalogCounts.Row> selections = List.of();
  private List<String> selectionNames = List.of();
  private String text = "", status = "loading";
  private ResourceKind kind = ResourceKind.ITEM;
  private int sort = TerminalSearchSupport.NAME;
  private long revision = 1;
  private int scanned, offset, totalRows, pageSize = 8;
  private String used = "0", capacity = "0";
  private boolean infinite, external;

  /**
   * A completed observation is re-read after this many ticks so changes made by other sessions,
   * automation or grant edits reach an idle viewer; the old rows stay visible meanwhile.
   */
  static final int RESCAN_TICKS = 20;

  private boolean loaded, busy, reachRegistered;
  private TerminalPackets.View view;
  private final TerminalCrafting crafting = new TerminalCrafting();
  private TerminalCraftingPackets.View gridView;
  private String gridStatus = "empty";
  private int pendingCrafts, gatherAttempts, crafted, editWindow = Integer.MIN_VALUE, edits;
  static final int MAX_GATHERS = 18, MAX_EDITS_PER_SECOND = 32;
  private TerminalJobPackets.View jobView;
  private TerminalJobs jobs;

  public TerminalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), MenuSession.read(buffer));
  }

  TerminalMenu(int id, Inventory inventory, TerminalAccess terminal, UUID session) {
    this(id, inventory, terminal, terminal.getBlockPos(), session);
  }

  private TerminalMenu(
      int id, Inventory inventory, TerminalAccess terminal, BlockPos position, UUID session) {
    super(TerminalContent.MENU.get(), id);
    this.viewer = inventory.player;
    this.terminal = terminal;
    this.position = position;
    protocol = new MenuSession(this, inventory.player, session, terminal != null, 5);
    if (terminal != null) {
      var preferences =
          viewer
              .getPersistentData()
              .getCompound(Player.PERSISTED_NBT_TAG)
              .getCompound("FactoryCoreTerminal");
      if (preferences.getInt("Schema") == 1) {
        String stored = preferences.getString("Search");
        if (stored.length() <= 128) text = stored;
        int storedSort = preferences.getInt("Sort");
        if (storedSort >= TerminalSearchSupport.NAME
            && storedSort <= TerminalSearchSupport.QUANTITY) sort = storedSort;
      }
    }
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(new Slot(inventory, 9 + row * 9 + column, 59 + 18 * column, 148 + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new Slot(inventory, column, 59 + 18 * column, 210));
    view =
        new TerminalPackets.View(
            id, protocol.id(), revision, status, List.of(), false, false, "0", text, sort);
    jobView =
        new TerminalJobPackets.View(
            id,
            protocol.id(),
            0,
            List.of(),
            false,
            TerminalJobPackets.NONE,
            "",
            0,
            0,
            List.of(),
            List.of(),
            "ready");
    if (terminal != null)
      jobs = new TerminalJobs((ServerPlayer) viewer, terminal, id, protocol.id(), jobView);
    gridView =
        new TerminalCraftingPackets.View(
            id,
            protocol.id(),
            0,
            Collections.nCopies(TerminalCraftingPackets.CELLS, ""),
            Collections.nCopies(TerminalCraftingPackets.CELLS, 1),
            false,
            "",
            "",
            0,
            gridStatus,
            0);
  }

  /** The client's received Jobs view, or on the server the view most recently sent to it. */
  public TerminalJobPackets.View jobView() {
    return jobs != null ? jobs.view() : jobView;
  }

  public void accept(TerminalJobPackets.View packet) {
    if (terminal == null
        && protocol.owns(packet.menu(), packet.session())
        && packet.revision() >= jobView.revision()) jobView = packet;
  }

  public TerminalCraftingPackets.View gridView() {
    return gridView;
  }

  public void accept(TerminalCraftingPackets.View packet) {
    if (terminal == null
        && protocol.owns(packet.menu(), packet.session())
        && packet.gridRevision() >= gridView.gridRevision()) gridView = packet;
  }

  /** Opens a terminal session through {@code access} if it currently allows the player. */
  public static boolean open(ServerPlayer player, TerminalAccess access, Component title) {
    if (!access.canUse(player)) return false;
    var session = UUID.randomUUID();
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, who) -> new TerminalMenu(id, inventory, access, session), title),
        buffer -> {
          buffer.writeBlockPos(access.getBlockPos());
          buffer.writeUUID(session);
        });
    return player.containerMenu instanceof TerminalMenu;
  }

  public UUID session() {
    return protocol.id();
  }

  public BlockPos position() {
    return position;
  }

  public TerminalPackets.View view() {
    return view;
  }

  public void accept(TerminalPackets.View packet) {
    if (terminal == null
        && protocol.owns(packet.menu(), packet.session())
        && packet.revision() >= view.revision()) view = packet;
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private NetworkPermissions.Actor actor() {
    return Actors.of(viewer);
  }

  private WorldStorageTransfers transfers() {
    return WorldNetworks.get(player().server).transfers();
  }

  private DeviceSavedData saved() {
    return DeviceSavedData.get(player().server);
  }

  private boolean authorized() {
    return protocol.active()
        && terminal != null
        && player().containerMenu == this
        && terminal.canUse(player());
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (terminal == null || terminal.canUse(player()));
  }

  /**
   * Client-only coordinates; slot identities/order remain the server's native 36-slot inventory.
   */
  public void layoutInventory(int extra) {
    if (terminal != null)
      throw new IllegalStateException("Only the client lays out inventory slots");
    for (int i = 0; i < slots.size(); i++) {
      var old = slots.get(i);
      int y = i < 27 ? 148 + 18 * (i / 9) : 210;
      var replacement =
          new Slot(old.container, old.getContainerSlot(), 59 + 18 * (i % 9), y + extra);
      replacement.index = i;
      slots.set(i, replacement);
    }
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    // Native initMenu broadcasts before assigning containerMenu; never close another menu.
    if (!protocol.server() || !protocol.active() || player().containerMenu != this) return;
    if (!authorized()) {
      player().closeContainer();
      return;
    }
    if (!terminal.upkeep(player())) {
      player()
          .displayClientMessage(
              Component.translatable("message.factorycore.wireless_no_power"), true);
      player().closeContainer();
      return;
    }
    if (!reachRegistered) {
      reachRegistered = true;
      transfers().playerReach(player(), terminal.origin(), terminal.reachSquared());
    }
    if (!protocol.admitPoll()) return;
    int tick = player().server.getTickCount();
    if (!loaded) {
      loaded = true;
      refresh();
      jobs.refreshCraftables();
    }
    if (query != null && !busy) {
      var result = transfers().search().window(query, actor(), offset, pageSize);
      if (result.reason() == OperationResult.Reason.DENIED) {
        // A captured scope/grant or the exclusive sorted-page anchor went stale.
        after = null;
        selections = List.of();
        selectionNames = List.of();
        next = null;
        totalRows = 0;
        offset = 0;
        used = "0";
        capacity = "0";
        infinite = false;
        external = false;
        revision++;
        rescan(tick);
        result =
            query == null ? null : transfers().search().window(query, actor(), offset, pageSize);
      }
      if (result == null) {
        selections = List.of();
        selectionNames = List.of();
        next = null;
        revision++;
      } else if (result.reason() != OperationResult.Reason.OK) {
        selections = List.of();
        selectionNames = List.of();
        next = null;
        status = result.reason().name().toLowerCase(Locale.ROOT);
        closeSearch();
        revision++;
      } else if (result.complete() && !selections.equals(result.rows())
          || result.complete()
              && (offset != result.offset()
                  || totalRows != result.total()
                  || !used.equals(result.used().toString())
                  || !capacity.equals(result.capacity().toString())
                  || infinite != result.infinite()
                  || external != result.external())) {
        selections = result.rows();
        selectionNames = result.names();
        offset = result.offset();
        totalRows = result.total();
        used = result.used().toString();
        capacity = result.capacity().toString();
        infinite = result.infinite();
        external = result.external();
        next =
            offset + result.rows().size() < result.total() && !result.rows().isEmpty()
                ? result.rows().getLast().key()
                : null;
        if (status.equals("loading")) status = "ready";
        revision++;
      } else if (result.complete() && status.equals("loading")) {
        offset = result.offset();
        totalRows = result.total();
        used = result.used().toString();
        capacity = result.capacity().toString();
        infinite = result.infinite();
        external = result.external();
        next =
            offset + result.rows().size() < result.total() && !result.rows().isEmpty()
                ? result.rows().getLast().key()
                : null;
        status = "ready";
        revision++;
      }
      if (result != null && result.complete() && tick - scanned >= RESCAN_TICKS) rescan(tick);
    }
    publish();
  }

  /** Restarts the observation of the same kind/text/page without clearing the visible rows. */
  private void rescan(int tick) {
    closeSearch();
    scanned = tick;
    query = UUID.randomUUID();
    var submission =
        transfers().search().start(query, actor(), terminal.origin(), kind, text, sort);
    if (submission.rejection() != OperationResult.Reason.OK) {
      query = null;
      selections = List.of();
      selectionNames = List.of();
      next = null;
      revision++;
      status = submission.rejection().name().toLowerCase(Locale.ROOT);
    }
  }

  private void savePreferences() {
    var persistent = viewer.getPersistentData();
    var kept = persistent.getCompound(Player.PERSISTED_NBT_TAG);
    var preferences = new CompoundTag();
    preferences.putInt("Schema", 1);
    preferences.putString("Search", text);
    preferences.putInt("Sort", sort);
    kept.put("FactoryCoreTerminal", preferences);
    persistent.put(Player.PERSISTED_NBT_TAG, kept);
  }

  private void closeSearch() {
    if (query != null) {
      transfers().search().close(query, actor());
      query = null;
    }
  }

  private void refresh() {
    closeSearch();
    after = null;
    next = null;
    selections = List.of();
    selectionNames = List.of();
    revision++;
    status = "loading";
    scanned = player().server.getTickCount();
    query = UUID.randomUUID();
    var submission =
        transfers().search().start(query, actor(), terminal.origin(), kind, text, sort);
    if (submission.rejection() != OperationResult.Reason.OK) {
      query = null;
      status = submission.rejection().name().toLowerCase(Locale.ROOT);
    }
  }

  public void command(ServerPlayer sender, TerminalPackets.Command command) {
    if (!protocol.fromViewer(sender, command.menu(), command.session())
        || !authorized()
        || busy
        || !protocol.admitRate()) return;
    int action = command.action();
    boolean mutating =
        action == TerminalPackets.WITHDRAW
            || action == TerminalPackets.DEPOSIT
            || action == TerminalPackets.RETURN
            || action == TerminalPackets.TAKE_CURSOR
            || action == TerminalPackets.HALF_CURSOR
            || action == TerminalPackets.DEPOSIT_CURSOR
            || (action >= TerminalPackets.FILL && action <= TerminalPackets.DISCHARGE);
    if (mutating && !terminal.spendAction(sender)) {
      status = "no_power";
      revision++;
      publish();
      return;
    }
    if (command.action() == TerminalPackets.SEARCH) {
      offset = 0;
      text = command.text();
      savePreferences();
      refresh();
      publish();
      return;
    }
    if (command.action() == TerminalPackets.SORT) {
      if (command.selection() > TerminalSearchSupport.QUANTITY) return;
      offset = 0;
      sort = command.selection();
      savePreferences();
      refresh();
      publish();
      return;
    }
    if (command.action() == TerminalPackets.WINDOW) {
      offset = Math.min(command.selection(), Math.max(0, totalRows - 1));
      pageSize = command.amount();
      selections = List.of();
      selectionNames = List.of();
      status = "loading";
      revision++;
      protocol.pollNow();
      broadcastChanges();
      return;
    }
    if (command.action() == TerminalPackets.HOME) {
      offset = 0;
      after = null;
      selections = List.of();
      selectionNames = List.of();
      status = "loading";
      revision++;
      protocol.pollNow();
      broadcastChanges();
      return;
    }
    if (command.action() == TerminalPackets.NEXT) {
      if (next != null) {
        offset += selections.size();
        after = next;
        selections = List.of();
        selectionNames = List.of();
        next = null;
        status = "loading";
        revision++;
        protocol.pollNow();
        broadcastChanges();
      }
      return;
    }
    if (command.action() == TerminalPackets.KIND) {
      if (command.selection() > 2) return;
      offset = 0;
      kind = ResourceKind.values()[command.selection()];
      refresh();
      publish();
      return;
    }
    if (command.action() >= TerminalPackets.FILL && command.action() <= TerminalPackets.DISCHARGE) {
      var containers = new TerminalContainers(sender, terminal.origin());
      String outcome;
      if (command.action() == TerminalPackets.FILL) {
        if (command.revision() != revision
            || kind != ResourceKind.FLUID
            || command.selection() >= selections.size()) {
          publish();
          return;
        }
        outcome = containers.fill(selections.get(command.selection()).key());
      } else if (command.action() == TerminalPackets.EMPTY)
        outcome = containers.empty(command.selection());
      else
        outcome =
            containers.energy(command.selection(), command.action() == TerminalPackets.CHARGE);
      status = outcome;
      revision++;
      refresh();
      status = outcome;
      publish();
      return;
    }
    if (command.revision() != revision) {
      publish();
      return;
    }
    if (command.action() == TerminalPackets.RETURN) {
      UUID operation = unresolved();
      if (operation == null) return;
      busy = true;
      var admission =
          transfers().progressGuarded(operation, actor(), true, this::finish, this::authorized);
      if (admission.rejection() != OperationResult.Reason.OK) {
        busy = false;
        status = admission.rejection().name().toLowerCase(Locale.ROOT);
      }
      publish();
      return;
    }
    ResourceKey key;
    WorldStorageTransfers.PlayerInventory endpoint;
    WorldStorageRoutes.Direction direction;
    long amount = command.amount();
    if (kind != ResourceKind.ITEM) return;
    if (command.action() == TerminalPackets.WITHDRAW
        || command.action() == TerminalPackets.TAKE_CURSOR
        || command.action() == TerminalPackets.HALF_CURSOR) {
      if (command.selection() >= selections.size()) return;
      // A ready observation is advisory; revalidate its captured scope/grants before selecting it.
      if (query == null
          || transfers().search().window(query, actor(), offset, pageSize).reason()
              != OperationResult.Reason.OK) {
        refresh();
        publish();
        return;
      }
      key = selections.get(command.selection()).key();
      var selectedStack = new PlatformResourceCodec(sender.registryAccess()).itemStack(key, 1);
      long stackAmount = Math.min(64, selectedStack.getMaxStackSize());
      if (command.action() != TerminalPackets.WITHDRAW) {
        if (!getCarried().isEmpty()) return;
        amount =
            Math.min(
                stackAmount,
                selections
                    .get(command.selection())
                    .available()
                    .min(java.math.BigInteger.valueOf(64))
                    .longValue());
        if (command.action() == TerminalPackets.HALF_CURSOR) amount = (amount + 1) / 2;
        amount = Math.max(1, amount);
      } else amount = Math.min(amount, stackAmount);
      endpoint =
          transfers().inventory(sender, command.action() == TerminalPackets.WITHDRAW ? -1 : 36);
      direction = WorldStorageRoutes.Direction.FROM_STORAGE;
    } else if (command.action() == TerminalPackets.DEPOSIT
        || command.action() == TerminalPackets.DEPOSIT_CURSOR) {
      boolean cursor = command.action() == TerminalPackets.DEPOSIT_CURSOR;
      var stack = cursor ? getCarried() : sender.getInventory().getItem(command.selection());
      if (stack.isEmpty()) return;
      try {
        key = new PlatformResourceCodec(sender.registryAccess()).itemKey(stack);
      } catch (IllegalArgumentException unsupported) {
        status = "unsupported";
        publish();
        return;
      }
      if (!MessageDigest.isEqual(TerminalPackets.fingerprint(key), command.fingerprint())) return;
      amount = Math.min(amount, stack.getCount());
      endpoint = transfers().inventory(sender, cursor ? 36 : command.selection());
      direction = WorldStorageRoutes.Direction.TO_STORAGE;
    } else return;
    if (unresolved() != null) {
      status = "reserved";
      publish();
      return;
    }
    busy = true;
    route = UUID.randomUUID();
    long selectedRevision = revision;
    var admission =
        transfers()
            .routes()
            .priorityGuarded(
                route,
                actor(),
                terminal.origin(),
                endpoint,
                key,
                amount,
                direction,
                result -> finish(result.move()),
                () -> authorized() && revision == selectedRevision,
                () -> unresolved() == null);
    if (admission.rejection() != OperationResult.Reason.OK) {
      busy = false;
      route = null;
      status = admission.rejection().name().toLowerCase(Locale.ROOT);
    }
    publish();
  }

  /** Ghost edits and bounded crafting; ingredients come only from the player's own inventory. */
  /** Applies a recipe-viewer transfer as crafting-mode ghost cells; unknown IDs clear a cell. */
  public void gridFill(ServerPlayer sender, TerminalCraftingPackets.Fill fill) {
    if (!protocol.fromViewer(sender, fill.menu(), fill.session()) || !authorized() || busy) return;
    int tick = sender.server.getTickCount();
    if (editWindow == Integer.MIN_VALUE || tick - editWindow >= 20) {
      editWindow = tick;
      edits = 0;
    }
    if (edits++ >= MAX_EDITS_PER_SECOND) return;
    var codec = new PlatformResourceCodec(sender.registryAccess());
    crafting.clear();
    if (crafting.processing()) crafting.processing(false);
    for (int cell = 0; cell < fill.items().size(); cell++) {
      var id = ResourceLocation.tryParse(fill.items().get(cell));
      var item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
      if (item == Items.AIR) continue;
      crafting.set(cell, codec.itemKey(new ItemStack(item)));
    }
    gridStatus = "ready";
    publish();
  }

  public void gridCommand(ServerPlayer sender, TerminalCraftingPackets.Command command) {
    if (!protocol.fromViewer(sender, command.menu(), command.session()) || !authorized() || busy)
      return;
    int tick = sender.server.getTickCount();
    if (command.action() == TerminalCraftingPackets.CRAFT
        || command.action() == TerminalCraftingPackets.ENCODE) {
      if (!protocol.admitRate()) return;
    } else {
      // Ghost edits move nothing; a separate bounded budget keeps recipe lookups bounded.
      if (editWindow == Integer.MIN_VALUE || tick - editWindow >= 20) {
        editWindow = tick;
        edits = 0;
      }
      if (edits++ >= MAX_EDITS_PER_SECOND) return;
    }
    var codec = new PlatformResourceCodec(sender.registryAccess());
    switch (command.action()) {
      case TerminalCraftingPackets.SET_OWN -> {
        var stack = sender.getInventory().getItem(command.source());
        if (command.cell() < 0 || stack.isEmpty()) return;
        ResourceKey key;
        try {
          key = codec.itemKey(stack);
        } catch (IllegalArgumentException unsupported) {
          gridStatus = "unsupported";
          publish();
          return;
        }
        if (!MessageDigest.isEqual(TerminalPackets.fingerprint(key), command.fingerprint())) return;
        crafting.set(command.cell(), key);
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.SET_ROW -> {
        if (command.cell() < 0
            || command.viewRevision() != revision
            || command.source() >= selections.size()) {
          publish();
          return;
        }
        var key = selections.get(command.source()).key();
        // Fluid rows are accepted only as processing-pattern cells.
        if (key.kind() == ResourceKind.ENERGY
            || key.kind() == ResourceKind.FLUID && !crafting.processing()) return;
        crafting.set(command.cell(), key);
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.CLEAR -> {
        if (command.cell() < 0) crafting.clear();
        else crafting.set(command.cell(), null);
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.MODE -> {
        crafting.processing(command.cell() == 1);
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.COUNT -> {
        if (command.cell() < 0 || !crafting.processing()) return;
        crafting.count(command.cell(), command.crafts());
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.ENCODE -> {
        if (!terminal.spendAction(sender)) {
          gridStatus = "no_power";
          publish();
          return;
        }
        if (command.gridRevision() != crafting.revision()) {
          publish();
          return;
        }
        finishCraft(crafting.encode(sender));
        return;
      }
      case TerminalCraftingPackets.CRAFT -> {
        if (!terminal.spendAction(sender)) {
          gridStatus = "no_power";
          publish();
          return;
        }
        if (command.gridRevision() != crafting.revision()) {
          publish();
          return;
        }
        pendingCrafts = command.crafts();
        gatherAttempts = 0;
        crafted = 0;
        continueCraft();
        return;
      }
      default -> {
        return;
      }
    }
    crafted = 0;
    publish();
  }

  /** Autocrafting: page craftables, preview a row, start the previewed plan, cancel own jobs. */
  public void jobCommand(ServerPlayer sender, TerminalJobPackets.Command command) {
    if (!protocol.fromViewer(sender, command.menu(), command.session())
        || !authorized()
        || !protocol.admitRate()) return;
    if (jobs.command(command)) publish();
  }

  /** Crafts now, or gathers one missing ingredient from authorized storage and retries. */
  private void continueCraft() {
    var outcome = crafting.craft(player(), pendingCrafts);
    if (outcome.deficit() == null || gatherAttempts >= MAX_GATHERS) {
      finishCraft(outcome.deficit() == null ? outcome : partialCraft());
      return;
    }
    gatherAttempts++;
    if (unresolved() != null) {
      finishCraft(new TerminalCrafting.Outcome(0, "reserved", null));
      return;
    }
    busy = true;
    route = UUID.randomUUID();
    long gridRevision = crafting.revision();
    var admission =
        transfers()
            .routes()
            .priorityGuarded(
                route,
                actor(),
                terminal.origin(),
                transfers().inventory(player(), -1),
                outcome.deficit().key(),
                Math.min(64, outcome.deficit().missing()),
                WorldStorageRoutes.Direction.FROM_STORAGE,
                result -> finish(result.move()),
                () -> authorized() && crafting.revision() == gridRevision,
                () -> unresolved() == null);
    if (admission.rejection() != OperationResult.Reason.OK) {
      busy = false;
      route = null;
      finishCraft(partialCraft());
      return;
    }
    gridStatus = "gathering";
    publish();
  }

  /** After gathering stops, crafts as many whole recipes as owned ingredients cover. */
  private TerminalCrafting.Outcome partialCraft() {
    var requirement = crafting.requirement();
    var owned = crafting.owned(player());
    long possible = pendingCrafts;
    for (var entry : requirement.perCraft().entrySet())
      possible = Math.min(possible, owned.getOrDefault(entry.getKey(), 0L) / entry.getValue());
    if (possible < 1) return new TerminalCrafting.Outcome(0, "shortage", null);
    var outcome = crafting.craft(player(), (int) possible);
    return outcome.crafted() > 0 && outcome.status().equals("crafted")
        ? new TerminalCrafting.Outcome(outcome.crafted(), "partial", null)
        : outcome;
  }

  private void finishCraft(TerminalCrafting.Outcome outcome) {
    pendingCrafts = 0;
    crafted = outcome.crafted();
    gridStatus = outcome.status();
    publish();
  }

  private UUID unresolved() {
    var stored =
        saved()
            .transfers()
            .unresolved(
                TransferContext.playerBacking(saved().registry().world(), viewer.getUUID()));
    return stored == null ? null : stored.id();
  }

  private void finish(WorldStorageTransfers.Result result) {
    busy = false;
    route = null;
    if (result.transfer() != null
        && result.transfer().state() == StagedTransfer.State.COMPLETE
        && result.transfer().pending() == StagedTransfer.PendingCall.NONE
        && result.transfer().staged() == 0) saved().transfers().retire(result.id());
    if (authorized()) {
      refresh();
      status = result.reason().name().toLowerCase(Locale.ROOT);
      if (pendingCrafts > 0) {
        if (result.transfer() != null && result.transfer().delivered() > 0) continueCraft();
        else finishCraft(partialCraft());
      }
      publish();
    } else pendingCrafts = 0;
  }

  private void publish() {
    var rows = new ArrayList<TerminalPackets.Row>();
    for (int index = 0; index < selections.size(); index++) {
      var row = selections.get(index);
      String name = selectionNames.get(index).replaceAll("[\\p{Cntrl}§]", "");
      if (name.length() > 96) name = name.substring(0, 96);
      rows.add(
          new TerminalPackets.Row(
              index,
              row.key().registryId(),
              name,
              row.total().toString(),
              row.reserved().toString(),
              row.available().toString()));
    }
    UUID operation = unresolved();
    String staged =
        operation == null ? "0" : Long.toString(saved().transfers().get(operation).staged());
    if (operation != null && !busy) {
      if (saved().transfers().get(operation).state() == StagedTransfer.State.QUARANTINED)
        status = "uncertain";
      else if (status.equals("ready")) status = "reserved";
    }
    var packet =
        new TerminalPackets.View(
            containerId,
            protocol.id(),
            revision,
            status,
            rows,
            next != null,
            busy,
            staged,
            text,
            sort,
            offset,
            totalRows,
            pageSize,
            kind.ordinal(),
            used,
            capacity,
            infinite,
            external);
    if (!packet.equals(view)) {
      view = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
    var cells = new ArrayList<String>(TerminalCraftingPackets.CELLS);
    var counts = new ArrayList<Integer>(TerminalCraftingPackets.CELLS);
    for (int index = 0; index < TerminalCraftingPackets.CELLS; index++) {
      var key = crafting.cell(index);
      cells.add(key == null ? "" : key.registryId());
      counts.add(crafting.count(index));
    }
    var result = crafting.preview(player());
    String resultName =
        result.isEmpty() ? "" : result.getHoverName().getString().replaceAll("[\\p{Cntrl}§]", "");
    if (resultName.length() > 96) resultName = resultName.substring(0, 96);
    var grid =
        new TerminalCraftingPackets.View(
            containerId,
            protocol.id(),
            crafting.revision(),
            cells,
            counts,
            crafting.processing(),
            result.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
            resultName,
            Math.min(64, result.getCount()),
            gridStatus,
            crafted);
    if (!grid.equals(gridView)) {
      gridView = grid;
      PacketDistributor.sendToPlayer(player(), grid);
    }
    jobs.publish();
  }

  @Override
  public void removed(Player player) {
    if (terminal != null && protocol.active()) {
      protocol.close();
      if (reachRegistered) transfers().playerReach(player(), terminal.origin(), 64);
      pendingCrafts = 0;
      closeSearch();
      selections = List.of();
      selectionNames = List.of();
      if (route != null && !transfers().inExecution()) transfers().routes().cancel(route, actor());
    }
    super.removed(player);
  }
}
