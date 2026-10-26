package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Actors;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * The network overview a Controller opens (playtest H10): its routing status and how many devices
 * of each kind its segment has, synced as data slots. Counts need View access and come from the
 * published topology ({@link WorldNetworks#roleCounts}), so the menu reads no positions, labels or
 * contents. Button {@link #ACCESS_BUTTON} opens the Network Access screen.
 */
public final class OverviewMenu extends AbstractContainerMenu {
  public static final int ACCESS_BUTTON = 0;

  /** Data slot 0: the status ordinal (−1 while unknown); 1..: role counts (−1 without View). */
  private static final int STATUS = 0, ROLES = 1;

  private static final int REFRESH_TICKS = 10;

  private final ContainerData data;
  private final NetworkNodeEntity controller;
  private final Player viewer;
  private int clock;

  public OverviewMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, (NetworkNodeEntity) null);
  }

  private OverviewMenu(int id, Inventory inventory, NetworkNodeEntity controller) {
    super(NetworkContent.OVERVIEW_MENU.get(), id);
    this.controller = controller;
    this.viewer = inventory.player;
    data = new SimpleContainerData(ROLES + NetworkTopology.Role.values().length);
    data.set(STATUS, -1);
    for (int role = 0; role < NetworkTopology.Role.values().length; role++)
      data.set(ROLES + role, -1);
    addDataSlots(data);
  }

  public static void open(ServerPlayer player, NetworkNodeEntity controller) {
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, who) -> new OverviewMenu(id, inventory, controller),
            Component.translatable("gui.factorycore.overview.title")),
        buffer -> {});
  }

  /** The routing status, or null until the server has sent it. */
  public NetworkTopology.Status status() {
    int value = data.get(STATUS);
    return value < 0 || value >= NetworkTopology.Status.values().length
        ? null
        : NetworkTopology.Status.values()[value];
  }

  /** Members of {@code role} in this network's segment, or −1 when unknown or not viewable. */
  public int count(NetworkTopology.Role role) {
    return data.get(ROLES + role.ordinal());
  }

  @Override
  public void broadcastChanges() {
    if (controller != null && clock-- <= 0) {
      clock = REFRESH_TICKS;
      refresh();
    }
    super.broadcastChanges();
  }

  private void refresh() {
    var runtime = WorldNetworks.get(((ServerPlayer) viewer).server);
    var position = controller.position();
    var status = runtime.membership(position).status();
    data.set(STATUS, status.ordinal());
    boolean view = runtime.allows(Actors.of(viewer), position, NetworkPermissions.Permission.VIEW);
    var counts = view ? runtime.roleCounts(position) : null;
    for (int role = 0; role < NetworkTopology.Role.values().length; role++)
      data.set(ROLES + role, counts == null ? -1 : Math.min(Short.MAX_VALUE, counts[role]));
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (button != ACCESS_BUTTON || controller == null || !(player instanceof ServerPlayer server))
      return false;
    NetworkAccessMenu.open(server, controller);
    return true;
  }

  @Override
  public boolean stillValid(Player player) {
    return controller == null
        || !controller.isRemoved()
            && player.distanceToSqr(controller.getBlockPos().getCenter()) <= 64;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }
}
