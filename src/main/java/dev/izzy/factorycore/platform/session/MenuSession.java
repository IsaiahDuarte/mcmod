package dev.izzy.factorycore.platform.session;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server-authoritative protocol every Factory Core screen shares, owned by its menu: a random
 * session nonce bound to one viewer, the command gate (right player, right open menu, right
 * session, rate-limited), view polling and only-on-change view publishing. Menus keep their own
 * domain state and views; a menu with several views uses one session for all of them.
 */
public final class MenuSession {
  /** Commands are admitted at most once per this many ticks. */
  public static final int COMMAND_TICKS = 2;

  /** Builds the server-side menu for a fresh session nonce. */
  @FunctionalInterface
  public interface Factory {
    AbstractContainerMenu create(int id, Inventory inventory, UUID session);
  }

  private final AbstractContainerMenu menu;
  private final Player viewer;
  private final UUID id;
  private final boolean server;
  private final TickGate poll;
  private final TickGate commands = new TickGate(COMMAND_TICKS);
  private boolean active = true;

  public MenuSession(
      AbstractContainerMenu menu, Player viewer, UUID id, boolean server, int pollTicks) {
    this.menu = Objects.requireNonNull(menu);
    this.viewer = Objects.requireNonNull(viewer);
    this.id = Objects.requireNonNull(id);
    this.server = server;
    poll = new TickGate(pollTicks);
  }

  /** Opens a menu for {@code player} under a new nonce, which the client reads back. */
  public static void open(ServerPlayer player, Component title, Factory factory) {
    var nonce = UUID.randomUUID();
    player.openMenu(
        new SimpleMenuProvider((id, inventory, who) -> factory.create(id, inventory, nonce), title),
        buffer -> buffer.writeUUID(nonce));
  }

  /** The nonce a client-side menu constructor reads from its opening buffer. */
  public static UUID read(FriendlyByteBuf buffer) {
    return buffer.readUUID();
  }

  public UUID id() {
    return id;
  }

  public boolean server() {
    return server;
  }

  public boolean active() {
    return active;
  }

  /** The viewing player on the server side. */
  public ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  public Player viewer() {
    return viewer;
  }

  /** Called from the menu's {@code removed}; no further commands or views are accepted. */
  public void close() {
    active = false;
  }

  /** True when a payload addresses this open menu and session. */
  public boolean owns(int menuId, UUID session) {
    return menuId == menu.containerId && id.equals(session);
  }

  /** The default {@code stillValid}: the same live viewer, still online while server-side. */
  public boolean stillValid(Player player) {
    return player == viewer
        && player.isAlive()
        && (!server
            || active && player().server.getPlayerList().getPlayer(player.getUUID()) == player);
  }

  /** True when {@code sender} is this session's viewer, with this menu open, for this session. */
  public boolean fromViewer(ServerPlayer sender, int menuId, UUID session) {
    return server
        && active
        && sender == viewer
        && sender.containerMenu == menu
        && owns(menuId, session);
  }

  /** {@link #fromViewer} and not rate-limited: commands share one gate per session. */
  public boolean admitCommand(ServerPlayer sender, int menuId, UUID session) {
    return fromViewer(sender, menuId, session) && admitRate();
  }

  /** The shared command rate gate alone, for menus that report a rate-limited command. */
  public boolean admitRate() {
    return commands.admit(player().server.getTickCount());
  }

  /** The next {@link #admitPoll} is admitted at once (after a change the viewer should see). */
  public void pollNow() {
    poll.reset();
  }

  /**
   * The common broadcast step: false unless server-side and open; closes the screen when the menu
   * is no longer {@code current} (lease, facing or scope changed); true when a view poll is due.
   */
  public boolean due(java.util.function.BooleanSupplier current) {
    if (!server || !active || player().containerMenu != menu) return false;
    if (!current.getAsBoolean()) {
      player().closeContainer();
      return false;
    }
    return poll.admit(player().server.getTickCount());
  }

  /** True when this server-side session is open and due for a view poll. */
  public boolean admitPoll() {
    return server
        && active
        && player().containerMenu == menu
        && poll.admit(player().server.getTickCount());
  }

  /** Sends {@code next} if it differs from {@code previous}; returns the view now shown. */
  public <V extends CustomPacketPayload> V publish(V previous, V next) {
    if (next.equals(previous)) return previous;
    PacketDistributor.sendToPlayer(player(), next);
    return next;
  }
}
