package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Network Access screen session (ADR 0055) anchored at a controller or gateway. The server owns the
 * origin, session nonce, page and the pending ownership-transfer confirmation; clients send only an
 * action, a principal and one small value. Every mutation re-derives the actor from the connected
 * player and goes through {@link AccessAdministration}, so current Manage at the origin (including
 * ancestor gateway branch policy) is checked at execution. Viewers without Manage see only their
 * own effective access and the gateway limits that apply here, never other principals.
 */
public final class NetworkAccessMenu extends AbstractContainerMenu {
  /** Ticks a requested ownership transfer waits for explicit confirmation. */
  public static final int CONFIRM_TICKS = 200;

  private static final int POLL_TICKS = 10;
  private final Player viewer;
  private final NetworkNodeEntity anchor;
  private final BlockPos position;
  private final UUID session;
  private final NodeRegistry.Handle lease;
  private UUID network;
  private int page, lastCommand = Integer.MIN_VALUE, lastPoll = Integer.MIN_VALUE;
  private UUID pending = AccessPackets.NONE;
  private int pendingUntil;
  private long pendingGeneration, revision = 1;
  private boolean hadManage, active = true;
  private String status = "ready";
  private AccessPackets.View view;

  /** Client constructor. */
  public NetworkAccessMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), buffer.readUUID());
  }

  private NetworkAccessMenu(
      int id, Inventory inventory, NetworkNodeEntity anchor, BlockPos position, UUID session) {
    super(NetworkContent.ACCESS_MENU.get(), id);
    viewer = inventory.player;
    this.anchor = anchor;
    this.position = position;
    this.session = session;
    lease = anchor == null ? null : anchor.handle();
    view = empty(id, session, 0, "loading");
    if (anchor != null) {
      var membership = WorldNetworks.get(player().server).membership(anchor.position());
      network =
          membership.status() == NetworkTopology.Status.VALID ? membership.scope().network() : null;
    }
  }

  /** Opens an access session at a commissioned controller or gateway. */
  public static void open(ServerPlayer player, NetworkNodeEntity anchor) {
    if (anchor.handle() == null) return;
    var nonce = UUID.randomUUID();
    var position = anchor.getBlockPos();
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, who) -> new NetworkAccessMenu(id, inventory, anchor, position, nonce),
            Component.translatable("gui.factorycore.access")),
        buffer -> {
          buffer.writeBlockPos(position);
          buffer.writeUUID(nonce);
        });
  }

  private static AccessPackets.View empty(int id, UUID session, long revision, String status) {
    return new AccessPackets.View(
        id,
        session,
        revision,
        "",
        false,
        false,
        false,
        0,
        0,
        0,
        0,
        0,
        List.of(),
        0,
        0,
        AccessPackets.NONE,
        "",
        status,
        0);
  }

  public UUID session() {
    return session;
  }

  public BlockPos position() {
    return position;
  }

  public AccessPackets.View view() {
    return view;
  }

  /** Client: accept only the matching session's newer views. */
  public void accept(AccessPackets.View packet) {
    if (anchor == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.revision() >= view.revision()) view = packet;
  }

  /** Client: request one action; the server validates everything. */
  public void send(int action, UUID principal, int value) {
    PacketDistributor.sendToServer(
        new AccessPackets.Command(containerId, session, action, principal, value));
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private boolean current() {
    if (!active
        || anchor.isRemoved()
        || lease == null
        || !lease.equals(anchor.handle())
        || viewer.level() != anchor.getLevel()
        || viewer.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5)
            > 64) return false;
    var membership = WorldNetworks.get(player().server).membership(anchor.position());
    // A rebuilding region keeps the read-only session; a different network ends it.
    return membership.status() != NetworkTopology.Status.VALID
        || network == null
        || network.equals(membership.scope().network());
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (anchor == null || current());
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    active = false;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (anchor == null || !active || player().containerMenu != this) return;
    int tick = player().server.getTickCount();
    if (lastPoll != Integer.MIN_VALUE && tick - lastPoll < POLL_TICKS) return;
    lastPoll = tick;
    publish();
  }

  /** Server: one client action, accepted only from the session's own connected player. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  public void command(ServerPlayer sender, AccessPackets.Command packet) {
    if (anchor == null
        || sender != viewer
        || packet.menu() != containerId
        || !packet.session().equals(session)
        || sender.containerMenu != this
        || !stillValid(sender)) return;
    var server = sender.server;
    int tick = server.getTickCount();
    if (lastCommand != Integer.MIN_VALUE && tick - lastCommand < 2) {
      status = "rate";
      publish();
      return;
    }
    lastCommand = tick;
    var actor = AccessAdministration.actor(sender);
    var origin = anchor.position();
    var principal = packet.principal();
    switch (packet.action()) {
      case AccessPackets.GRANT, AccessPackets.REVOKE, AccessPackets.REVOKE_ALL -> {
        if (packet.action() != AccessPackets.REVOKE_ALL
            && packet.value() >= Permission.values().length) return;
        if (!listed(actor, principal)) {
          status = "unknown";
          break;
        }
        Set<Permission> named =
            packet.action() == AccessPackets.REVOKE_ALL
                ? EnumSet.allOf(Permission.class)
                : EnumSet.of(Permission.values()[packet.value()]);
        var result =
            packet.action() == AccessPackets.GRANT
                ? AccessAdministration.change(server, actor, origin, principal, named, Set.of())
                : AccessAdministration.change(server, actor, origin, principal, Set.of(), named);
        status = key(result.outcome(), "updated");
        if (result.outcome() == AccessAdministration.Outcome.OK)
          AccessAdministration.notifyChanged(
              server, sender.getName(), sender, principal, position, result.permissions());
      }
      case AccessPackets.PAGE -> page = packet.value();
      case AccessPackets.TRANSFER -> {
        var state = WorldNetworks.get(server).grants(actor, origin);
        if (state == null || !mayTransfer(sender, state)) {
          status = "denied";
          break;
        }
        if (principal.equals(state.owner()) || !listed(actor, principal)) {
          status = "unknown";
          break;
        }
        pending = principal;
        pendingUntil = tick + CONFIRM_TICKS;
        pendingGeneration = state.generation();
        status = "confirm";
      }
      case AccessPackets.CONFIRM -> {
        if (pending.equals(AccessPackets.NONE) || !pending.equals(principal)) {
          // A confirmation for anyone but the requested principal withdraws the request.
          pending = AccessPackets.NONE;
          status = "unconfirmed";
          break;
        }
        var state = WorldNetworks.get(server).grants(actor, origin);
        var target = pending;
        pending = AccessPackets.NONE;
        if (tick > pendingUntil) {
          status = "expired";
          break;
        }
        if (state == null || state.generation() != pendingGeneration) {
          status = "changed";
          break;
        }
        var previous = state.owner();
        var outcome = AccessAdministration.transfer(server, actor, origin, target);
        status = key(outcome, "transferred");
        if (outcome == AccessAdministration.Outcome.OK)
          AccessAdministration.notifyTransferred(
              server, sender.getName(), sender, previous, target, position);
      }
      case AccessPackets.CANCEL -> {
        if (!pending.equals(AccessPackets.NONE)) status = "cancelled";
        pending = AccessPackets.NONE;
      }
      case AccessPackets.RECOVERY -> {
        dev.izzy.factorycore.platform.storage.RecoveryMenu.open(sender);
        return;
      }
      default -> {
        return;
      }
    }
    publish();
  }

  private static String key(AccessAdministration.Outcome outcome, String ok) {
    return switch (outcome) {
      case OK -> ok;
      case NO_NETWORK -> "no_network";
      case DENIED -> "denied";
      case LIMIT -> "limit";
      case OWNER_IMPLICIT -> "owner";
    };
  }

  /** Only the current owner or an operator, with current Manage here, may transfer ownership. */
  private static boolean mayTransfer(ServerPlayer player, NetworkPermissions.State state) {
    return player.getUUID().equals(state.owner()) || player.hasPermissions(2);
  }

  /** A principal the screen may name: an explicit grant here or a currently online player. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  private boolean listed(NetworkPermissions.Actor actor, UUID principal) {
    if (principal.equals(AccessPackets.NONE)) return false;
    var server = player().server;
    if (server.getPlayerList().getPlayer(principal) != null) return true;
    var state = WorldNetworks.get(server).grants(actor, anchor.position());
    return state != null && state.grants().containsKey(principal);
  }

  private void publish() {
    var built = build(revision);
    if (built.equals(view)) return;
    revision++;
    view = build(revision);
    PacketDistributor.sendToPlayer(player(), view);
  }

  /** Canonical view at this instant; pending expiry and lost Manage update the status. */
  @SuppressWarnings("PMD.CloseResource") // Minecraft owns the borrowed server.
  private AccessPackets.View build(long revision) {
    var server = player().server;
    var networks = WorldNetworks.get(server);
    var origin = anchor.position();
    var membership = networks.membership(origin);
    if (membership.status() != NetworkTopology.Status.VALID) {
      pending = AccessPackets.NONE;
      return empty(containerId, session, revision, "rebuilding");
    }
    var actor = AccessAdministration.actor(player());
    int self = 0;
    for (var permission : Permission.values())
      if (networks.authorize(actor, origin, permission) == NetworkAuthority.Decision.ALLOWED)
        self |= 1 << permission.ordinal();
    boolean manage = (self & 1 << Permission.MANAGE.ordinal()) != 0;
    int branch = (1 << Permission.values().length) - 1, inherited = branch;
    int kinds = (1 << ResourceKind.values().length) - 1;
    var ancestors = networks.ancestors(origin);
    for (var ancestor : ancestors) {
      branch &= AccessAdministration.mask(ancestor.policy().branchPermissions());
      inherited &= AccessAdministration.mask(ancestor.policy().inheritedPermissions());
      int kindMask = 0;
      for (var kind : ancestor.policy().inheritedKinds()) kindMask |= 1 << kind.ordinal();
      kinds &= kindMask;
    }
    var canonical = DeviceSavedData.get(server).network(membership.scope().network()).snapshot();
    boolean ownerSelf = canonical.owner().equals(viewer.getUUID());
    // The actor who just gave ownership away keeps that result; anyone else learns of the loss.
    if (hadManage && !manage && !status.equals("transferred")) status = "manage_revoked";
    hadManage = manage;
    int tick = server.getTickCount();
    if (!pending.equals(AccessPackets.NONE)) {
      if (!manage) pending = AccessPackets.NONE;
      else if (tick > pendingUntil) {
        pending = AccessPackets.NONE;
        status = "expired";
      } else if (canonical.generation() != pendingGeneration) {
        pending = AccessPackets.NONE;
        status = "changed";
      }
    }
    if (!manage)
      return new AccessPackets.View(
          containerId,
          session,
          revision,
          "",
          ownerSelf,
          false,
          false,
          self,
          branch,
          inherited,
          kinds,
          Math.min(64, ancestors.size()),
          List.of(),
          0,
          0,
          AccessPackets.NONE,
          "",
          status,
          0);
    var principals = new ArrayList<AccessPackets.Row>();
    canonical.grants().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry ->
                principals.add(
                    new AccessPackets.Row(
                        entry.getKey(),
                        "",
                        AccessAdministration.mask(entry.getValue()),
                        server.getPlayerList().getPlayer(entry.getKey()) != null,
                        false)));
    server.getPlayerList().getPlayers().stream()
        .filter(
            player ->
                !player.getUUID().equals(canonical.owner())
                    && !canonical.grants().containsKey(player.getUUID()))
        .sorted(Comparator.comparing(player -> player.getGameProfile().getName()))
        .limit(AccessPackets.MAX_CANDIDATES)
        .forEach(
            player -> principals.add(new AccessPackets.Row(player.getUUID(), "", 0, true, true)));
    int pages =
        Math.max(1, (principals.size() + AccessPackets.PAGE_ROWS - 1) / AccessPackets.PAGE_ROWS);
    page = Math.min(page, Math.min(pages, AccessPackets.MAX_PAGES) - 1);
    var rows = new ArrayList<AccessPackets.Row>();
    for (int i = page * AccessPackets.PAGE_ROWS;
        i < Math.min(principals.size(), (page + 1) * AccessPackets.PAGE_ROWS);
        i++) {
      var row = principals.get(i);
      rows.add(
          new AccessPackets.Row(
              row.id(), name(row.id()), row.granted(), row.online(), row.candidate()));
    }
    return new AccessPackets.View(
        containerId,
        session,
        revision,
        name(canonical.owner()),
        ownerSelf,
        true,
        ownerSelf || player().hasPermissions(2),
        self,
        branch,
        inherited,
        kinds,
        Math.min(64, ancestors.size()),
        rows,
        principals.size(),
        page,
        pending,
        pending.equals(AccessPackets.NONE) ? "" : name(pending),
        status,
        canonical.generation());
  }

  private String name(UUID id) {
    String text = AccessAdministration.name(player().server, id).getString();
    return text.length() > 48 ? text.substring(0, 48) : text;
  }
}
