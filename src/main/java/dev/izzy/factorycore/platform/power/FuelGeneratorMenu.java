package dev.izzy.factorycore.platform.power;

import dev.izzy.factorycore.core.storage.FuelGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Q142 (playthrough note 2): the Fuel Generator screen. It has the fuel slot, the container slot
 * (take only), the stored FE, the FE given out this tick, the burn left on the current fuel and why
 * it is not producing, synced in data slots.
 */
public final class FuelGeneratorMenu extends AbstractContainerMenu {
  /** Panel-relative item positions on the 176-wide kit panel. */
  public static final int FUEL_X = 36,
      FUEL_Y = 72,
      REMAINDER_X = 152,
      REMAINDER_Y = 72,
      INVENTORY_X = 8,
      INVENTORY_Y = 112,
      HOTBAR_Y = INVENTORY_Y + 58;

  /** Data slots: 16-bit halves of energy, burn left and burn total; output; status. */
  private static final int ENERGY_LOW = 0,
      ENERGY_HIGH = 1,
      BURN_LOW = 2,
      BURN_HIGH = 3,
      TOTAL_LOW = 4,
      TOTAL_HIGH = 5,
      OUTPUT = 6,
      STATUS = 7,
      COUNT = 8;

  /** Status values, in data order; 0 is "not synced yet". */
  public static final String[] STATUSES = {
    "loading", "burning", "no_fuel", "full", "remainder_full", "unsupported"
  };

  private final BlockPos position;
  private final ContainerData data;

  /** Client constructor: empty slots that the server fills. */
  public FuelGeneratorMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(
        id,
        inventory,
        buffer.readBlockPos(),
        new ItemStackHandler(2),
        new SimpleContainerData(COUNT));
  }

  private FuelGeneratorMenu(
      int id, Inventory inventory, BlockPos position, IItemHandler slots, ContainerData data) {
    super(FuelGeneratorContent.MENU.get(), id);
    this.position = position;
    this.data = data;
    addSlot(new SlotItemHandler(slots, FuelGeneratorEntity.FUEL, FUEL_X, FUEL_Y));
    addSlot(
        new SlotItemHandler(slots, FuelGeneratorEntity.REMAINDER, REMAINDER_X, REMAINDER_Y) {
          @Override
          public boolean mayPlace(ItemStack stack) {
            return false;
          }
        });
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
    addDataSlots(data);
  }

  /** Server menu on {@code generator}, for {@link #open} and GameTests. */
  public static FuelGeneratorMenu create(
      int id, Inventory inventory, FuelGeneratorEntity generator) {
    return new FuelGeneratorMenu(
        id, inventory, generator.getBlockPos(), generator.slots(), new ServerData(generator));
  }

  public static void open(ServerPlayer player, FuelGeneratorEntity generator) {
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, who) -> create(id, inventory, generator),
            dev.izzy.factorycore.platform.DeviceNames.title(
                generator, Component.translatable("block.factorycore.fuel_generator"))),
        buffer -> buffer.writeBlockPos(generator.getBlockPos()));
  }

  /** Server data: read from the generator each sync. */
  private record ServerData(FuelGeneratorEntity generator) implements ContainerData {
    @Override
    public int get(int index) {
      var core = generator.generator();
      Level level = generator.getLevel();
      return switch (index) {
        case ENERGY_LOW -> (int) (core.energy() & 0xffff);
        case ENERGY_HIGH -> (int) (core.energy() >>> 16 & 0xffff);
        case BURN_LOW -> (int) (core.burnRemaining() & 0xffff);
        case BURN_HIGH -> (int) (core.burnRemaining() >>> 16 & 0xffff);
        case TOTAL_LOW -> (int) (core.burnTotal() & 0xffff);
        case TOTAL_HIGH -> (int) (core.burnTotal() >>> 16 & 0xffff);
        case OUTPUT -> level == null ? 0 : (int) core.recentOutput(level.getGameTime());
        default -> status(generator);
      };
    }

    @Override
    public void set(int index, int value) {}

    @Override
    public int getCount() {
      return COUNT;
    }
  }

  /** The status index into {@link #STATUSES}, from the generator's blocked reason. */
  static int status(FuelGeneratorEntity generator) {
    var reason = generator.blockedReason();
    if (reason.isEmpty())
      reason =
          generator.generator().status() == FuelGenerator.Status.BURNING ? "burning" : "no_fuel";
    for (int i = 1; i < STATUSES.length; i++) if (STATUSES[i].equals(reason)) return i;
    return 0;
  }

  /** Data slot {@code index}, as the next sync sends it. */
  public int data(int index) {
    return data.get(index);
  }

  private long wide(int low, int high) {
    return (data.get(low) & 0xffffL) | (long) (data.get(high) & 0xffff) << 16;
  }

  public long energy() {
    return wide(ENERGY_LOW, ENERGY_HIGH);
  }

  public long burnRemaining() {
    return wide(BURN_LOW, BURN_HIGH);
  }

  public long burnTotal() {
    return wide(TOTAL_LOW, TOTAL_HIGH);
  }

  /** FE given out to neighbours during the last tick. */
  public long output() {
    return data.get(OUTPUT);
  }

  /** The status's key suffix (see {@link #STATUSES}). */
  public String status() {
    int index = data.get(STATUS);
    return STATUSES[index < 0 || index >= STATUSES.length ? 0 : index];
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    var slot = slots.get(index);
    if (!slot.hasItem()) return ItemStack.EMPTY;
    var stack = slot.getItem();
    var copy = stack.copy();
    if (index < 2) {
      if (!moveItemStackTo(stack, 2, slots.size(), true)) return ItemStack.EMPTY;
    } else if (slots.getFirst().mayPlace(stack)) {
      if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
    } else return ItemStack.EMPTY;
    if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
    else slot.setChanged();
    return copy;
  }

  @Override
  public boolean stillValid(Player player) {
    return player.level().getBlockEntity(position) instanceof FuelGeneratorEntity
        && player.distanceToSqr(position.getCenter()) <= 64;
  }
}
