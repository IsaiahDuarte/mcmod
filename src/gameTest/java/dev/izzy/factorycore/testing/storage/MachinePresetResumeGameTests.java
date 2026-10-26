package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.platform.storage.MachineSettingsMenu;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * U6(b)/(c): a binding change or upgrade by the preset's own deployer keeps it running; anyone else
 * pauses it, and an authorized player resumes it with one Apply.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachinePresetResumeGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), MACHINE = new BlockPos(2, 1, 1);

  private record Fixture(
      NetworkPermissions.Actor owner,
      MachineInterfaceEntity machine,
      NetworkNodeEntity root,
      DeviceSavedData saved) {}

  private MachinePresetResumeGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    NetworkNodeEntity root = Net.in(h, owner.id()).controller(ROOT);
    MachineInterfaceEntity machine = Net.in(h).machine(MACHINE, Direction.NORTH);
    return new Fixture(owner, machine, root, DeviceSavedData.get(h.getLevel().getServer()));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.machine.position()).status()
            == NetworkTopology.Status.VALID,
        "Machine scope ready");
  }

  private static MachinePresets.Deployment current(Fixture f) {
    return f.saved.machinePresets().get(f.machine.handle().id());
  }

  private static ConnectorSettings rate(int rate) {
    return new ConnectorSettings(
        ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, rate);
  }

  private static void grant(Fixture f, UUID player, NetworkPermissions.Permission... permissions) {
    f.saved.network(f.root.handle().id()).setGrants(f.owner, player, Set.of(permissions));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void deployerEditAndUpgradeKeepPresetRunning(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.deployPreset(
                  f.owner, MachinePresets.Mode.IMPORT, ResourceFilter.all(ResourceKind.ITEM), 64);
              f.machine.configure(f.owner, rate(8));
              h.assertTrue(
                  current(f).mode() == MachinePresets.Mode.IMPORT
                      && current(f).authority().principal().equals(f.owner.id())
                      && f.machine.presetAuthorized(current(f))
                      && f.machine.interruptedMode() == null,
                  "The deployer's own binding change saves and keeps the preset running");
              h.assertTrue(
                  f.machine.installThroughput(f.owner, ThroughputTier.ADVANCED)
                      == NetworkAuthority.Decision.ALLOWED,
                  "The deployer installs an upgrade");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    current(f).mode() == MachinePresets.Mode.IMPORT
                        && f.machine.presetAuthorized(current(f)),
                    "The deployer's own upgrade keeps the preset running once the network settles"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void otherEditorPausesAndResumeNeedsDeployGrants(GameTestHelper h) {
    var f = setup(h);
    var editor = TestPlayers.mock(h);
    TestPlayers.connect(editor);
    editor.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    editor.getInventory().clearContent();
    var actor = new NetworkPermissions.Actor(editor.getUUID(), false);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.deployPreset(
                  f.owner, MachinePresets.Mode.IMPORT, ResourceFilter.all(ResourceKind.ITEM), 64);
              grant(
                  f,
                  editor.getUUID(),
                  NetworkPermissions.Permission.VIEW,
                  NetworkPermissions.Permission.EDIT);
              f.machine.installThroughput(actor, ThroughputTier.ADVANCED);
              h.assertTrue(
                  current(f).mode() == MachinePresets.Mode.PAUSED
                      && f.machine.interruptedMode() == MachinePresets.Mode.IMPORT,
                  "Another player's upgrade pauses the preset and remembers its mode");
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              boolean denied = false;
              try {
                f.machine.resumePreset(actor);
              } catch (IllegalStateException expected) {
                denied = true;
              }
              h.assertTrue(
                  denied && current(f).mode() == MachinePresets.Mode.PAUSED,
                  "Resume without DEPLOY and resource grants is refused");
              grant(
                  f,
                  editor.getUUID(),
                  NetworkPermissions.Permission.VIEW,
                  NetworkPermissions.Permission.EDIT,
                  NetworkPermissions.Permission.DEPLOY,
                  NetworkPermissions.Permission.WITHDRAW,
                  NetworkPermissions.Permission.DEPOSIT);
              h.useBlock(MACHINE, editor);
              h.assertTrue(
                  editor.containerMenu instanceof MachineSettingsMenu,
                  "An empty-handed editor opens the machine screen");
              var menu = (MachineSettingsMenu) editor.containerMenu;
              menu.broadcastChanges();
              h.assertTrue(
                  menu.presetView().status().equals("resumable")
                      && menu.presetView().mode() == MachinePresets.Mode.IMPORT
                      && menu.presetView().dirty(),
                  "The Presets page offers the paused preset back for one Apply");
              menu.presetCommand(
                  editor,
                  new MachinePresetPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.presetView().revision(),
                      MachinePresetPackets.DEPLOY,
                      0,
                      "",
                      new byte[32]));
              h.assertTrue(
                  current(f).mode() == MachinePresets.Mode.IMPORT
                      && current(f).authority().principal().equals(editor.getUUID())
                      && f.machine.interruptedMode() == null,
                  "One Apply resumes the preset in the resuming player's name");
              editor.closeContainer();
              editor.connection.disconnect(Component.literal("Preset resume fixture complete"));
            })
        .thenSucceed();
  }
}
