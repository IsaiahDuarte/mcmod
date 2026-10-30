package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.TransferRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.network.AccessAdministration;
import dev.izzy.factorycore.platform.session.MenuSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transfer Recovery screen session (ADR 0055): the in-game face of ADR 0022/0047 inspection, known
 * cleanup, legacy settlement and completed-record retirement. Operators page every record; other
 * players page only records they originally started, scanning a bounded number of registry pages
 * per refresh. Actions go through {@link RecoveryActions}, which preserves evidence first and
 * shares rate limits with the commands. Uncertain records offer no action.
 */
public final class RecoveryMenu extends AbstractContainerMenu {
  /** Registry pages of 16 scanned per refresh when filtering to the viewer's own records. */
  public static final int OWN_SCAN_PAGES = 4;

  private static final int POLL_TICKS = 20;
  private final MenuSession session;
  private final List<UUID> history = new ArrayList<>();
  private UUID cursor, next;
  private long revision = 1;
  private RecoveryPackets.Status status = RecoveryPackets.Status.READY;
  private String evidence = "", detail = "";
  private RecoveryPackets.View view;

  /** Client constructor. */
  public RecoveryMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, MenuSession.read(buffer), false);
  }

  private RecoveryMenu(int id, Inventory inventory, UUID session, boolean server) {
    super(RecoveryContent.MENU.get(), id);
    this.session = new MenuSession(this, inventory.player, session, server, POLL_TICKS);
    view =
        new RecoveryPackets.View(
            id, session, 0, false, List.of(), 0, false, RecoveryPackets.Status.LOADING, "", "");
  }

  /** Opens the screen for a connected player; what it lists depends on operator status. */
  public static void open(ServerPlayer player) {
    MenuSession.open(
        player,
        Component.translatable("gui.factorycore.recovery"),
        (id, inventory, nonce) -> new RecoveryMenu(id, inventory, nonce, true));
  }

  public UUID session() {
    return session.id();
  }

  public RecoveryPackets.View view() {
    return view;
  }

  public void accept(RecoveryPackets.View packet) {
    if (!session.server()
        && session.owns(packet.menu(), packet.session())
        && packet.revision() >= view.revision()) view = packet;
  }

  public void send(int action, UUID record) {
    PacketDistributor.sendToServer(
        new RecoveryPackets.Command(containerId, session.id(), action, record));
  }

  private ServerPlayer player() {
    return session.player();
  }

  @Override
  public boolean stillValid(Player player) {
    return session.stillValid(player);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    session.close();
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (session.admitPoll()) publish();
  }

  public void command(ServerPlayer sender, RecoveryPackets.Command packet) {
    if (!stillValid(sender) || !session.admitCommand(sender, packet.menu(), packet.session()))
      return;
    evidence = "";
    detail = "";
    switch (packet.action()) {
      case RecoveryPackets.HOME -> {
        history.clear();
        cursor = null;
        status = RecoveryPackets.Status.READY;
      }
      case RecoveryPackets.NEXT -> {
        if (next != null) {
          history.add(cursor);
          cursor = next;
          status = RecoveryPackets.Status.READY;
        }
      }
      case RecoveryPackets.RETURN, RecoveryPackets.SETTLE -> {
        var row = visible(packet.record());
        if (row == null) {
          status = RecoveryPackets.Status.MISSING;
          break;
        }
        boolean settle = packet.action() == RecoveryPackets.SETTLE;
        var id = row.id();
        var outcome =
            RecoveryActions.request(
                sender,
                id,
                settle,
                completed -> {
                  if (!session.active() || player().containerMenu != this) return;
                  status = settle ? RecoveryPackets.Status.SETTLED : RecoveryPackets.Status.RESULT;
                  detail = completed.reason().name();
                  publish();
                });
        status = RecoveryPackets.Status.of(outcome.reason());
        evidence = outcome.evidence();
        if (status == RecoveryPackets.Status.QUEUED) detail = outcome.admission().name();
      }
      case RecoveryPackets.RETIRE -> {
        if (!sender.hasPermissions(Actors.OPERATOR_LEVEL)) {
          status = RecoveryPackets.Status.OPERATOR;
          break;
        }
        if (visible(packet.record()) == null) {
          status = RecoveryPackets.Status.MISSING;
          break;
        }
        var outcome = RecoveryActions.retire(sender.server, packet.record());
        status = RecoveryPackets.Status.of(outcome.reason());
        evidence = outcome.evidence();
      }
      default -> {
        return;
      }
    }
    publish();
  }

  /** A record on the page this session last showed; forged ids for other records are refused. */
  private RecoveryPackets.Row visible(UUID id) {
    for (var row : view.rows()) if (row.id().equals(id)) return row;
    return null;
  }

  private void publish() {
    var built = build(revision);
    if (built.equals(view)) return;
    revision++;
    view = build(revision);
    PacketDistributor.sendToPlayer(player(), view);
  }

  private RecoveryPackets.View build(long revision) {
    var player = player();
    boolean operator = player.hasPermissions(Actors.OPERATOR_LEVEL);
    var rows = new ArrayList<RecoveryPackets.Row>();
    next = null;
    try {
      var records = DeviceSavedData.get(player.server).transfers();
      if (operator) {
        var page = records.page(cursor, RecoveryPackets.PAGE_ROWS);
        for (var record : page.records()) rows.add(row(record));
        next = page.after();
      } else {
        UUID scan = cursor;
        scan:
        for (int scanned = 0; scanned < OWN_SCAN_PAGES; scanned++) {
          var page = records.page(scan, 16);
          for (var record : page.records()) {
            if (record.transfer().owner().equals(player.getUUID())) {
              if (rows.size() == RecoveryPackets.PAGE_ROWS) {
                next = rows.get(rows.size() - 1).id();
                break scan;
              }
              rows.add(row(record));
            }
            scan = record.id();
          }
          if (page.after() == null) break;
          // Scan budget spent: the next page resumes after the last scanned record.
          if (scanned + 1 == OWN_SCAN_PAGES) next = scan;
        }
      }
    } catch (IllegalStateException unavailable) {
      return new RecoveryPackets.View(
          containerId,
          session.id(),
          revision,
          operator,
          List.of(),
          history.size(),
          false,
          RecoveryPackets.Status.UNAVAILABLE,
          "",
          "");
    }
    return new RecoveryPackets.View(
        containerId,
        session.id(),
        revision,
        operator,
        rows,
        Math.min(1024, history.size()),
        next != null,
        status,
        evidence,
        detail);
  }

  private RecoveryPackets.Row row(TransferRegistry.Stored record) {
    var move = record.transfer();
    String owner = AccessAdministration.name(player().server, move.owner()).getString();
    String resource = move.key().registryId();
    return new RecoveryPackets.Row(
        record.id(),
        owner.length() > 48 ? owner.substring(0, 48) : owner,
        move.state().name(),
        move.key().kind().name(),
        resource.length() > 96 ? resource.substring(0, 96) : resource,
        move.staged(),
        move.delivered(),
        move.returned(),
        move.pending().name(),
        RecoveryActions.classify(record),
        move.owner().equals(player().getUUID()));
  }
}
