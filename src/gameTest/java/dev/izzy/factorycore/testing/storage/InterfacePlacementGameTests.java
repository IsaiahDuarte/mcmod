package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.InterfaceBlock;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.testing.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** U11 (ADR 0059): one Interface block whose mode follows what it is placed against. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class InterfacePlacementGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      PORT = new BlockPos(2, 1, 1),
      TARGET = new BlockPos(3, 1, 1);

  private InterfacePlacementGameTests() {}

  private static InterfaceKind detectAgainst(
      GameTestHelper h, net.minecraft.world.level.block.Block block) {
    h.setBlock(TARGET, block);
    return InterfaceBlock.detect(h.getLevel(), h.absolutePos(PORT), Direction.EAST);
  }

  @GameTest(template = "empty")
  public static void freshPlacementPicksModeFromTheFacedBlock(GameTestHelper h) {
    h.assertTrue(detectAgainst(h, Blocks.CHEST) == InterfaceKind.STORAGE, "Chest: Storage");
    h.assertTrue(detectAgainst(h, Blocks.BARREL) == InterfaceKind.STORAGE, "Barrel: Storage");
    // H7: a machine never joins storage; machines take Importers, Exporters and cable ports.
    h.assertTrue(detectAgainst(h, Blocks.FURNACE) == InterfaceKind.BUFFER, "Furnace: Buffer");
    h.assertTrue(detectAgainst(h, Blocks.STONE) == InterfaceKind.BUFFER, "Stone: Buffer");
    h.assertTrue(detectAgainst(h, Blocks.AIR) == InterfaceKind.BUFFER, "Nothing: Buffer");
    h.assertTrue(
        InterfaceBlock.carried(new ItemStack(InterfaceContent.ITEM.get())) == null,
        "A fresh item carries no mode");
    h.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void eachKindIsBackedByItsDeviceAndAMovedItemKeepsItsMode(GameTestHelper h) {
    var net = Net.in(h);
    net.controller(ROOT);
    h.setBlock(PORT, InterfaceContent.state(InterfaceKind.STORAGE, Direction.EAST));
    ConnectorEntity storage = h.getBlockEntity(PORT);
    storage.place(new ItemStack(InterfaceContent.ITEM.get()));
    var buffer = net.buffer(new BlockPos(1, 1, 2));
    h.setBlock(
        new BlockPos(1, 1, 3), InterfaceContent.state(InterfaceKind.STORAGE, Direction.EAST));
    h.assertTrue(
        h.getBlockEntity(new BlockPos(1, 1, 3)) instanceof ConnectorEntity connector
            && !(connector instanceof MachineInterfaceEntity),
        "Storage kind: connector entity");
    h.startSequence()
        .thenIdle(2)
        .thenExecute(
            () -> {
              h.assertTrue(
                  InterfaceBlock.carried(storage.portableForBreak()) == InterfaceKind.STORAGE,
                  "A broken Storage Interface carries Storage");
              h.assertTrue(
                  buffer instanceof BufferedInterfaceEntity
                      && InterfaceBlock.carried(buffer.portableForBreak()) == InterfaceKind.BUFFER,
                  "A broken Buffer Interface carries Buffer");
            })
        .thenSucceed();
  }
}
