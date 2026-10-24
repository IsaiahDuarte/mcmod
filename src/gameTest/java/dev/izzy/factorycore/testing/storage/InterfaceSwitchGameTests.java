package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsMenu;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceBlock;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.InterfaceSwitch;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** U11 (ADR 0059): idle-only mode switching retires the old lease and places a fresh device. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class InterfaceSwitchGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), PORT = new BlockPos(2, 1, 1);

  private InterfaceSwitchGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT).above()));
    return player;
  }

  private static InterfaceKind kind(GameTestHelper h) {
    return h.getLevel().getBlockState(h.absolutePos(PORT)).getValue(InterfaceBlock.KIND);
  }

  private static void ready(GameTestHelper h, Net net) {
    net.assertValid(PORT);
  }

  /** The lease id currently placed at {@code PORT}, whichever kind backs it. */
  private static UUID lease(GameTestHelper h) {
    var entity = h.getBlockEntity(PORT);
    return entity instanceof BufferedInterfaceEntity buffer
        ? buffer.handle().id()
        : ((ConnectorEntity) entity).handle().id();
  }

  /** Leases placed at {@code PORT} in the shared, world-wide node table. */
  private static long placedAtPort(GameTestHelper h, DeviceSavedData saved) {
    var at = Positions.of(h.getLevel(), h.absolutePos(PORT));
    return saved.nodes().snapshot().stream().filter(node -> at.equals(node.position())).count();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void idleSwitchesRetireTheOldLeaseAndNeverLeak(GameTestHelper h) {
    var owner = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    var buffer = net.buffer(PORT);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var retired = new ArrayList<UUID>();
    var sequence =
        h.startSequence()
            .thenWaitUntil(() -> ready(h, net))
            .thenExecute(
                () -> {
                  var old = buffer.handle();
                  retired.add(old.id());
                  h.assertTrue(
                      InterfaceSwitch.request(owner, h.absolutePos(PORT), InterfaceKind.MACHINE)
                          == InterfaceSwitch.Outcome.SWITCHED,
                      "An empty idle buffer switches to Machine");
                  h.assertTrue(
                      kind(h) == InterfaceKind.MACHINE
                          && h.getBlockEntity(PORT) instanceof MachineInterfaceEntity,
                      "The block and its entity are now a Machine Interface");
                  h.assertFalse(
                      saved.nodes().contains(old.id()), "The old buffer lease is retired");
                  h.assertTrue(
                      saved.buffers().snapshot().stream()
                          .noneMatch(state -> state.backing().equals(old.id())),
                      "The old buffer inventory record is gone");
                  h.assertTrue(placedAtPort(h, saved) == 1, "One lease out, one lease in");
                })
            .thenWaitUntil(() -> ready(h, net))
            .thenExecute(
                () -> {
                  MachineInterfaceEntity machine = h.getBlockEntity(PORT);
                  machine.deployPreset(
                      net.owner(),
                      MachinePresets.Mode.IMPORT,
                      ResourceFilter.all(ResourceKind.ITEM),
                      64);
                  var old = machine.handle();
                  retired.add(old.id());
                  h.assertTrue(
                      InterfaceSwitch.request(owner, h.absolutePos(PORT), InterfaceKind.STORAGE)
                          == InterfaceSwitch.Outcome.SWITCHED,
                      "A Machine with a preset but nothing in transit switches to Storage");
                  h.assertTrue(
                      saved.machinePresets().get(old.id()) == null
                          && !saved.machinePresets().configurations().containsKey(old.id()),
                      "The old preset and its configuration fence are forgotten");
                  h.assertTrue(
                      h.getBlockEntity(PORT) instanceof ConnectorEntity connector
                          && !(connector instanceof MachineInterfaceEntity),
                      "Now a Storage connector");
                });
    // Each switch rebuilds the network; the next one is checked once it settles.
    for (var target :
        new InterfaceKind[] {
          InterfaceKind.BUFFER, InterfaceKind.STORAGE, InterfaceKind.MACHINE, InterfaceKind.BUFFER
        })
      sequence =
          sequence
              .thenWaitUntil(() -> ready(h, net))
              .thenExecute(
                  () -> {
                    retired.add(lease(h));
                    h.assertTrue(
                        InterfaceSwitch.request(owner, h.absolutePos(PORT), target)
                            == InterfaceSwitch.Outcome.SWITCHED,
                        "Repeated switch to " + target);
                  });
    sequence
        .thenWaitUntil(() -> ready(h, net))
        .thenExecute(
            () -> {
              h.assertTrue(
                  placedAtPort(h, saved) == 1
                      && retired.size() == 6
                      && retired.stream().noneMatch(id -> saved.nodes().contains(id)),
                  "Six switches leave exactly one lease and retire every old one");
              owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void refusalsChangeNothing(GameTestHelper h) {
    var owner = player(h);
    var guest = player(h);
    var net = Net.in(h, owner.getUUID());
    NetworkNodeEntity root = net.controller(ROOT);
    var buffer = net.buffer(PORT);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    saved
        .network(root.handle().id())
        .setGrants(net.owner(), guest.getUUID(), Set.of(NetworkPermissions.Permission.VIEW));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, net))
        .thenExecute(
            () -> {
              var lease = buffer.handle();
              h.assertTrue(
                  InterfaceSwitch.request(guest, h.absolutePos(PORT), InterfaceKind.STORAGE)
                      == InterfaceSwitch.Outcome.DENIED,
                  "VIEW alone cannot switch");
              h.assertTrue(
                  InterfaceSwitch.request(owner, h.absolutePos(PORT), InterfaceKind.BUFFER)
                      == InterfaceSwitch.Outcome.SAME,
                  "Switching to the current mode does nothing");
              var key =
                  new PlatformResourceCodec(h.getLevel().registryAccess())
                      .itemKey(new ItemStack(Items.IRON_INGOT));
              buffer.inventory().insertSlot(0, key, 3, false);
              h.assertTrue(
                  InterfaceSwitch.request(owner, h.absolutePos(PORT), InterfaceKind.STORAGE)
                      == InterfaceSwitch.Outcome.NOT_EMPTY,
                  "A buffer holding items is never switched");
              buffer.inventory().extractSlot(0, 3, false);
              buffer.installThroughput(net.owner(), ThroughputTier.ADVANCED);
              h.assertTrue(
                  InterfaceSwitch.request(owner, h.absolutePos(PORT), InterfaceKind.STORAGE)
                      == InterfaceSwitch.Outcome.UPGRADED,
                  "An upgraded Interface keeps its mode so the upgrade is not lost");
              h.assertTrue(
                  kind(h) == InterfaceKind.BUFFER
                      && saved.nodes().contains(lease.id())
                      && h.getBlockEntity(PORT) == buffer,
                  "Every refusal left the device untouched");
              owner.connection.disconnect(Component.literal("done"));
              guest.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void screenModeButtonSwitchesAndClosesTheScreen(GameTestHelper h) {
    var owner = player(h);
    owner.getInventory().clearContent();
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    h.setBlock(PORT, InterfaceContent.state(InterfaceKind.STORAGE, Direction.EAST));
    ConnectorEntity connector = h.getBlockEntity(PORT);
    connector.place(new ItemStack(InterfaceContent.ITEM.get()));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, net))
        .thenExecute(
            () -> {
              h.useBlock(PORT, owner);
              h.assertTrue(
                  owner.containerMenu instanceof ConnectorSettingsMenu,
                  "Storage Interface screen opened");
              var menu = (ConnectorSettingsMenu) owner.containerMenu;
              h.assertFalse(
                  menu.clickMenuButton(
                      owner, InterfaceSwitch.BUTTON + InterfaceKind.STORAGE.ordinal()),
                  "The current mode's button switches nothing");
              h.assertTrue(
                  menu.clickMenuButton(
                      owner, InterfaceSwitch.BUTTON + InterfaceKind.BUFFER.ordinal()),
                  "The Buffer button switches the Interface");
              h.assertTrue(
                  owner.containerMenu != menu
                      && kind(h) == InterfaceKind.BUFFER
                      && h.getBlockEntity(PORT) instanceof BufferedInterfaceEntity,
                  "The screen closes and the Interface is now a Buffer");
              owner.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
