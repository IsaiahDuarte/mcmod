package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.PortContent;
import dev.izzy.factorycore.testing.ConnectorFixture;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * Q136 (n21, ADR 0013 amendment): Importers and Exporters work on a modded inventory that is
 * neither a vanilla container nor describes its backing (here a 100-slot test block, as a
 * Sophisticated Storage chest is), taking it as one block, with exact accounting.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ModdedProviderGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 2, 1),
      DRIVE = new BlockPos(1, 2, 2),
      PORT = new BlockPos(2, 2, 1),
      TARGET = new BlockPos(3, 2, 1);

  private ModdedProviderGameTests() {}

  private static MachineInterfaceEntity place(
      GameTestHelper h, DeferredBlock<?> block, ServerPlayer placer) {
    h.setBlock(
        PORT,
        block.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
    var position = h.absolutePos(PORT);
    var state = h.getLevel().getBlockState(position);
    state.getBlock().setPlacedBy(h.getLevel(), position, state, placer, new ItemStack(block.get()));
    return h.getBlockEntity(PORT);
  }

  private static int count(ConnectorFixture.WideEntity chest, net.minecraft.world.item.Item item) {
    int total = 0;
    for (int slot = 0; slot < chest.handler.getSlots(); slot++)
      if (chest.handler.getStackInSlot(slot).is(item))
        total += chest.handler.getStackInSlot(slot).getCount();
    return total;
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void importersAndExportersWorkOnAnUndescribedModdedInventory(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = net.cell(DRIVE, 0);
    h.setBlock(TARGET, ConnectorFixture.WIDE.get());
    ConnectorFixture.WideEntity chest = h.getBlockEntity(TARGET);
    chest.handler.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 40));
    // Within a port's 64-slot window; slots past it are Q145.
    chest.handler.setStackInSlot(63, new ItemStack(Items.DIRT, 64));
    var importer = place(h, PortContent.IMPORTER, owner);
    var registry = net.saved().registry();
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    count(chest, Items.IRON_INGOT) == 0
                        && count(chest, Items.DIRT) == 0
                        && registry.stock(cell, net.key(Items.IRON_INGOT)).total() == 40
                        && registry.stock(cell, net.key(Items.DIRT)).total() == 64,
                    "The Importer empties the modded inventory into storage exactly: "
                        + net.runtime().machinePresets().status(importer)
                        + " chest iron="
                        + count(chest, Items.IRON_INGOT)
                        + " dirt="
                        + count(chest, Items.DIRT)
                        + " stored iron="
                        + registry.stock(cell, net.key(Items.IRON_INGOT)).total()
                        + " "
                        + importer.diagnostic()))
        .thenExecute(() -> place(h, PortContent.EXPORTER, owner))
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              MachineInterfaceEntity exporter = h.getBlockEntity(PORT);
              exporter.deployPreset(
                  Actors.of(owner),
                  MachinePresets.Mode.STOCK,
                  new ResourceFilter(
                      ResourceKind.ITEM,
                      ResourceFilter.Mode.INCLUDE,
                      List.of(new ResourceFilter.Exact(net.key(Items.IRON_INGOT)))),
                  8);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    count(chest, Items.IRON_INGOT) == 8
                        && registry.stock(cell, net.key(Items.IRON_INGOT)).total() == 32,
                    "An Exporter keeps eight in it, and storage loses exactly those"))
        .thenIdle(40)
        .thenExecute(
            () -> {
              h.assertTrue(
                  count(chest, Items.IRON_INGOT) == 8
                      && registry.stock(cell, net.key(Items.IRON_INGOT)).total() == 32,
                  "The stock holds");
              owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
