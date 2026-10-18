package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One physical access session. Exact selections and accepted operation ownership stay server-side.
 */
public final class TerminalMenu extends AbstractContainerMenu {
  private final Player viewer;
  private final TerminalEntity terminal;
  private final UUID session;
  private final BlockPos position;
  private UUID query, route;
  private ResourceKey after, next;
  private List<CatalogCounts.Row> selections = List.of();
  private String text = "", status = "loading";
  private long revision = 1;
  private int lastCommand = Integer.MIN_VALUE, lastPoll = Integer.MIN_VALUE;
  private boolean active = true, loaded, busy;
  private TerminalPackets.View view;
  private final TerminalCrafting crafting = new TerminalCrafting();
  private TerminalCraftingPackets.View gridView;
  private String gridStatus = "empty";
  private int pendingCrafts, gatherAttempts, crafted, editWindow = Integer.MIN_VALUE, edits;
  static final int MAX_GATHERS = 18, MAX_EDITS_PER_SECOND = 32;

  public TerminalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), buffer.readUUID());
  }

  TerminalMenu(int id, Inventory inventory, TerminalEntity terminal, UUID session) {
    this(id, inventory, terminal, terminal.getBlockPos(), session);
  }

  private TerminalMenu(
      int id, Inventory inventory, TerminalEntity terminal, BlockPos position, UUID session) {
    super(TerminalContent.MENU.get(), id);
    this.viewer = inventory.player;
    this.terminal = terminal;
    this.position = position;
    this.session = session;
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(new Slot(inventory, 9 + row * 9 + column, 59 + 18 * column, 148 + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new Slot(inventory, column, 59 + 18 * column, 210));
    view = new TerminalPackets.View(id, session, revision, status, List.of(), false, false, "0");
    gridView =
        new TerminalCraftingPackets.View(
            id, session, 0, java.util.Collections.nCopies(9, ""), "", "", 0, gridStatus, 0);
  }

  public TerminalCraftingPackets.View gridView() {
    return gridView;
  }

  public void accept(TerminalCraftingPackets.View packet) {
    if (terminal == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.gridRevision() >= gridView.gridRevision()) gridView = packet;
  }

  public UUID session() {
    return session;
  }

  public BlockPos position() {
    return position;
  }

  public TerminalPackets.View view() {
    return view;
  }

  public void accept(TerminalPackets.View packet) {
    if (terminal == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.revision() >= view.revision()) view = packet;
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private NetworkPermissions.Actor actor() {
    return new NetworkPermissions.Actor(viewer.getUUID(), player().hasPermissions(2));
  }

  private WorldStorageTransfers transfers() {
    return WorldNetworks.get(player().server).transfers();
  }

  private DeviceSavedData saved() {
    return DeviceSavedData.get(player().server);
  }

  private boolean authorized() {
    return active
        && terminal != null
        && player().containerMenu == this
        && terminal.canUse(player());
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (terminal == null || terminal.canUse(player()));
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    // Native initMenu broadcasts before assigning containerMenu; never close another menu.
    if (terminal == null || !active || player().containerMenu != this) return;
    if (!authorized()) {
      player().closeContainer();
      return;
    }
    int tick = player().server.getTickCount();
    if (lastPoll != Integer.MIN_VALUE && tick - lastPoll < 5) return;
    lastPoll = tick;
    if (!loaded) {
      loaded = true;
      refresh();
    }
    if (query != null && !busy) {
      var result = transfers().search().result(query, actor(), after, 8);
      if (result.reason() != OperationResult.Reason.OK) {
        selections = List.of();
        next = null;
        status = result.reason().name().toLowerCase(Locale.ROOT);
        closeSearch();
        revision++;
      } else if (result.complete() && !selections.equals(result.rows())) {
        selections = result.rows();
        next = result.next();
        if (status.equals("loading")) status = "ready";
        revision++;
      } else if (result.complete() && status.equals("loading")) {
        next = result.next();
        status = "ready";
        revision++;
      }
    }
    publish();
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
    revision++;
    status = "loading";
    query = UUID.randomUUID();
    var submission =
        transfers().search().start(query, actor(), terminal.origin(), ResourceKind.ITEM, text);
    if (submission.rejection() != OperationResult.Reason.OK) {
      query = null;
      status = submission.rejection().name().toLowerCase(Locale.ROOT);
    }
  }

  public void command(ServerPlayer sender, TerminalPackets.Command command) {
    if (sender != viewer
        || command.menu() != containerId
        || !command.session().equals(session)
        || !authorized()
        || busy) return;
    int tick = sender.server.getTickCount();
    if (lastCommand != Integer.MIN_VALUE && tick - lastCommand < 2) return;
    lastCommand = tick;
    if (command.action() == TerminalPackets.SEARCH) {
      text = command.text();
      refresh();
      publish();
      return;
    }
    if (command.action() == TerminalPackets.HOME) {
      after = null;
      selections = List.of();
      status = "loading";
      revision++;
      lastPoll = Integer.MIN_VALUE;
      broadcastChanges();
      return;
    }
    if (command.action() == TerminalPackets.NEXT) {
      if (next != null) {
        after = next;
        selections = List.of();
        next = null;
        status = "loading";
        revision++;
        lastPoll = Integer.MIN_VALUE;
        broadcastChanges();
      }
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
    if (command.action() == TerminalPackets.WITHDRAW) {
      if (command.selection() >= selections.size()) return;
      // A ready observation is advisory; revalidate its captured scope/grants before selecting it.
      if (query == null
          || transfers().search().result(query, actor(), after, 8).reason()
              != OperationResult.Reason.OK) {
        refresh();
        publish();
        return;
      }
      key = selections.get(command.selection()).key();
      endpoint = transfers().inventory(sender, -1);
      direction = WorldStorageRoutes.Direction.FROM_STORAGE;
    } else if (command.action() == TerminalPackets.DEPOSIT) {
      var stack = sender.getInventory().getItem(command.selection());
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
      endpoint = transfers().inventory(sender, command.selection());
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
  public void gridCommand(ServerPlayer sender, TerminalCraftingPackets.Command command) {
    if (sender != viewer
        || command.menu() != containerId
        || !command.session().equals(session)
        || !authorized()
        || busy) return;
    int tick = sender.server.getTickCount();
    if (command.action() == TerminalCraftingPackets.CRAFT
        || command.action() == TerminalCraftingPackets.ENCODE) {
      if (lastCommand != Integer.MIN_VALUE && tick - lastCommand < 2) return;
      lastCommand = tick;
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
        if (key.kind() != ResourceKind.ITEM) return;
        crafting.set(command.cell(), key);
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.CLEAR -> {
        if (command.cell() < 0) crafting.clear();
        else crafting.set(command.cell(), null);
        gridStatus = "ready";
      }
      case TerminalCraftingPackets.ENCODE -> {
        if (command.gridRevision() != crafting.revision()) {
          publish();
          return;
        }
        finishCraft(crafting.encode(sender));
        return;
      }
      case TerminalCraftingPackets.CRAFT -> {
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
    var codec = new PlatformResourceCodec(player().registryAccess());
    for (int index = 0; index < selections.size(); index++) {
      var row = selections.get(index);
      String name;
      try {
        name =
            codec
                .itemStack(row.key(), 1)
                .getHoverName()
                .getString()
                .replaceAll("[\\p{Cntrl}§]", "");
      } catch (IllegalArgumentException unsupported) {
        name = row.key().registryId();
      }
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
            containerId, session, revision, status, rows, next != null, busy, staged);
    if (!packet.equals(view)) {
      view = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
    var cells = new ArrayList<String>(9);
    for (int index = 0; index < 9; index++) {
      var key = crafting.cell(index);
      cells.add(key == null ? "" : key.registryId());
    }
    var result = crafting.preview(player());
    String resultName =
        result.isEmpty() ? "" : result.getHoverName().getString().replaceAll("[\\p{Cntrl}§]", "");
    if (resultName.length() > 96) resultName = resultName.substring(0, 96);
    var grid =
        new TerminalCraftingPackets.View(
            containerId,
            session,
            crafting.revision(),
            cells,
            result.isEmpty()
                ? ""
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(result.getItem())
                    .toString(),
            resultName,
            Math.min(64, result.getCount()),
            gridStatus,
            crafted);
    if (!grid.equals(gridView)) {
      gridView = grid;
      PacketDistributor.sendToPlayer(player(), grid);
    }
  }

  @Override
  public void removed(Player player) {
    if (terminal != null && active) {
      active = false;
      pendingCrafts = 0;
      closeSearch();
      selections = List.of();
      if (route != null && !transfers().inExecution()) transfers().routes().cancel(route, actor());
    }
    super.removed(player);
  }
}
