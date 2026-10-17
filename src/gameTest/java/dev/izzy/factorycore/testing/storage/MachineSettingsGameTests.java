package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.storage.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachineSettingsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), MACHINE = new BlockPos(2, 1, 1);

  private record Fixture(
      ServerPlayer player, MachineInterfaceEntity machine, NetworkNodeEntity root) {}

  private MachineSettingsGameTests() {}

  @SuppressWarnings("removal")
  private static Fixture setup(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    h.setBlock(MACHINE, MachineInterfaceContent.BLOCK.get());
    MachineInterfaceEntity machine = h.getBlockEntity(MACHINE);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    return new Fixture(player, machine, root);
  }

  private static MachineSettingsMenu open(GameTestHelper h, Fixture f) {
    h.useBlock(MACHINE, f.player);
    h.assertTrue(
        f.player.containerMenu instanceof MachineSettingsMenu,
        "Native physical empty-hand interaction opens editor");
    var menu = (MachineSettingsMenu) f.player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  private static MachineSettingsPackets.Form form() {
    return new MachineSettingsPackets.Form(
        "smelter", ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, -7, 2, 1, 8);
  }

  private static void apply(Fixture f, MachineSettingsMenu menu, long generation, UUID session) {
    menu.apply(
        f.player, new MachineSettingsPackets.Apply(menu.containerId, session, generation, form()));
  }

  private static void end(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Machine settings fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void atomicApplyAndCancelLeaveOwnershipAndInitialMenuValid(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = open(h, f);
              h.assertTrue(
                  m.view().editable() && m.view().generation() == f.machine.generation(),
                  "Native initialization retains editor and publishes current form");
              long generation = f.machine.generation();
              f.player.closeContainer();
              h.assertTrue(
                  f.machine.generation() == generation && f.machine.label().isEmpty(),
                  "Closing without Apply does not publish a local draft");
              m = open(h, f);
              apply(f, m, generation, m.session());
              h.assertTrue(
                  f.player.containerMenu != m
                      && f.machine.label().equals("smelter")
                      && f.machine.settings().equals(form().settings())
                      && f.machine.generation() == generation + 1,
                  "One Apply publishes complete label/type/access/window/rate atomically then closes");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void wrongNonceAndStaleConfigurationCannotOverwrite(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = open(h, f);
              long original = f.machine.generation();
              apply(f, m, original, UUID.randomUUID());
              h.assertTrue(
                  f.machine.generation() == original,
                  "Copied menu ID with wrong nonce has no edit authority");
              f.machine.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.WRITE_ONLY, 3, 0, 4, 16));
              apply(f, m, original, m.session());
              h.assertTrue(
                  m.view().status().equals("stale")
                      && m.view().form().rate() == 16
                      && f.machine.label().isEmpty()
                      && f.machine.settings().rate() == 16,
                  "Old form cannot replace external settings; current state reloads");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void rangeLossAndClosedSessionPreventLaterApply(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = open(h, f);
              long generation = f.machine.generation();
              f.player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)).add(20, 0, 0));
              apply(f, m, generation, m.session());
              m.broadcastChanges();
              h.assertTrue(
                  f.player.containerMenu != m && f.machine.generation() == generation,
                  "Physical range loss rejects Apply and closes editor");
              f.player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
              apply(f, m, generation, m.session());
              h.assertTrue(
                  f.machine.generation() == generation,
                  "Closed session stays invalid after returning in range");
              end(f);
            })
        .thenSucceed();
  }

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void editRevocationDeniesSaveAndViewRevocationCloses(GameTestHelper h) {
    var owner = setup(h);
    var guest = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        guest.connection.getConnection());
    guest.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    guest.getInventory().clearContent();
    var f = new Fixture(guest, owner.machine, owner.root);
    var actor = new NetworkPermissions.Actor(owner.player.getUUID(), false);
    var grants = DeviceSavedData.get(guest.server).network(owner.root.handle().id());
    grants.setGrants(
        actor,
        guest.getUUID(),
        Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.EDIT));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(guest), "Granted editor ready"))
        .thenExecute(
            () -> {
              var m = open(h, f);
              long generation = f.machine.generation();
              grants.setGrants(actor, guest.getUUID(), Set.of(NetworkPermissions.Permission.VIEW));
              apply(f, m, generation, m.session());
              h.assertTrue(
                  f.machine.generation() == generation
                      && m.view().status().equals("denied")
                      && !m.view().editable(),
                  "Execution-time EDIT revocation rejects saved form without losing VIEW");
              grants.setGrants(actor, guest.getUUID(), Set.of());
              m.broadcastChanges();
              h.assertTrue(
                  guest.containerMenu != m && f.machine.generation() == generation,
                  "VIEW revocation closes editor without publication");
              end(f);
              end(owner);
            })
        .thenSucceed();
  }
}
