package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Q32 (QD8): the Portable Storage's screen. Slot 0 holds its one item cell (kept in the item's
 * {@code container} component); slots 1–45 show a page of the cell's contents, read from its
 * catalog; then the player's inventory.
 *
 * <p>Every move is server-side and goes through {@link PortableCells#with}: click a shown stack to
 * take a stack (right click: half) onto the cursor, Shift-click to take it into the inventory;
 * click with a held stack to store it (right click: one), Shift-click an inventory stack to store
 * it. A move costs {@link #COST} FE from the Portable Storage and is refused without it. Only the
 * owner may open it.
 */
public final class PortableStorageMenu extends AbstractContainerMenu {
  public static final int COLUMNS = 9, ROWS = 5, SHOWN = COLUMNS * ROWS;
  public static final int CELL_X = 8, CELL_Y = 18, VIEW_X = 8, VIEW_Y = 40;
  public static final int INVENTORY_X = 8, INVENTORY_Y = 145, HOTBAR_Y = INVENTORY_Y + 58;

  /** FE per take or store. */
  public static final int COST = 64;

  /** Catalog rows read per refresh; more types show only their count. */
  public static final int MAX_READ = 1024;

  private static final int REFRESH_TICKS = 20;

  private final Player player;
  private final InteractionHand hand;
  private final ItemStack portable;
  private final SimpleContainer cell = new SimpleContainer(1);

  /** Shown stacks carry the stored amount, so the usual stack-size cap does not apply. */
  private final SimpleContainer view =
      new SimpleContainer(SHOWN) {
        @Override
        public int getMaxStackSize(ItemStack stack) {
          return Integer.MAX_VALUE;
        }

        @Override
        public int getMaxStackSize() {
          return Integer.MAX_VALUE;
        }
      };

  private final List<ResourceKey> keys = new ArrayList<>();
  private final DataSlot page = DataSlot.standalone();
  private final DataSlot types = DataSlot.standalone();
  private boolean dirty = true;
  private int age;

  public PortableStorageMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, buffer.readEnum(InteractionHand.class));
  }

  PortableStorageMenu(int id, Inventory inventory, InteractionHand hand) {
    super(PortableContent.MENU.get(), id);
    this.player = inventory.player;
    this.hand = hand;
    this.portable = player.getItemInHand(hand);
    var stored = portable.get(net.minecraft.core.component.DataComponents.CONTAINER);
    if (stored != null) cell.setItem(0, stored.copyOne());
    cell.addListener(this::cellChanged);
    addSlot(
        new Slot(cell, 0, CELL_X, CELL_Y) {
          @Override
          public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof CellItem item
                && item.tier().kind() == ResourceKind.ITEM;
          }

          @Override
          public int getMaxStackSize() {
            return 1;
          }
        });
    for (int index = 0; index < SHOWN; index++)
      addSlot(
          new Slot(view, index, VIEW_X + 18 * (index % COLUMNS), VIEW_Y + 18 * (index / COLUMNS)) {
            @Override
            public boolean mayPickup(Player who) {
              return false;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
              return false;
            }
          });
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(
            new HeldGuard(
                inventory,
                column + row * 9 + 9,
                INVENTORY_X + 18 * column,
                INVENTORY_Y + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new HeldGuard(inventory, column, INVENTORY_X + 18 * column, HOTBAR_Y));
    addDataSlot(page);
    addDataSlot(types);
  }

  /** The Portable Storage itself cannot be picked up while its screen is open. */
  private final class HeldGuard extends Slot {
    HeldGuard(Container container, int index, int x, int y) {
      super(container, index, x, y);
    }

    @Override
    public boolean mayPickup(Player who) {
      return getItem() != portable;
    }
  }

  public static void open(ServerPlayer player, InteractionHand hand) {
    player.openMenu(
        new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, who) -> new PortableStorageMenu(id, inventory, hand),
            Component.translatable("container.factorycore.portable_storage")),
        buffer -> buffer.writeEnum(hand));
  }

  /** The shown page (0-based) and the number of item types in the cell. */
  public int page() {
    return page.get();
  }

  public int types() {
    return types.get();
  }

  public int pages() {
    return Math.max(1, (types.get() + SHOWN - 1) / SHOWN);
  }

  public boolean hasCell() {
    return !cell.getItem(0).isEmpty();
  }

  private void cellChanged(Container changed) {
    if (player.level().isClientSide()) return;
    var stack = cell.getItem(0);
    portable.set(
        net.minecraft.core.component.DataComponents.CONTAINER,
        ItemContainerContents.fromItems(List.of(stack.copy())));
    dirty = true;
  }

  @Override
  public boolean stillValid(Player who) {
    return who.getItemInHand(hand) == portable && PortableStorageItem.mayUse(portable, who);
  }

  /** Buttons: 0 previous page, 1 next page. */
  @Override
  public boolean clickMenuButton(Player who, int id) {
    int next = id == 0 ? page.get() - 1 : id == 1 ? page.get() + 1 : -1;
    if (next < 0 || next >= pages()) return false;
    page.set(next);
    dirty = true;
    return true;
  }

  @Override
  public void clicked(int slot, int button, ClickType type, Player who) {
    // Number keys and the offhand key may not swap the Portable Storage out of its hand.
    if (type == ClickType.SWAP
        && (button == 40 && hand == InteractionHand.OFF_HAND
            || button >= 0
                && button < 9
                && hand == InteractionHand.MAIN_HAND
                && who.getInventory().getItem(button) == portable)) return;
    if (slot >= 1 && slot <= SHOWN) {
      if (who instanceof ServerPlayer server) view(server, slot - 1, button, type);
      return;
    }
    super.clicked(slot, button, type, who);
  }

  private void view(ServerPlayer who, int index, int button, ClickType type) {
    var carried = getCarried();
    if (type == ClickType.PICKUP && !carried.isEmpty()) {
      int amount = button == 1 ? 1 : carried.getCount();
      long stored = store(who, carried, amount);
      if (stored > 0) carried.shrink((int) stored);
      setCarried(carried);
      return;
    }
    int shown = page.get() * SHOWN + index;
    if (shown >= keys.size()) return;
    var key = keys.get(shown);
    var codec = new PlatformResourceCodec(who.registryAccess());
    int stack = codec.itemStack(key, 1).getMaxStackSize();
    if (type == ClickType.PICKUP && carried.isEmpty()) {
      long total = view.getItem(index).getCount();
      int amount = button == 1 ? (int) Math.max(1, Math.min(stack, (total + 1) / 2)) : stack;
      long taken = take(who, key, amount);
      if (taken > 0) setCarried(codec.itemStack(key, (int) taken));
    } else if (type == ClickType.QUICK_MOVE) {
      int room = room(who.getInventory(), codec.itemStack(key, 1));
      long taken = take(who, key, Math.min(stack, room));
      if (taken > 0) who.getInventory().add(codec.itemStack(key, (int) taken));
    }
  }

  /** Room for {@code like} in the main inventory, at most one stack. */
  private static int room(Inventory inventory, ItemStack like) {
    int room = 0;
    for (int slot = 0; slot < 36 && room < like.getMaxStackSize(); slot++) {
      var held = inventory.getItem(slot);
      if (held.isEmpty()) room += like.getMaxStackSize();
      else if (ItemStack.isSameItemSameComponents(held, like))
        room += Math.max(0, held.getMaxStackSize() - held.getCount());
    }
    return Math.min(room, like.getMaxStackSize());
  }

  @Override
  public ItemStack quickMoveStack(Player who, int index) {
    if (!(who instanceof ServerPlayer server)) return ItemStack.EMPTY;
    var slot = slots.get(index);
    if (index == 0) {
      // The cell goes back to the inventory.
      var stack = slot.getItem();
      if (!stack.isEmpty() && moveItemStackTo(stack, SHOWN + 1, slots.size(), true))
        slot.setChanged();
      return ItemStack.EMPTY;
    }
    if (index > SHOWN && slot.hasItem() && slot.mayPickup(who)) {
      var stack = slot.getItem();
      if (stack.getItem() instanceof CellItem && !hasCell()) {
        if (moveItemStackTo(stack, 0, 1, false)) slot.setChanged();
        return ItemStack.EMPTY;
      }
      long stored = store(server, stack, stack.getCount());
      if (stored > 0) {
        stack.shrink((int) stored);
        slot.setChanged();
      }
    }
    return ItemStack.EMPTY;
  }

  /** Charge left for one move, refusing with a message when there is none. */
  private boolean charged(ServerPlayer who) {
    var energy = portable.getCapability(Capabilities.EnergyStorage.ITEM);
    if (energy != null && energy.extractEnergy(COST, true) >= COST) return true;
    who.displayClientMessage(
        Component.translatable("message.factorycore.portable_storage.no_power"), true);
    return false;
  }

  private void spend() {
    var energy = portable.getCapability(Capabilities.EnergyStorage.ITEM);
    if (energy != null) energy.extractEnergy(COST, false);
  }

  /** Stores up to {@code amount} of {@code stack} (not changed here); returns how many went in. */
  private long store(ServerPlayer who, ItemStack stack, int amount) {
    if (!hasCell() || stack.isEmpty() || amount <= 0 || !charged(who)) return 0;
    ResourceKey key;
    try {
      key = new PlatformResourceCodec(who.registryAccess()).itemKey(stack);
    } catch (IllegalArgumentException unsupported) {
      return 0;
    }
    long stored = move(who, handle -> registry(who).insert(handle, key, amount).amount());
    if (stored > 0) spend();
    return stored;
  }

  /**
   * Takes up to {@code amount} of {@code key}; returns how many came out (the caller places them).
   */
  private long take(ServerPlayer who, ResourceKey key, int amount) {
    if (!hasCell() || amount <= 0 || !charged(who)) return 0;
    long taken = move(who, handle -> registry(who).extract(handle, key, amount, null).amount());
    if (taken > 0) spend();
    return taken;
  }

  private static DeviceRegistry registry(ServerPlayer who) {
    return DeviceSavedData.get(who.server).registry();
  }

  private long move(ServerPlayer who, java.util.function.Function<DeviceRegistry.Handle, Long> op) {
    try {
      var moved = PortableCells.with(who, cell.getItem(0), op);
      cell.setItem(0, moved.cell());
      dirty = true;
      return moved.result();
    } catch (IllegalArgumentException | IllegalStateException refused) {
      who.displayClientMessage(
          Component.translatable("message.factorycore.portable_storage.refused"), true);
      return 0;
    }
  }

  @Override
  public void broadcastChanges() {
    if (player instanceof ServerPlayer server && (dirty || ++age >= REFRESH_TICKS)) refresh(server);
    super.broadcastChanges();
  }

  /** Reads the cell's catalog (bounded), most stored first, and fills the shown page. */
  private void refresh(ServerPlayer who) {
    dirty = false;
    age = 0;
    keys.clear();
    var totals = new java.util.HashMap<ResourceKey, Long>();
    var handle = CellHandleData.read(cell.getItem(0));
    var registry = registry(who);
    if (handle != null && registry.validate(handle) == DeviceRegistry.Failure.OK) {
      ResourceKey after = null;
      do {
        var catalog = registry.catalog(handle, after, ResourceLedger.CATALOG_PAGE_SIZE);
        for (var entry : catalog.entries())
          if (keys.size() < MAX_READ && entry.stock().total() > 0) {
            keys.add(entry.key());
            totals.put(entry.key(), entry.stock().total());
          }
        after = catalog.next();
      } while (after != null && keys.size() < MAX_READ);
    }
    keys.sort(
        Comparator.<ResourceKey>comparingLong(totals::get)
            .reversed()
            .thenComparing(Comparator.naturalOrder()));
    types.set(keys.size());
    if (page.get() >= pages()) page.set(pages() - 1);
    var codec = new PlatformResourceCodec(who.registryAccess());
    for (int index = 0; index < SHOWN; index++) {
      int shown = page.get() * SHOWN + index;
      view.setItem(
          index,
          shown < keys.size()
              ? codec.itemStack(
                  keys.get(shown), (int) Math.min(Integer.MAX_VALUE, totals.get(keys.get(shown))))
              : ItemStack.EMPTY);
    }
  }
}
