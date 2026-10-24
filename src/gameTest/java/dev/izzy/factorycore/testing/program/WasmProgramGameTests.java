package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.program.ProgramMenu;
import dev.izzy.factorycore.platform.program.ProgramPackets;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void uploadedRustGuestStocksALabeledMachineAndSurvivesReload(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var owner = new NetworkPermissions.Actor(player.getUUID(), false);
    NetworkNodeEntity root = Net.in(h, owner.id()).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    var cell = CellHandleData.read(drive.cell(0));
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
    h.setBlock(MACHINE, InterfaceContent.state(InterfaceKind.MACHINE, Direction.EAST));
    MachineInterfaceEntity machine = h.getBlockEntity(MACHINE);
    machine.place(new ItemStack(InterfaceContent.ITEM.get()));
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

  private static long count(byte[] state) {
    return state == null ? 0 : ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getLong();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void guestStatePersistsAcrossReloadAndResetsOnRedeploy(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    var owner = new NetworkPermissions.Actor(player.getUUID(), false);
    NetworkNodeEntity root = Net.in(h, owner.id()).controller(ROOT);
    h.setBlock(DRIVE, ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(DRIVE);
    byte[] counter;
    try {
      counter = Files.readAllBytes(Path.of(System.getProperty("factorycore.test.counter")));
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
    var programs = WorldPrograms.get(h.getLevel().getServer());
    var networks = WorldNetworks.get(h.getLevel().getServer());
    long[] before = new long[1];
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    networks.membership(controller.origin()).status()
                        == NetworkTopology.Status.VALID,
                    "Ready"))
        .thenExecute(
            () -> {
              controller.energyStorage().receiveEnergy(1_000, false);
              h.assertTrue(
                  programs.deployWasm(owner, controller, counter).isEmpty()
                      && controller.guestState() == null,
                  "Counter guest deploys with no state");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    count(controller.guestState()) >= 2 && controller.logs().contains("count: 2"),
                    "Each callback commits its count: " + controller.logs()))
        .thenExecute(
            () -> {
              before[0] = count(controller.guestState());
              var tag = controller.saveWithoutMetadata(h.getLevel().registryAccess());
              h.assertTrue(tag.contains("GuestState"), "Schema 3 persists GuestState");
              h.setBlock(DRIVE, Blocks.AIR);
              h.setBlock(DRIVE, ProgramContent.BLOCK.get());
              ProgramControllerEntity reloaded = h.getBlockEntity(DRIVE);
              reloaded.loadWithComponents(tag, h.getLevel().registryAccess());
              h.assertTrue(
                  count(reloaded.guestState()) == before[0]
                      && reloaded.wasm() != null
                      && count(reloaded.wasm().state()) == before[0],
                  "Reload restores committed state into a fresh instance");
            })
        .thenWaitUntil(
            () -> {
              ProgramControllerEntity reloaded = h.getBlockEntity(DRIVE);
              h.assertTrue(
                  reloaded.logs().contains("count: " + (before[0] + 1)),
                  "The reloaded guest continues from the saved count: " + reloaded.logs());
            })
        .thenExecute(
            () -> {
              ProgramControllerEntity reloaded = h.getBlockEntity(DRIVE);
              h.assertTrue(
                  programs.deployWasm(owner, reloaded, counter).isEmpty()
                      && reloaded.guestState() == null,
                  "Redeploying starts a new deployment without state");
            })
        .thenWaitUntil(
            () -> {
              ProgramControllerEntity reloaded = h.getBlockEntity(DRIVE);
              h.assertTrue(
                  reloaded.logs().contains("count: 1") && count(reloaded.guestState()) >= 1,
                  "The new deployment counts from one: " + reloaded.logs());
              programs.control(owner, reloaded, ProgramControllerEntity.State.STOPPED);
              player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
