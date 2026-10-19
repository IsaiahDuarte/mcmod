package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.program.ProgramMenu;
import dev.izzy.factorycore.platform.program.ProgramPackets;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ProgramEditorGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), CONTROLLER = new BlockPos(1, 1, 2);

  private ProgramEditorGameTests() {}

  @SuppressWarnings("removal")
  private static ServerPlayer player(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    return player;
  }

  private static void send(ServerPlayer player, ProgramPackets.Command command) {
    ((ProgramMenu) player.containerMenu).command(player, command);
    player.containerMenu.broadcastChanges();
  }

  private static ProgramMenu menu(GameTestHelper h, ServerPlayer player) {
    h.assertTrue(player.containerMenu instanceof ProgramMenu, "Editor open");
    return (ProgramMenu) player.containerMenu;
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void editorSavesChunkedDraftsReportsErrorsAndControlsDeployment(GameTestHelper h) {
    var owner = player(h);
    var viewer = player(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
    var long_ = new StringBuilder("every 10 ticks as \"long\" {\n");
    while (long_.length() < 5000) long_.append("  log \"padding padding padding\"\n");
    var source = long_.append("}").toString();
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(controller.origin())
                            .status()
                        == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID,
                    "Controller wired"))
        .thenExecute(
            () -> {
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(controller.origin())
                      .scope()
                      .network();
              DeviceSavedData.get(h.getLevel().getServer())
                  .network(network)
                  .setGrants(
                      new NetworkPermissions.Actor(owner.getUUID(), false),
                      viewer.getUUID(),
                      EnumSet.of(NetworkPermissions.Permission.VIEW));
              h.useBlock(CONTROLLER, owner);
              var m = menu(h, owner);
              for (var chunk :
                  ProgramPackets.chunks(m.containerId, m.session(), "every 10 ticks { bogus }"))
                send(owner, chunk);
              h.assertTrue(
                  controller.draft().equals("every 10 ticks { bogus }")
                      && m.view().diagnostics().getFirst().startsWith("1:18"),
                  "Draft saved with a positioned diagnostic: " + m.view().diagnostics());
              var chunks = ProgramPackets.chunks(m.containerId, m.session(), source);
              h.assertTrue(chunks.size() == 3, "A 5,000-character program needs three chunks");
              for (var chunk : chunks) send(owner, chunk);
              send(
                  owner,
                  new ProgramPackets.Command(
                      m.containerId, m.session(), ProgramPackets.DEPLOY, 0, 1, ""));
              h.assertTrue(
                  source.equals(controller.deployedSource())
                      && controller.state() == ProgramControllerEntity.State.RUNNING
                      && m.view().deployed()
                      && m.view().diagnostics().isEmpty(),
                  "Chunked program deployed exactly");
            })
        .thenIdle(3)
        .thenExecute(
            () ->
                send(
                    owner,
                    new ProgramPackets.Command(
                        menu(h, owner).containerId,
                        menu(h, owner).session(),
                        ProgramPackets.PAUSE,
                        0,
                        1,
                        "")))
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(controller.state() == ProgramControllerEntity.State.PAUSED, "Paused");
              send(
                  owner,
                  new ProgramPackets.Command(
                      menu(h, owner).containerId,
                      menu(h, owner).session(),
                      ProgramPackets.RESUME,
                      0,
                      1,
                      ""));
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(controller.state() == ProgramControllerEntity.State.RUNNING, "Resumed");
              send(
                  owner,
                  new ProgramPackets.Command(
                      menu(h, owner).containerId,
                      menu(h, owner).session(),
                      ProgramPackets.STOP,
                      0,
                      1,
                      ""));
              h.assertTrue(
                  controller.deployedSource() == null && controller.draft().equals(source),
                  "Stop removes the deployment and keeps the draft");
              owner.closeContainer();
              h.useBlock(CONTROLLER, viewer);
              var v = menu(h, viewer);
              for (var chunk :
                  ProgramPackets.chunks(
                      v.containerId, v.session(), "every 10 ticks { log \"mine\" }"))
                send(viewer, chunk);
              h.assertTrue(
                  controller.draft().equals(source)
                      && v.view().diagnostics().getFirst().contains("Edit permission"),
                  "A viewer without Edit cannot change the draft");
              send(
                  viewer,
                  new ProgramPackets.Command(
                      v.containerId, v.session(), ProgramPackets.DEPLOY, 0, 1, ""));
              h.assertTrue(
                  controller.deployedSource() == null, "A viewer without Deploy cannot deploy");
              viewer.closeContainer();
              owner.connection.disconnect(Component.literal("done"));
              viewer.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 250)
  public static void authoringPreviewIsViewScopedAndNeverExecutesTheDraft(GameTestHelper h) {
    var owner = player(h);
    var viewer = player(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
    var machinePos = new BlockPos(2, 1, 1);
    h.setBlock(
        machinePos, dev.izzy.factorycore.platform.storage.MachineInterfaceContent.BLOCK.get());
    dev.izzy.factorycore.platform.storage.MachineInterfaceEntity machine =
        h.getBlockEntity(machinePos);
    machine.place(
        new net.minecraft.world.item.ItemStack(
            dev.izzy.factorycore.platform.storage.MachineInterfaceContent.ITEM.get()));
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(controller.origin())
                            .status()
                        == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID,
                    "Controller wired"))
        .thenExecute(
            () ->
                h.assertTrue(
                    machine.relabel(
                            new NetworkPermissions.Actor(owner.getUUID(), false), "fuel input")
                        == dev.izzy.factorycore.core.network.NetworkAuthority.Decision.ALLOWED,
                    "Local label configured"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(controller.origin())
                            .status()
                        == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID,
                    "Relabel published"))
        .thenExecute(
            () -> {
              controller.energyStorage().receiveEnergy(100, false);
              controller.draft(
                  "every 10 ticks { for m in machines(\"fuel input\") { keep m stocked with 16 of item(\"coal\") from storage } craft 4 of item(\"oak_planks\") log \"never emitted\" }");
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(controller.origin())
                      .scope()
                      .network();
              DeviceSavedData.get(h.getLevel().getServer())
                  .network(network)
                  .setGrants(
                      new NetworkPermissions.Actor(owner.getUUID(), false),
                      viewer.getUUID(),
                      EnumSet.of(NetworkPermissions.Permission.VIEW));
              h.useBlock(CONTROLLER, viewer);
              var m = menu(h, viewer);
              m.author(
                  viewer,
                  new ProgramPackets.AuthorRequest(
                      m.containerId, java.util.UUID.randomUUID(), true, 0, ""));
              h.assertTrue(m.authorView() == null, "Forged session reveals no scope or labels");
              m.author(
                  viewer,
                  new ProgramPackets.AuthorRequest(m.containerId, m.session(), true, 0, ""));
              h.assertTrue(
                  m.authorView().labels().equals(java.util.List.of("fuel input")),
                  "VIEW sees permitted local labels");
              h.assertTrue(
                  m.authorView().preview().stream()
                      .anyMatch(line -> line.contains("Keep 16 item minecraft:coal")),
                  "Preview resolves machine target");
              h.assertTrue(
                  m.authorView().preview().stream()
                      .anyMatch(line -> line.contains("Craft 4 minecraft:oak_planks")),
                  "Preview describes craft intent");
              h.assertTrue(
                  !controller.deployed()
                      && controller.energy() == 100
                      && controller.logs().isEmpty(),
                  "Snapshot does not deploy, charge FE or emit logs");
              DeviceSavedData.get(h.getLevel().getServer())
                  .network(network)
                  .setGrants(
                      new NetworkPermissions.Actor(owner.getUUID(), false),
                      viewer.getUUID(),
                      EnumSet.noneOf(NetworkPermissions.Permission.class));
            })
        .thenIdle(6)
        .thenExecute(
            () -> {
              viewer.containerMenu.broadcastChanges();
              // The regular validity path closes the revoked viewer before another snapshot.
              h.assertTrue(
                  !(viewer.containerMenu instanceof ProgramMenu),
                  "Revoked View closes authoring session");
              h.assertTrue(
                  !controller.deployed()
                      && controller.energy() == 100
                      && controller.logs().isEmpty(),
                  "Preview remains inert after later ticks");
              owner.connection.disconnect(Component.literal("done"));
              viewer.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
