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
  private final TerminalAccess terminal;
  private final UUID session;
  private final BlockPos position;
  private UUID query, route;
  private ResourceKey after, next;
  private List<CatalogCounts.Row> selections = List.of();
  private String text = "", status = "loading";
  private ResourceKind kind = ResourceKind.ITEM;
  private long revision = 1;
  private int lastCommand = Integer.MIN_VALUE, lastPoll = Integer.MIN_VALUE;
  private boolean active = true, loaded, busy, reachRegistered;
  private TerminalPackets.View view;
  private final TerminalCrafting crafting = new TerminalCrafting();
  private TerminalCraftingPackets.View gridView;
  private String gridStatus = "empty";
  private int pendingCrafts, gatherAttempts, crafted, editWindow = Integer.MIN_VALUE, edits;
  static final int MAX_GATHERS = 18, MAX_EDITS_PER_SECOND = 32;
  private TerminalJobPackets.View jobView;
  private long jobRevision = 1;
  private int craftableOffset;
  private List<ResourceKey> craftables = List.of();
  private boolean craftablesNext;
  private ResourceKey previewKey;
  private int previewAmount;
  private dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.Preview preview;
  private String jobStatus = "ready";
  private List<UUID> jobIds = List.of();

  public TerminalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), buffer.readUUID());
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
    this.session = session;
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(new Slot(inventory, 9 + row * 9 + column, 59 + 18 * column, 148 + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new Slot(inventory, column, 59 + 18 * column, 210));
    view = new TerminalPackets.View(id, session, revision, status, List.of(), false, false, "0");
    jobView =
        new TerminalJobPackets.View(
            id,
            session,
            0,
            List.of(),
            false,
            TerminalJobPackets.NONE,
            "",
            0,
            0,
            List.of(),
            List.of(),
            jobStatus);
    gridView =
        new TerminalCraftingPackets.View(
            id,
            session,
            0,
            java.util.Collections.nCopies(TerminalCraftingPackets.CELLS, ""),
            java.util.Collections.nCopies(TerminalCraftingPackets.CELLS, 1),
            false,
            "",
            "",
            0,
            gridStatus,
            0);
  }

  public TerminalJobPackets.View jobView() {
    return jobView;
  }

  public void accept(TerminalJobPackets.View packet) {
    if (terminal == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.revision() >= jobView.revision()) jobView = packet;
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

  /** Opens a terminal session through {@code access} if it currently allows the player. */
  public static boolean open(
      ServerPlayer player, TerminalAccess access, net.minecraft.network.chat.Component title) {
    if (!access.canUse(player)) return false;
    var session = UUID.randomUUID();
    player.openMenu(
        new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, who) -> new TerminalMenu(id, inventory, access, session), title),
        buffer -> {
          buffer.writeBlockPos(access.getBlockPos());
          buffer.writeUUID(session);
        });
    return player.containerMenu instanceof TerminalMenu;
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
    if (!terminal.upkeep(player())) {
      player()
          .displayClientMessage(
              net.minecraft.network.chat.Component.translatable(
                  "message.factorycore.wireless_no_power"),
              true);
      player().closeContainer();
      return;
    }
    if (!reachRegistered) {
      reachRegistered = true;
      transfers().playerReach(player(), terminal.origin(), terminal.reachSquared());
    }
    int tick = player().server.getTickCount();
    if (lastPoll != Integer.MIN_VALUE && tick - lastPoll < 5) return;
    lastPoll = tick;
    if (!loaded) {
      loaded = true;
      refresh();
      refreshCraftables();
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
    var submission = transfers().search().start(query, actor(), terminal.origin(), kind, text);
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
    int action = command.action();
    boolean mutating =
        action == TerminalPackets.WITHDRAW
            || action == TerminalPackets.DEPOSIT
            || action == TerminalPackets.RETURN
            || action >= TerminalPackets.FILL;
    if (mutating && !terminal.spendAction(sender)) {
      status = "no_power";
      revision++;
      publish();
      return;
    }
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
    if (command.action() == TerminalPackets.KIND) {
      if (command.selection() > 2) return;
      kind = ResourceKind.values()[command.selection()];
      refresh();
      publish();
      return;
    }
    if (command.action() >= TerminalPackets.FILL) {
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
  /** Applies a recipe-viewer transfer as crafting-mode ghost cells; unknown IDs clear a cell. */
  public void gridFill(ServerPlayer sender, TerminalCraftingPackets.Fill fill) {
    if (sender != viewer
        || fill.menu() != containerId
        || !fill.session().equals(session)
        || !authorized()
        || busy) return;
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
      var id = net.minecraft.resources.ResourceLocation.tryParse(fill.items().get(cell));
      var item =
          id == null
              ? net.minecraft.world.item.Items.AIR
              : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
      if (item == net.minecraft.world.item.Items.AIR) continue;
      crafting.set(cell, codec.itemKey(new net.minecraft.world.item.ItemStack(item)));
    }
    gridStatus = "ready";
    publish();
  }

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

  /** Lists craftable outputs of this segment's Crafting Units, eight per page, in key order. */
  private void refreshCraftables() {
    var member = WorldNetworks.get(player().server).membership(terminal.origin());
    if (member.status() != dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID) {
      craftables = List.of();
      craftablesNext = false;
      return;
    }
    var keys =
        new ArrayList<>(
            dev.izzy.factorycore.platform.crafting.WorldCrafting.get(player().server)
                .index(member.scope())
                .byOutput()
                .keySet());
    keys.sort(null);
    craftableOffset = Math.min(craftableOffset, Math.max(0, keys.size() - 1) / 8 * 8);
    craftables =
        List.copyOf(keys.subList(craftableOffset, Math.min(keys.size(), craftableOffset + 8)));
    craftablesNext = keys.size() > craftableOffset + 8;
  }

  /** Autocrafting: page craftables, preview a row, start the previewed plan, cancel own jobs. */
  public void jobCommand(ServerPlayer sender, TerminalJobPackets.Command command) {
    if (sender != viewer
        || command.menu() != containerId
        || !command.session().equals(session)
        || !authorized()) return;
    int tick = sender.server.getTickCount();
    if (lastCommand != Integer.MIN_VALUE && tick - lastCommand < 2) return;
    lastCommand = tick;
    var jobs = dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.get(sender.server);
    switch (command.action()) {
      case TerminalJobPackets.HOME -> {
        craftableOffset = 0;
        refreshCraftables();
      }
      case TerminalJobPackets.NEXT -> {
        if (craftablesNext) craftableOffset += 8;
        refreshCraftables();
      }
      case TerminalJobPackets.PREVIEW -> {
        if (command.revision() != jobRevision || command.selection() >= craftables.size()) {
          publish();
          return;
        }
        previewKey = craftables.get(command.selection());
        previewAmount = command.amount();
        preview = jobs.preview(actor(), terminal.origin(), previewKey, previewAmount);
        jobStatus = preview.reason().isEmpty() ? "previewed" : preview.reason();
      }
      case TerminalJobPackets.START -> {
        if (!terminal.spendAction(sender)) {
          jobStatus = "no_power";
          break;
        }
        if (command.revision() != jobRevision || previewKey == null) {
          publish();
          return;
        }
        var submission = jobs.submit(actor(), terminal.origin(), previewKey, previewAmount);
        if (submission.job() != null) {
          jobStatus = "started";
          previewKey = null;
          preview = null;
        } else {
          jobStatus = submission.reason();
          if (submission.plan() != null)
            preview =
                new dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.Preview(
                    submission.plan(), submission.reason());
        }
      }
      case TerminalJobPackets.KEEP -> {
        if (command.revision() != jobRevision || command.selection() >= craftables.size()) {
          publish();
          return;
        }
        var key = craftables.get(command.selection());
        var existing = jobs.rule(actor(), terminal.origin(), key);
        if (existing != null)
          jobStatus = jobs.unkeep(actor(), existing.id()) ? "unkept" : "refused";
        else {
          var reason =
              jobs.keep(actor(), terminal.origin(), key, command.amount(), command.amount());
          jobStatus = reason.isEmpty() ? "kept" : reason;
        }
      }
      case TerminalJobPackets.CANCEL -> {
        if (command.selection() >= jobIds.size()) return;
        jobStatus =
            jobs.cancel(actor(), jobIds.get(command.selection())) ? "cancelling" : "refused";
      }
      default -> {
        return;
      }
    }
    jobRevision++;
    publish();
  }

  private TerminalJobPackets.Entry entry(ResourceKey key, String amount) {
    String name;
    try {
      name =
          new PlatformResourceCodec(player().registryAccess())
              .itemStack(key, 1)
              .getHoverName()
              .getString()
              .replaceAll("[\\p{Cntrl}§]", "");
    } catch (IllegalArgumentException unsupported) {
      name = key.registryId();
    }
    if (name.length() > 96) name = name.substring(0, 96);
    return new TerminalJobPackets.Entry(key.registryId(), name, amount);
  }

  private void publishJobs() {
    var rows = new ArrayList<TerminalJobPackets.Entry>();
    var runtime = dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.get(player().server);
    for (var key : craftables) {
      // A craftable row's amount carries the viewer's keep-stock threshold, if any.
      var rule = runtime.rule(actor(), terminal.origin(), key);
      rows.add(entry(key, rule == null ? "" : Long.toString(rule.low())));
    }
    var missing = new ArrayList<TerminalJobPackets.Entry>();
    int steps = 0;
    long crafts = 0;
    String reason = "";
    if (preview != null && preview.plan() != null) {
      reason = preview.reason();
      steps = preview.plan().steps().size();
      for (var step : preview.plan().steps()) crafts = Math.addExact(crafts, step.crafts());
      for (var entry : preview.plan().missing().entrySet()) {
        if (missing.size() == TerminalJobPackets.MISSING) break;
        missing.add(entry(entry.getKey(), entry.getValue().toString()));
      }
    } else if (preview != null) reason = preview.reason();
    var ids = new ArrayList<UUID>();
    var jobRows = new ArrayList<TerminalJobPackets.Job>();
    for (var job :
        dev.izzy.factorycore.platform.crafting.WorldCraftingJobs.get(player().server)
            .jobs(viewer.getUUID())) {
      if (jobRows.size() == TerminalJobPackets.JOBS) break;
      ids.add(job.id());
      jobRows.add(
          new TerminalJobPackets.Job(
              entry(job.request(), Long.toString(job.amount())),
              job.craftsDone(),
              job.craftsTotal(),
              job.state().name().toLowerCase(Locale.ROOT),
              job.reason()));
    }
    jobIds = List.copyOf(ids);
    var packet =
        new TerminalJobPackets.View(
            containerId,
            session,
            jobRevision,
            rows,
            craftablesNext,
            previewKey == null
                ? TerminalJobPackets.NONE
                : entry(previewKey, Integer.toString(previewAmount)),
            reason,
            steps,
            crafts,
            missing,
            jobRows,
            jobStatus);
    if (!packet.equals(jobView)) {
      jobView = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
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
        var shown =
            switch (row.key().kind()) {
              case ITEM -> codec.itemStack(row.key(), 1).getHoverName();
              case FLUID -> codec.fluidStack(row.key(), 1).getHoverName();
              case ENERGY ->
                  net.minecraft.network.chat.Component.translatable(
                      "gui.factorycore.terminal.energy");
            };
        name = shown.getString().replaceAll("[\\p{Cntrl}§]", "");
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
            session,
            crafting.revision(),
            cells,
            counts,
            crafting.processing(),
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
    publishJobs();
  }

  @Override
  public void removed(Player player) {
    if (terminal != null && active) {
      active = false;
      if (reachRegistered) transfers().playerReach(player(), terminal.origin(), 64);
      pendingCrafts = 0;
      closeSearch();
      selections = List.of();
      if (route != null && !transfers().inExecution()) transfers().routes().cancel(route, actor());
    }
    super.removed(player);
  }
}
