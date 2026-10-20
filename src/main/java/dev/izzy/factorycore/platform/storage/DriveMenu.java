package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKind;
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
public final class DriveMenu extends AbstractContainerMenu {
  public static final int BAY_X = 8, BAY_Y = 18, BAY_HEIGHT = 26, INVENTORY_Y = 158;
  private static final int POLL_TICKS = 10;
  private final MenuSession session;
  private final StorageDriveEntity drive;
  private String status = "ready", detail = "";
  private DrivePackets.View view;

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
        addSlot(new Slot(inventory, 9 + row * 9 + column, 8 + 18 * column, INVENTORY_Y + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new Slot(inventory, column, 8 + 18 * column, INVENTORY_Y + 58));
    var empty = new ArrayList<DrivePackets.Bay>();
    for (int bay = 0; bay < DrivePackets.BAYS; bay++) empty.add(DrivePackets.Bay.EMPTY);
    view = new DrivePackets.View(id, session, 0, empty, "loading", "");
  }

  public static void open(ServerPlayer player, StorageDriveEntity drive) {
    MenuSession.open(
        player,
        Component.translatable("block.factorycore.storage_drive"),
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
        status = "removed";
      } else if (carried.getItem() instanceof CellItem) {
        drive.installCell(bay, carried.copyWithCount(1));
        // Even creative installation consumes the physical lease item (see StorageDriveBlock).
        carried.shrink(1);
        status = "installed";
      } else if (carried.getItem() instanceof CellUpgradeItem module) {
        var result = drive.upgradeCell(bay, module.targetLevel());
        if (!result.portable().isEmpty()) give(player, result.portable());
        if (result.upgraded() && !player.getAbilities().instabuild) carried.shrink(1);
        status = result.upgraded() ? "upgraded" : "refused";
      } else {
        status = "wrong_item";
      }
    } catch (IllegalArgumentException | IllegalStateException refused) {
      status = "refused";
      detail = String.valueOf(refused.getMessage());
    }
    session.pollNow();
    return true;
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
      status = "installed";
    } catch (IllegalArgumentException | IllegalStateException refused) {
      status = "refused";
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
    if (session.admitPoll()) publish();
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
      bays.add(
          new DrivePackets.Bay(
              BuiltInRegistries.ITEM.getKey(item).toString(),
              item.tier().kind() == ResourceKind.FLUID,
              owner == null ? 0 : owner.total(),
              definition == null ? item.tier().capacity() : definition.capacity(),
              definition == null ? item.tier().infinite() : definition.infinite(),
              owner == null ? 0 : owner.types()));
    }
    boolean faulted = !drive.diagnostic().isEmpty() && status.equals("ready");
    return new DrivePackets.View(
        containerId,
        session.id(),
        revision,
        bays,
        faulted ? "refused" : status,
        faulted ? truncate(drive.diagnostic()) : truncate(detail));
  }

  private static String truncate(String text) {
    return text.length() <= 128 ? text : text.substring(0, 125) + "...";
  }
}
