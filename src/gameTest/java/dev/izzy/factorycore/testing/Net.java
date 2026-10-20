package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceContent;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Builds a test network in one place: each call places a real block and puts its device in the
 * state a player would (commissioned, leased, cells installed). Positions are test-relative.
 * Unusual topologies (unowned, wrong facing) still use the helper directly.
 *
 * <pre>{@code
 * var net = Net.in(helper);
 * net.controller(ROOT);
 * net.cable(CABLE);
 * net.drive(DRIVE, CellTier.STARTER_ITEM);
 * var cell = net.cell(DRIVE, 0);
 * // in a sequence: .thenWaitUntil(() -> net.assertValid(DRIVE))
 * }</pre>
 */
public final class Net {
  private final GameTestHelper helper;
  private final NetworkPermissions.Actor owner;

  private Net(GameTestHelper helper, NetworkPermissions.Actor owner) {
    this.helper = helper;
    this.owner = owner;
  }

  /** A network owned by a fresh non-operator principal. */
  public static Net in(GameTestHelper helper) {
    return new Net(helper, new NetworkPermissions.Actor(UUID.randomUUID(), false));
  }

  /** A network owned by {@code owner}. */
  public static Net in(GameTestHelper helper, UUID owner) {
    return new Net(helper, new NetworkPermissions.Actor(owner, false));
  }

  public NetworkPermissions.Actor owner() {
    return owner;
  }

  public GameTestHelper helper() {
    return helper;
  }

  /** A commissioned controller owned by {@link #owner()}. */
  public NetworkNodeEntity controller(BlockPos position) {
    helper.setBlock(position, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(position);
    root.commission(owner.id());
    return root;
  }

  public void cable(BlockPos... positions) {
    for (var position : positions) helper.setBlock(position, NetworkContent.CABLE.get());
  }

  /** A drive with one new cell per tier, installed in order. */
  public StorageDriveEntity drive(BlockPos position, CellTier... cells) {
    helper.setBlock(position, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(position);
    for (var tier : cells) drive.installCell(new ItemStack(StorageContent.cell(tier)));
    return drive;
  }

  /** The handle of the cell in {@code slot} of the drive at {@code position}. */
  public DeviceRegistry.Handle cell(BlockPos position, int slot) {
    StorageDriveEntity drive = helper.getBlockEntity(position);
    return CellHandleData.read(drive.cell(slot));
  }

  /** A placed Buffered Interface leased to {@link #owner()}. */
  public BufferedInterfaceEntity buffer(BlockPos position) {
    helper.setBlock(position, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(position);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), owner.id());
    return buffer;
  }

  public EnergyBankEntity bank(BlockPos position, EnergyBankTier tier) {
    helper.setBlock(position, EnergyBankContent.block(tier));
    EnergyBankEntity bank = helper.getBlockEntity(position);
    bank.place(new ItemStack(EnergyBankContent.item(tier)));
    return bank;
  }

  /** A placed Machine Interface whose target face is {@code facing}. */
  public MachineInterfaceEntity machine(BlockPos position, Direction facing) {
    helper.setBlock(
        position,
        MachineInterfaceContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, facing));
    MachineInterfaceEntity machine = helper.getBlockEntity(position);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    return machine;
  }

  public NodeRegistry.Position at(BlockPos position) {
    return Positions.of(helper.getLevel(), helper.absolutePos(position));
  }

  public DeviceSavedData saved() {
    return DeviceSavedData.get(helper.getLevel().getServer());
  }

  public WorldNetworks runtime() {
    return WorldNetworks.get(helper.getLevel().getServer());
  }

  public ResourceKey key(Item item) {
    return key(new ItemStack(item));
  }

  public ResourceKey key(ItemStack stack) {
    return new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(stack);
  }

  /** Fails (so a {@code thenWaitUntil} retries) until every position is in a valid network. */
  public void assertValid(BlockPos... positions) {
    for (var position : positions)
      helper.assertTrue(
          runtime().membership(at(position)).status() == NetworkTopology.Status.VALID,
          "Network valid at " + position + ": " + runtime().membership(at(position)));
  }
}
