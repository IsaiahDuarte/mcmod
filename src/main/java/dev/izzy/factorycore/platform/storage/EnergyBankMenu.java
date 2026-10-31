package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Q20: the Energy Bank screen. It shows the charge, the FE that went in and out during the last
 * tick, the tier's rate and the network state, synced in data slots. Its module slot takes a Cell
 * Upgrade module: putting one in upgrades the bank where it stands, keeping its energy ({@link
 * EnergyBankEntity#upgradeInPlace}), and uses the module up. A module that does not apply stays in
 * the slot with a message and returns to the player when the screen closes. The screen follows the
 * position, so it stays open while the upgraded bank's block is replaced.
 */
public final class EnergyBankMenu extends AbstractContainerMenu {
  /** Panel-relative item positions on the 176-wide kit panel. */
  public static final int MODULE_X = 152,
      MODULE_Y = 54,
      INVENTORY_X = 8,
      INVENTORY_Y = 84,
      HOTBAR_Y = INVENTORY_Y + 58;

  /** Data slots: energy (low, high 16 bits), FE in, FE out, tier, network state + 1. */
  private static final int ENERGY_LOW = 0, ENERGY_HIGH = 1, INPUT = 2, OUTPUT = 3, TIER = 4;

  private static final int STATE = 5, COUNT = 6;

  private final Player viewer;
  private final BlockPos position;
  private final ContainerData data;
  private final SimpleContainer module = new SimpleContainer(1);

  /** Client constructor. */
  public EnergyBankMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, buffer.readBlockPos(), new SimpleContainerData(COUNT));
  }

  private EnergyBankMenu(int id, Inventory inventory, BlockPos position, ContainerData data) {
    super(EnergyBankContent.MENU.get(), id);
    viewer = inventory.player;
    this.position = position;
    this.data = data;
    addSlot(
        new Slot(module, 0, MODULE_X, MODULE_Y) {
          @Override
          public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof CellUpgradeItem;
          }

          @Override
          public int getMaxStackSize() {
            return 1;
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
    module.addListener(this::slotsChanged);
  }

  public static void open(ServerPlayer player, BlockPos position) {
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, who) ->
                new EnergyBankMenu(id, inventory, position, new ServerData(player, position)),
            dev.izzy.factorycore.platform.DeviceNames.title(
                player.level().getBlockEntity(position),
                Component.translatable("gui.factorycore.bank"))),
        buffer -> buffer.writeBlockPos(position));
  }

  /** Server data: read from the bank at the position each sync. */
  private static final class ServerData implements ContainerData {
    private final ServerPlayer player;
    private final BlockPos position;

    ServerData(ServerPlayer player, BlockPos position) {
      this.player = player;
      this.position = position;
    }

    @Override
    public int get(int index) {
      if (!(player.level().getBlockEntity(position) instanceof EnergyBankEntity bank)) return 0;
      return switch (index) {
        case ENERGY_LOW -> bank.getEnergyStored() & 0xffff;
        case ENERGY_HIGH -> bank.getEnergyStored() >>> 16;
        case INPUT -> (int) Math.min(Short.MAX_VALUE, bank.flow().input());
        case OUTPUT -> (int) Math.min(Short.MAX_VALUE, bank.flow().output());
        case TIER -> bank.tier().ordinal();
        default ->
            WorldNetworks.get(player.server)
                    .membership(Positions.of(player.level(), position))
                    .status()
                    .ordinal()
                + 1;
      };
    }

    @Override
    public void set(int index, int value) {}

    @Override
    public int getCount() {
      return COUNT;
    }
  }

  public long energy() {
    return (data.get(ENERGY_LOW) & 0xffffL) | (long) (data.get(ENERGY_HIGH) & 0xffff) << 16;
  }

  public long input() {
    return data.get(INPUT);
  }

  public long output() {
    return data.get(OUTPUT);
  }

  public EnergyBankTier tier() {
    var tiers = EnergyBankTier.values();
    return tiers[Math.max(0, Math.min(tiers.length - 1, data.get(TIER)))];
  }

  /** The network state, or null before the first sync. */
  public NetworkTopology.Status networkStatus() {
    int state = data.get(STATE);
    var values = NetworkTopology.Status.values();
    return state <= 0 || state > values.length ? null : values[state - 1];
  }

  /** The network state's translation key suffix, or "loading". */
  public String networkKey() {
    var status = networkStatus();
    return status == null ? "loading" : status.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public void slotsChanged(Container container) {
    super.slotsChanged(container);
    if (container != module
        || !(viewer instanceof ServerPlayer player)
        || !(module.getItem(0).getItem() instanceof CellUpgradeItem upgrade)
        || !(player.level().getBlockEntity(position) instanceof EnergyBankEntity bank)) return;
    try {
      var tier = bank.upgradeInPlace(Actors.of(player), upgrade.targetLevel());
      if (tier == null) {
        player.displayClientMessage(
            Component.translatable("message.factorycore.bank_upgrade_denied"), true);
        return;
      }
      module.removeItemNoUpdate(0);
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.bank_upgraded",
              Component.translatable("block.factorycore." + tierName(tier) + "_energy_bank")),
          true);
      broadcastChanges();
    } catch (IllegalArgumentException | IllegalStateException refused) {
      player.displayClientMessage(
          Component.translatable(
              "message.factorycore.bank_upgrade_refused",
              Component.translatable(
                  "block.factorycore." + tierName(bank.tier()) + "_energy_bank")),
          true);
    }
  }

  private static String tierName(EnergyBankTier tier) {
    return tier.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    var slot = slots.get(index);
    if (!slot.hasItem()) return ItemStack.EMPTY;
    var stack = slot.getItem();
    var copy = stack.copy();
    if (index == 0) {
      if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
    } else if (stack.getItem() instanceof CellUpgradeItem) {
      if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
    } else return ItemStack.EMPTY;
    if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
    else slot.setChanged();
    return copy;
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer
        && player.level().getBlockEntity(position) instanceof EnergyBankEntity
        && player.distanceToSqr(position.getCenter()) <= 64;
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    clearContainer(player, module);
  }
}
