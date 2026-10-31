package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.power.EnergyUsers;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Q25: also the segment's energy use from {@link EnergyUsers}: the total FE per tick, each kind
 * of device's count and use, and the five largest users (by kind, as positions are not shown).
 */
public final class OverviewMenu extends AbstractContainerMenu {
  public static final int ACCESS_BUTTON = 0;

  /** Data slot 0: the status ordinal (−1 while unknown); 1..: role counts (−1 without View). */
  private static final int STATUS = 0, ROLES = 1;

  private static final int KINDS = EnergyUsers.Kind.values().length;

  /**
   * Q25, after the roles: the total use, then per kind its count and use, then the top users' kind
   * ordinal (−1 for none) and use. A use is tenths of FE per tick in two 15-bit slots (data slots
   * travel as shorts); −1 in the first slot means unknown or not viewable.
   */
  private static final int ENERGY = ROLES + NetworkTopology.Role.values().length,
      PER_KIND = ENERGY + 2,
      TOP = PER_KIND + 3 * KINDS,
      SLOTS = TOP + 3 * EnergyUsers.TOP;

  private static final long MAX_TENTHS = (1L << 30) - 1;

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
    data = new SimpleContainerData(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) data.set(slot, -1);
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

  /** FE per tick stored at {@code slot}, or −1 when unknown. */
  private double use(int slot) {
    if (data.get(slot) < 0) return -1;
    return ((long) data.get(slot) << 15 | data.get(slot + 1)) / 10.0;
  }

  private void use(int slot, double perTick) {
    long tenths = Math.min(MAX_TENTHS, Math.round(perTick * 10));
    data.set(slot, (int) (tenths >> 15));
    data.set(slot + 1, (int) (tenths & 0x7FFF));
  }

  /** The segment's total FE use per tick, or −1 when unknown or not viewable. */
  public double energyUse() {
    return use(ENERGY);
  }

  /** How many devices of {@code kind} spend energy here (−1 when unknown). */
  public int energyCount(EnergyUsers.Kind kind) {
    return data.get(PER_KIND + 3 * kind.ordinal());
  }

  /** What devices of {@code kind} spend together, FE per tick. */
  public double energyUse(EnergyUsers.Kind kind) {
    return use(PER_KIND + 3 * kind.ordinal() + 1);
  }

  /** The largest users, largest first (at most {@link EnergyUsers#TOP}). */
  public List<EnergyUsers.Use> topUsers() {
    var top = new ArrayList<EnergyUsers.Use>();
    for (int i = 0; i < EnergyUsers.TOP; i++) {
      int kind = data.get(TOP + 3 * i);
      if (kind < 0 || kind >= KINDS) break;
      top.add(new EnergyUsers.Use(EnergyUsers.Kind.values()[kind], use(TOP + 3 * i + 1)));
    }
    return top;
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
    for (int slot = ENERGY; slot < SLOTS; slot++) data.set(slot, -1);
    if (counts == null) return;
    var energy =
        EnergyUsers.summary(
            (net.minecraft.server.level.ServerLevel) controller.getLevel(),
            runtime.membership(position).scope());
    use(ENERGY, energy.total());
    for (int kind = 0; kind < KINDS; kind++) {
      data.set(PER_KIND + 3 * kind, Math.min(Short.MAX_VALUE, energy.counts()[kind]));
      use(PER_KIND + 3 * kind + 1, energy.uses()[kind]);
    }
    for (int i = 0; i < energy.top().size(); i++) {
      data.set(TOP + 3 * i, energy.top().get(i).kind().ordinal());
      use(TOP + 3 * i + 1, energy.top().get(i).perTick());
    }
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
