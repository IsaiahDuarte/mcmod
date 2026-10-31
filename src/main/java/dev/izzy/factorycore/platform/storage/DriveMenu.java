package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.session.MenuSession;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Drive screen (docs/UX_PLAN.md U2). Menu button {@code b} acts on bay {@code b % 4} with the item
 * on the cursor: a cell installs into an empty bay, a module upgrades the bay's cell in place, and
 * an empty cursor picks the cell up. Buttons 4–7 send the bay's cell to the inventory instead.
 * Shift-clicking a cell in the inventory installs it in the first empty bay. Like sneak-use on the
 * block, this is physical access: it needs no network grant.
 */
public final class DriveMenu extends AbstractContainerMenu implements DeviceStatus.Holder {
  /** Screen layout, panel-relative item positions (on the 230-wide kit panel since H3a). */
  public static final int BAY_X = 7,
      BAY_Y = 18,
      BAY_HEIGHT = 26,
      INVENTORY_X = 35,
      INVENTORY_Y = 156,
      HOTBAR_Y = INVENTORY_Y + 58;

  private static final int POLL_TICKS = 10;
  private final MenuSession session;
  private final StorageDriveEntity drive;
  private DrivePackets.Status status = DrivePackets.Status.READY;
  private String detail = "";
  private DrivePackets.View view;
  private DeviceStatus.View deviceStatus;

  /** Client constructor. */
  public DriveMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, MenuSession.read(buffer), null);
  }

  private DriveMenu(int id, Inventory inventory, UUID session, StorageDriveEntity drive) {
    super(StorageContent.DRIVE_MENU.get(), id);
    this.session = new MenuSession(this, inventory.player, session, drive != null, POLL_TICKS);
    this.drive = drive;
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(
            new Slot(
                inventory,
                9 + row * 9 + column,
                INVENTORY_X + 18 * column,
                INVENTORY_Y + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new Slot(inventory, column, INVENTORY_X + 18 * column, HOTBAR_Y));
    var empty = new ArrayList<DrivePackets.Bay>();
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) empty.add(DrivePackets.Bay.EMPTY);
    view = new DrivePackets.View(id, session, 0, empty, DrivePackets.Status.LOADING, "", 0);
    deviceStatus = DeviceStatus.View.pending(id, session);
  }

  @Override
  public DeviceStatus.View deviceStatus() {
    return deviceStatus;
  }

  @Override
  public void acceptDeviceStatus(DeviceStatus.View packet) {
    if (!session.server())
      deviceStatus = DeviceStatus.accept(containerId, session.id(), deviceStatus, packet);
  }

  public static void open(ServerPlayer player, StorageDriveEntity drive) {
    MenuSession.open(
        player,
        dev.izzy.factorycore.platform.DeviceNames.title(
            drive, Component.translatable("block.factorycore.storage_drive")),
        (id, inventory, nonce) -> new DriveMenu(id, inventory, nonce, drive));
  }

  public DrivePackets.View view() {
    return view;
  }

  public void accept(DrivePackets.View packet) {
    if (!session.server()
        && session.owns(packet.menu(), packet.session())
        && packet.revision() >= view.revision()) view = packet;
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (drive == null
        || !(player instanceof ServerPlayer)
        || button < 0
        || button >= 2 * DrivePackets.BAYS
        || !stillValid(player)) return false;
    int bay = button % DrivePackets.BAYS;
    boolean toInventory = button >= DrivePackets.BAYS;
    var carried = getCarried();
    detail = "";
    try {
      if (toInventory || carried.isEmpty()) {
        if (drive.cell(bay).isEmpty()) return false;
        var removed = drive.removeCell(bay);
        if (toInventory) give(player, removed);
        else setCarried(removed);
        status = DrivePackets.Status.REMOVED;
      } else if (carried.getItem() instanceof CellItem) {
        drive.installCell(bay, carried.copyWithCount(1));
        // Even creative installation consumes the physical lease item (see StorageDriveBlock).
        carried.shrink(1);
        status = DrivePackets.Status.INSTALLED;
      } else if (carried.getItem() instanceof CellUpgradeItem module) {
        var result = drive.upgradeCell(bay, module.targetLevel());
        if (!result.portable().isEmpty()) give(player, result.portable());
        if (result.upgraded() && !player.getAbilities().instabuild) carried.shrink(1);
        status = result.upgraded() ? DrivePackets.Status.UPGRADED : DrivePackets.Status.REFUSED;
      } else {
        status = DrivePackets.Status.WRONG_ITEM;
      }
    } catch (IllegalArgumentException | IllegalStateException refused) {
      status = DrivePackets.Status.REFUSED;
      detail = String.valueOf(refused.getMessage());
    }
    session.pollNow();
    return true;
  }

  /** B3: edits the partition of the cell in a bay, at once, if the player may EDIT here. */
  public void filter(ServerPlayer sender, DrivePackets.FilterCommand packet) {
    if (drive == null
        || !session.fromViewer(sender, packet.menu(), packet.session())
        || !stillValid(sender)
        || !session.admitRate()) return;
    var networks = dev.izzy.factorycore.platform.network.WorldNetworks.get(sender.server);
    if (!networks.allows(
        dev.izzy.factorycore.platform.Actors.of(sender),
        Positions.of(sender.serverLevel(), drive.getBlockPos()),
        dev.izzy.factorycore.core.network.NetworkPermissions.Permission.EDIT)) {
      status = DrivePackets.Status.DENIED;
      session.pollNow();
      return;
    }
    if (packet.action() == DrivePackets.FilterCommand.PRIORITY) {
      try {
        drive.setPriority(Integer.parseInt(packet.text().strip()));
        status = DrivePackets.Status.PRIORITY;
        detail = "";
      } catch (IllegalArgumentException | IllegalStateException refused) {
        status = DrivePackets.Status.REFUSED;
        detail = "Priority must be a whole number from -1000 to 1000";
      }
      session.pollNow();
      return;
    }
    var cell = drive.cell(packet.bay());
    if (!(cell.getItem() instanceof CellItem item)) return;
    var kind = item.tier().kind();
    var current = cell.get(StorageContent.PARTITION.get());
    var filter =
        current == null
            ? dev.izzy.factorycore.core.resource.ResourceFilter.all(kind)
            : current.filter();
    var terms = new ArrayList<>(filter.terms());
    var mode = filter.mode();
    detail = "";
    try {
      switch (packet.action()) {
        case DrivePackets.FilterCommand.CARRIED -> {
          var term = FilterEdits.carried(kind, getCarried());
          if (term == null) return;
          terms.add(term);
        }
        case DrivePackets.FilterCommand.CYCLE -> {
          if (packet.selection() >= terms.size()) return;
          var next =
              FilterEdits.cycle(
                  kind,
                  terms.get(packet.selection()),
                  packet.text(),
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                      sender.registryAccess()));
          if (next == null) {
            status = DrivePackets.Status.STALE;
            session.pollNow();
            return;
          }
          terms.set(packet.selection(), next);
        }
        case DrivePackets.FilterCommand.REMOVE -> {
          if (packet.selection() >= terms.size()) return;
          terms.remove(packet.selection());
        }
        case DrivePackets.FilterCommand.CLEAR -> terms.clear();
        case DrivePackets.FilterCommand.CONTENTS -> {
          terms = contents(sender, cell);
          if (terms.isEmpty()) {
            status = DrivePackets.Status.REFUSED;
            detail = "The cell is empty";
            session.pollNow();
            return;
          }
          mode = dev.izzy.factorycore.core.resource.ResourceFilter.Mode.INCLUDE;
        }
        case DrivePackets.FilterCommand.REGISTRY ->
            terms.add(
                new dev.izzy.factorycore.core.resource.ResourceFilter.Registry(packet.text()));
        case DrivePackets.FilterCommand.TAG -> {
          String text = packet.text();
          terms.add(
              new dev.izzy.factorycore.core.resource.ResourceFilter.Tag(
                  text.startsWith("#") ? text.substring(1) : text));
        }
        case DrivePackets.FilterCommand.MODE ->
            mode =
                switch (mode) {
                  case ALL -> dev.izzy.factorycore.core.resource.ResourceFilter.Mode.INCLUDE;
                  case INCLUDE -> dev.izzy.factorycore.core.resource.ResourceFilter.Mode.EXCLUDE;
                  case EXCLUDE -> dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL;
                };
        default -> {
          return;
        }
      }
      // A first slot starts an allow list; an empty list, or "everything", removes the partition.
      if (packet.action() != DrivePackets.FilterCommand.MODE) {
        if (terms.isEmpty()) mode = dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL;
        else if (mode == dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL)
          mode = dev.izzy.factorycore.core.resource.ResourceFilter.Mode.INCLUDE;
      } else if (mode == dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL) terms.clear();
      if (mode != dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL && terms.isEmpty())
        mode = dev.izzy.factorycore.core.resource.ResourceFilter.Mode.ALL;
      drive.setPartition(
          packet.bay(), new dev.izzy.factorycore.core.resource.ResourceFilter(kind, mode, terms));
      status = DrivePackets.Status.PARTITIONED;
    } catch (IllegalArgumentException | IllegalStateException refused) {
      status = DrivePackets.Status.REFUSED;
      detail = String.valueOf(refused.getMessage());
    }
    session.pollNow();
  }

  /**
   * Q23: one slot per stored type, in catalog order, up to the filter's slot limit. A plain
   * resource becomes its ID; one with components (an enchanted book, a named item) stays exact.
   */
  private static ArrayList<dev.izzy.factorycore.core.resource.ResourceFilter.Term> contents(
      ServerPlayer sender, ItemStack cell) {
    var terms = new ArrayList<dev.izzy.factorycore.core.resource.ResourceFilter.Term>();
    var handle = CellHandleData.read(cell);
    var registry = DeviceSavedData.get(sender.server).registry();
    var codec =
        new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(sender.registryAccess());
    if (handle == null || registry.validate(handle) != DeviceRegistry.Failure.OK) return terms;
    dev.izzy.factorycore.core.resource.ResourceKey after = null;
    // A few pages at most: emptied rows are skipped, and the slots hold 16.
    for (int pages = 0; pages < 4; pages++) {
      var page = registry.catalog(handle, after, ResourceLedger.CATALOG_PAGE_SIZE);
      for (var entry : page.entries()) {
        if (terms.size() == dev.izzy.factorycore.core.resource.ResourceFilter.MAX_TERMS)
          return terms;
        if (entry.stock().total() <= 0) continue;
        var key = entry.key();
        dev.izzy.factorycore.core.resource.ResourceFilter.Term term =
            plain(codec, key)
                ? new dev.izzy.factorycore.core.resource.ResourceFilter.Registry(key.registryId())
                : new dev.izzy.factorycore.core.resource.ResourceFilter.Exact(key);
        if (!terms.contains(term)) terms.add(term);
      }
      if (page.next() == null) break;
      after = page.next();
    }
    return terms;
  }

  /** True if {@code key} is its resource with default components. */
  private static boolean plain(
      dev.izzy.factorycore.platform.resource.PlatformResourceCodec codec,
      dev.izzy.factorycore.core.resource.ResourceKey key) {
    if (!codec.resolvable(key)) return false;
    return key.kind() == ResourceKind.FLUID
        ? key.equals(
            codec.fluidKey(
                new net.neoforged.neoforge.fluids.FluidStack(
                    codec.fluidStack(key, 1).getFluid(), 1)))
        : key.equals(codec.itemKey(new ItemStack(codec.itemStack(key, 1).getItem())));
  }

  private static void give(Player player, ItemStack stack) {
    if (!player.getInventory().add(stack)) player.drop(stack, false);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (drive == null || !stillValid(player) || index < 0 || index >= slots.size())
      return ItemStack.EMPTY;
    var slot = slots.get(index);
    var stack = slot.getItem();
    if (!(stack.getItem() instanceof CellItem)) return ItemStack.EMPTY;
    detail = "";
    try {
      drive.installCell(stack.copyWithCount(1));
      stack.shrink(1);
      slot.setChanged();
      status = DrivePackets.Status.INSTALLED;
    } catch (IllegalArgumentException | IllegalStateException refused) {
      status = DrivePackets.Status.REFUSED;
      detail = String.valueOf(refused.getMessage());
    }
    session.pollNow();
    return ItemStack.EMPTY;
  }

  @Override
  public boolean stillValid(Player player) {
    if (!session.stillValid(player)) return false;
    if (drive == null) return true;
    var position = drive.getBlockPos();
    return !drive.isRemoved()
        && player.level() == drive.getLevel()
        && player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5)
            <= 64;
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    session.close();
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!session.admitPoll()) return;
    publish();
    deviceStatus =
        DeviceStatus.publish(
            session.player(),
            deviceStatus,
            DeviceStatus.of(
                session.player().server,
                containerId,
                session.id(),
                Positions.of(drive.getLevel(), drive.getBlockPos()),
                "",
                ""));
  }

  private void publish() {
    var built = build(view.revision());
    if (built.equals(view)) return;
    view = build(view.revision() + 1);
    session.publish(null, view);
  }

  private DrivePackets.View build(long revision) {
    var bays = new ArrayList<DrivePackets.Bay>(DrivePackets.BAYS);
    var registry = DeviceSavedData.get(session.player().server).registry();
    for (int slot = 0; slot < DrivePackets.BAYS; slot++) {
      var cell = drive.cell(slot);
      var handle = CellHandleData.read(cell);
      var owner = handle == null ? null : registry.ownership(handle.backing());
      if (!(cell.getItem() instanceof CellItem item)) {
        bays.add(DrivePackets.Bay.EMPTY);
        continue;
      }
      var definition = owner == null ? null : owner.definition();
      var partition = cell.get(StorageContent.PARTITION.get());
      bays.add(
          new DrivePackets.Bay(
              BuiltInRegistries.ITEM.getKey(item).toString(),
              item.tier().kind() == ResourceKind.FLUID,
              owner == null ? 0 : owner.total(),
              definition == null ? item.tier().capacity() : definition.capacity(),
              definition == null ? item.tier().infinite() : definition.infinite(),
              owner == null ? 0 : owner.types(),
              partition == null ? "ALL" : partition.filter().mode().name(),
              partition == null
                  ? java.util.List.of()
                  : FilterEdits.rules(
                      partition.filter(),
                      new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                          session.player().registryAccess()))));
    }
    boolean faulted = !drive.diagnostic().isEmpty() && status == DrivePackets.Status.READY;
    return new DrivePackets.View(
        containerId,
        session.id(),
        revision,
        bays,
        faulted ? DrivePackets.Status.REFUSED : status,
        faulted ? truncate(drive.diagnostic()) : truncate(detail),
        drive.priority());
  }

  private static String truncate(String text) {
    return text.length() <= 128 ? text : text.substring(0, 125) + "...";
  }
}
