package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.network.DeviceLamp;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveBlock;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Status lamps and drive bays are presentation mirrors of authoritative state: they follow it
 * within a bounded refresh period and changing them never disturbs network topology.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class PresentationStateGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(3, 1, 1);
  private static final BlockPos PROGRAM = new BlockPos(2, 1, 2);
  private static final BlockPos LONE_GATEWAY = new BlockPos(1, 1, 4);
  private static final BlockPos LINK = new BlockPos(4, 1, 4);

  private PresentationStateGameTests() {}

  private static NetworkTopology.Status status(GameTestHelper helper, BlockPos relative) {
    return WorldNetworks.get(helper.getLevel().getServer())
        .membership(
            new NodeRegistry.Position(
                helper.getLevel().dimension().location().toString(),
                helper.absolutePos(relative).asLong()))
        .status();
  }

  private static DeviceLamp lamp(GameTestHelper helper, BlockPos relative) {
    return helper.getBlockState(relative).getValue(DeviceLamp.PROPERTY);
  }

  private static boolean bay(GameTestHelper helper, int slot) {
    return helper.getBlockState(DRIVE).getValue(StorageDriveBlock.BAYS.get(slot));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void lampsAndBaysMirrorDeviceState(GameTestHelper helper) {
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    ((NetworkNodeEntity) helper.getBlockEntity(ROOT)).commission(UUID.randomUUID());
    helper.setBlock(CABLE, NetworkContent.CABLE.get());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    helper.setBlock(PROGRAM, ProgramContent.BLOCK.get());
    helper.setBlock(LONE_GATEWAY, NetworkContent.GATEWAY.get());
    ((NetworkNodeEntity) helper.getBlockEntity(LONE_GATEWAY)).commission(null);
    helper.setBlock(LINK, WirelessContent.LINK.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    helper.assertTrue(
        lamp(helper, ROOT) == DeviceLamp.OFF && !bay(helper, 0), "Placement starts idle and empty");
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
    helper.assertTrue(
        bay(helper, 0) && bay(helper, 1) && !bay(helper, 2) && !bay(helper, 3),
        "Installed cells light exactly their bays immediately");
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    status(helper, DRIVE) == NetworkTopology.Status.VALID
                        && lamp(helper, ROOT) == DeviceLamp.ACTIVE,
                    "Valid root lights its lamp within the refresh period"))
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    lamp(helper, LONE_GATEWAY) == DeviceLamp.FAULT,
                    "Gateway without a root shows the fault lamp: " + status(helper, LONE_GATEWAY)))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  lamp(helper, PROGRAM) == DeviceLamp.OFF, "Undeployed program stays dark");
              helper.assertTrue(lamp(helper, LINK) == DeviceLamp.OFF, "Unpaired link stays dark");
              // A cosmetic lamp change must not schedule a rebuild of the valid network.
              DeviceLamp.show(
                  helper.getLevel(),
                  helper.absolutePos(ROOT),
                  helper.getBlockState(ROOT),
                  DeviceLamp.FAULT);
              helper.assertTrue(
                  lamp(helper, ROOT) == DeviceLamp.FAULT
                      && status(helper, DRIVE) == NetworkTopology.Status.VALID,
                  "Lamp change kept membership VALID: " + status(helper, DRIVE));
              helper.assertTrue(
                  !drive.removeCell(0).isEmpty() && !bay(helper, 0) && bay(helper, 1),
                  "Removing a cell clears only its bay");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    lamp(helper, ROOT) == DeviceLamp.ACTIVE,
                    "Server refresh restores the authoritative lamp"))
        .thenSucceed();
  }
}
