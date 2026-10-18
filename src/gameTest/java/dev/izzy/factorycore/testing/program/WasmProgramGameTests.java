package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.program.ProgramMenu;
import dev.izzy.factorycore.platform.program.ProgramPackets;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WasmProgramGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CONTROLLER = new BlockPos(1, 1, 3),
      MACHINE = new BlockPos(2, 1, 1),
      CHEST = new BlockPos(3, 1, 1);

  private WasmProgramGameTests() {}

  private static byte[] smelter() {
    try {
      return Files.readAllBytes(Path.of(System.getProperty("factorycore.test.smelter")));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 600)
  public static void uploadedRustGuestStocksALabeledMachineAndSurvivesReload(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var owner = new NetworkPermissions.Actor(player.getUUID(), false);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.id());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(0));
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
    h.setBlock(
        MACHINE,
        MachineInterfaceContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    MachineInterfaceEntity machine = h.getBlockEntity(MACHINE);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    h.setBlock(CHEST, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(CHEST);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var ore =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.RAW_IRON));
    var networks = WorldNetworks.get(h.getLevel().getServer());
    Runnable ready =
        () ->
            h.assertTrue(
                networks.membership(controller.origin()).status() == NetworkTopology.Status.VALID
                    && networks.membership(machine.position()).status()
                        == NetworkTopology.Status.VALID
                    && saved.registry().stock(cell, ore).loaded(),
                "Ready");
    h.startSequence()
        .thenWaitUntil(ready)
        .thenExecute(
            () ->
                machine.applySettings(
                    owner,
                    new ConnectorSettings(
                        ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 27, 64),
                    "smelter_in"))
        .thenWaitUntil(ready)
        .thenExecute(
            () -> {
              h.assertTrue(saved.registry().insert(cell, ore, 100).amount() == 100, "Ore stored");
              controller.energyStorage().receiveEnergy(1000, false);
              h.useBlock(CONTROLLER, player);
              var m = (ProgramMenu) player.containerMenu;
              var chunks = ProgramPackets.Artifact.chunks(m.containerId, m.session(), smelter());
              for (var chunk : chunks) m.artifact(player, chunk);
              m.broadcastChanges();
              h.assertTrue(
                  controller.wasmDeployment()
                      && controller.state() == ProgramControllerEntity.State.RUNNING
                      && m.view().diagnostics().isEmpty(),
                  "Uploaded Rust guest deployed: " + m.view().diagnostics());
              var garbage =
                  ProgramPackets.Artifact.chunks(
                      m.containerId, m.session(), new byte[] {0, 97, 115, 109, 9, 9, 9, 9});
              var before = controller.deployment();
              for (var chunk : garbage) m.artifact(player, chunk);
              m.broadcastChanges();
              h.assertTrue(
                  before.equals(controller.deployment())
                      && m.view().diagnostics().getFirst().contains("Wasm rejected"),
                  "An invalid artifact is rejected without replacing the deployment");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    chestCount(chest) == 64,
                    "Guest kept the machine at 64 raw iron: " + controller.logs()))
        .thenExecute(
            () -> {
              h.assertTrue(
                  saved.registry().stock(cell, ore).total() == 36 && controller.energy() < 1000,
                  "Exactly the deficit moved and FE was charged per instruction");
              var tag = controller.saveWithoutMetadata(h.getLevel().registryAccess());
              player.closeContainer();
              h.setBlock(CONTROLLER, Blocks.AIR);
              h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
              ProgramControllerEntity reloaded = h.getBlockEntity(CONTROLLER);
              reloaded.loadWithComponents(tag, h.getLevel().registryAccess());
              h.assertTrue(
                  reloaded.wasmDeployment()
                      && reloaded.wasm() != null
                      && !reloaded.wasm().failed()
                      && reloaded.wasm().rules().size() == 2
                      && owner.id().equals(reloaded.deployer()),
                  "Reload re-instantiates a fresh guest from the persisted artifact");
              player.connection.disconnect(Component.literal("done"));
            })
        // Replacing the block rebuilds the network; lifecycle actions need a VALID controller.
        .thenWaitUntil(
            () -> {
              ProgramControllerEntity reloaded = h.getBlockEntity(CONTROLLER);
              h.assertTrue(
                  WorldPrograms.get(h.getLevel().getServer())
                          .control(owner, reloaded, ProgramControllerEntity.State.STOPPED)
                      && !reloaded.deployed(),
                  "Stop removes the Wasm deployment");
            })
        .thenSucceed();
  }

  private static int chestCount(ChestBlockEntity chest) {
    int total = 0;
    for (int slot = 0; slot < chest.getContainerSize(); slot++)
      if (chest.getItem(slot).is(Items.RAW_IRON)) total += chest.getItem(slot).getCount();
    return total;
  }
}
