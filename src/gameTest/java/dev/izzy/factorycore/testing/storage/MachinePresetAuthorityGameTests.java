package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.storage.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachinePresetAuthorityGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), MACHINE = new BlockPos(2, 1, 1);

  private record Fixture(
      NetworkPermissions.Actor owner,
      MachineInterfaceEntity machine,
      NetworkNodeEntity root,
      DeviceSavedData saved) {}

  private MachinePresetAuthorityGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(actor.id());
    h.setBlock(MACHINE, MachineInterfaceContent.BLOCK.get());
    MachineInterfaceEntity machine = h.getBlockEntity(MACHINE);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    return new Fixture(actor, machine, root, DeviceSavedData.get(h.getLevel().getServer()));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(f.machine.position()).status()
            == NetworkTopology.Status.VALID,
        "Machine scope ready");
  }

  private static MachinePresets.Deployment deploy(Fixture f, NetworkPermissions.Actor actor) {
    return f.machine.deployPreset(
        actor, MachinePresets.Mode.IMPORT, ResourceFilter.all(ResourceKind.ITEM), 64);
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void configurationEditAndOldChunkCannotResumeCanonicalDeployment(GameTestHelper h) {
    var f = setup(h);
    MachinePresets.Deployment[] accepted = {null};
    CompoundTag[] oldChunk = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              accepted[0] = deploy(f, f.owner);
              h.assertTrue(
                  f.machine.presetAuthorized(accepted[0]),
                  "Current original principal/scope/configuration authorizes deployment metadata");
              oldChunk[0] = f.machine.saveWithFullMetadata(h.getLevel().registryAccess());
              f.machine.configure(
                  f.owner,
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 1, 8));
              h.assertTrue(
                  f.saved.machinePresets().get(f.machine.handle().id()).mode()
                          == MachinePresets.Mode.PAUSED
                      && !f.machine.presetAuthorized(accepted[0]),
                  "Port edit pauses canonical deployment before any future scheduled work");
              f.machine.loadWithComponents(oldChunk[0], h.getLevel().registryAccess());
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.machine.generation() == accepted[0].configuration()
                        && !f.machine.presetAuthorized(accepted[0])
                        && f.saved.machinePresets().get(f.machine.handle().id()).mode()
                            == MachinePresets.Mode.PAUSED,
                    "Replayed old chunk configuration cannot resume canonical paused authority"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void revocationRegrantAndReplacementNeverAdoptAnOldPrincipal(GameTestHelper h) {
    var f = setup(h);
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var grants = f.saved.network(f.root.handle().id());
    var all =
        Set.of(
            NetworkPermissions.Permission.VIEW,
            NetworkPermissions.Permission.EDIT,
            NetworkPermissions.Permission.DEPLOY,
            NetworkPermissions.Permission.WITHDRAW,
            NetworkPermissions.Permission.DEPOSIT);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              grants.setGrants(f.owner, guest.id(), all);
              var original = deploy(f, guest);
              h.assertTrue(
                  f.machine.presetAuthorized(original),
                  "Explicit current guest deployment retains its own principal");
              grants.setGrants(
                  f.owner,
                  guest.id(),
                  Set.of(
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.WITHDRAW));
              h.assertTrue(
                  !f.machine.presetAuthorized(original),
                  "Revocation invalidates saved deployment before mutation");
              grants.setGrants(f.owner, guest.id(), all);
              h.assertTrue(
                  !f.machine.presetAuthorized(original),
                  "Regrant does not silently reactivate an old grant generation");
              var renewed = deploy(f, guest);
              h.assertTrue(
                  f.machine.presetAuthorized(renewed),
                  "Explicit redeploy accepts renewed grant generation");
              var replaced = deploy(f, f.owner);
              h.assertTrue(
                  !f.machine.presetAuthorized(renewed)
                      && f.machine.presetAuthorized(replaced)
                      && replaced.authority().principal().equals(f.owner.id()),
                  "Replacement never adopts the old principal/revision");
              var forged =
                  new MachinePresets.Deployment(
                      replaced.node(),
                      replaced.leaseGeneration(),
                      replaced.revision(),
                      replaced.configuration(),
                      replaced.face(),
                      replaced.authority(),
                      MachinePresets.Mode.STOCK,
                      replaced.filter(),
                      1);
              h.assertTrue(
                  !f.machine.presetAuthorized(forged),
                  "Same revision cannot authorize a different mode/goal");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void rootReplacementDoesNotRebindTheSavedProgram(GameTestHelper h) {
    var f = setup(h);
    MachinePresets.Deployment[] original = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              original[0] = deploy(f, f.owner);
              h.setBlock(ROOT, Blocks.AIR);
              h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
              NetworkNodeEntity replacement = h.getBlockEntity(ROOT);
              replacement.commission(f.owner.id());
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () ->
                h.assertTrue(
                    !f.machine.presetAuthorized(original[0])
                        && f.saved
                            .machinePresets()
                            .get(f.machine.handle().id())
                            .equals(original[0]),
                    "Same owner/new root cannot silently rebind saved machine authority"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void rotationRejectsOldFaceAndExplicitRedeployBindsNewFace(GameTestHelper h) {
    var f = setup(h);
    MachinePresets.Deployment[] old = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              old[0] = deploy(f, f.owner);
              h.setBlock(
                  MACHINE,
                  f.machine.getBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.machine.generation() == old[0].configuration()
                      && !f.machine.presetAuthorized(old[0]),
                  "Changed provider face invalidates intent even with unchanged port generation");
              var next = deploy(f, f.owner);
              h.assertTrue(
                  next.face() == MachinePresets.Face.EAST && f.machine.presetAuthorized(next),
                  "Explicit redeployment captures the new provider face");
            })
        .thenSucceed();
  }
}
