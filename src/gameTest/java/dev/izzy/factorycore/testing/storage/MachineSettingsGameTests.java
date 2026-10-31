package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachineSettingsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), MACHINE = new BlockPos(2, 1, 1);

  private record Fixture(
      ServerPlayer player, MachineInterfaceEntity machine, NetworkNodeEntity root) {}

  private MachineSettingsGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    MachineInterfaceEntity machine = Net.in(h).machine(MACHINE, Direction.NORTH);
    return new Fixture(player, machine, root);
  }

  /**
   * Holds until the machine's network scope has stayed the same for {@code SETTLE} ticks. A port's
   * network can rebuild again just after it first becomes valid; an editor opened before then goes
   * stale and drops later commands. When that happens depends on server load.
   */
  private static void settled(GameTestHelper h, Fixture f, Object[] last, int[] same) {
    h.assertTrue(f.machine.canUse(f.player), "Wired owner ready");
    var scope = WorldNetworks.get(f.player.server).membership(f.machine.position()).scope();
    if (Objects.equals(scope, last[0])) same[0]++;
    else {
      last[0] = scope;
      same[0] = 0;
    }
    h.assertTrue(same[0] >= SETTLE, "Network settled");
  }

  private static final int SETTLE = 10;

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
                  m.view().status() == MachineSettingsPackets.Status.STALE
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

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void editRevocationDeniesSaveAndViewRevocationCloses(GameTestHelper h) {
    var owner = setup(h);
    var guest = TestPlayers.mock(h);
    TestPlayers.connect(guest);
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
                      && m.view().status() == MachineSettingsPackets.Status.DENIED
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

  private static void preset(Fixture f, MachineSettingsMenu m, int action, String text) {
    byte[] digest = new byte[32];
    var stack = f.player.getInventory().getItem(9);
    if (!stack.isEmpty())
      digest =
          TerminalPackets.fingerprint(
              new PlatformResourceCodec(f.player.registryAccess()).itemKey(stack));
    m.presetCommand(
        f.player,
        new MachinePresetPackets.Command(
            m.containerId, m.session(), m.presetView().revision(), action, 9, text, digest));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void presetDraftSamplesDoNotDeployOrConsumeUntilExplicitSave(GameTestHelper h) {
    var f = setup(h);
    MachineSettingsMenu[] menu = {null};
    Object[] scope = {null};
    int[] same = {0};
    h.startSequence()
        .thenWaitUntil(() -> settled(h, f, scope, same))
        .thenExecute(
            () -> {
              menu[0] = open(h, f);
              f.player.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 7));
              preset(f, menu[0], MachinePresetPackets.SAMPLE, "");
              h.assertTrue(
                  menu[0].presetView().dirty()
                      && menu[0].presetView().rules().size() == 1
                      && f.player.getInventory().getItem(9).getCount() == 7
                      && DeviceSavedData.get(f.player.server)
                              .machinePresets()
                              .get(f.machine.handle().id())
                          == null,
                  "Authenticated exact sample changes only the server draft and consumes nothing");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              preset(f, menu[0], MachinePresetPackets.MODE, "");
              h.assertTrue(
                  menu[0].presetView().mode() == MachinePresets.Mode.IMPORT,
                  "Mode remains an unsaved draft");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              preset(f, menu[0], MachinePresetPackets.DEPLOY, "8");
              var saved =
                  DeviceSavedData.get(f.player.server)
                      .machinePresets()
                      .get(f.machine.handle().id());
              h.assertTrue(
                  saved != null
                      && saved.target() == 8
                      && saved.filter().terms().size() == 1
                      && saved.authority().principal().equals(f.player.getUUID())
                      && menu[0].presetView().status() == MachinePresetPackets.Status.APPLIED
                      && !menu[0].presetView().dirty(),
                  "Explicit deploy saves exact filter and typed goal under authenticated principal");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void presetBadGoalAndStaleNonceRevisionCannotPublish(GameTestHelper h) {
    var f = setup(h);
    MachineSettingsMenu[] menu = {null};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = menu[0] = open(h, f);
              m.presetCommand(
                  f.player,
                  new MachinePresetPackets.Command(
                      m.containerId,
                      UUID.randomUUID(),
                      m.presetView().revision(),
                      MachinePresetPackets.MODE,
                      0,
                      "",
                      new byte[32]));
              h.assertTrue(!m.presetView().dirty(), "Wrong nonce does not mutate draft");
              preset(f, m, MachinePresetPackets.GOAL, "-1");
              h.assertTrue(
                  m.presetView().goal() == 64
                      && m.presetView().status() == MachinePresetPackets.Status.INVALID,
                  "Negative goal leaves draft unchanged");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu[0];
              m.presetCommand(
                  f.player,
                  new MachinePresetPackets.Command(
                      m.containerId,
                      m.session(),
                      m.presetView().revision() - 1,
                      MachinePresetPackets.DEPLOY,
                      0,
                      "8",
                      new byte[32]));
              h.assertTrue(
                  m.presetView().status() == MachinePresetPackets.Status.STALE
                      && DeviceSavedData.get(f.player.server)
                              .machinePresets()
                              .get(f.machine.handle().id())
                          == null,
                  "Stale revision cannot deploy");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var m = menu[0];
              f.machine.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 1, 8));
              preset(f, m, MachinePresetPackets.DEPLOY, "8");
              h.assertTrue(
                  m.presetView().status() == MachinePresetPackets.Status.STALE
                      && !m.presetView().dirty()
                      && DeviceSavedData.get(f.player.server)
                              .machinePresets()
                              .get(f.machine.handle().id())
                          == null,
                  "Changed physical configuration reloads draft before accepting deployment");
              end(f);
            })
        .thenSucceed();
  }

  private static void cycle(Fixture f, MachineSettingsMenu m, String shown) {
    m.presetCommand(
        f.player,
        new MachinePresetPackets.Command(
            m.containerId,
            m.session(),
            m.presetView().revision(),
            MachinePresetPackets.CYCLE,
            0,
            shown,
            new byte[32]));
  }

  /**
   * B1 on a fluid preset: a held water bucket adds water as an Item term (the bucket stays); the
   * slot then cycles Item → Exact → the fluid's tag(s) → Item.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void fluidGhostSlotCyclesEveryMatch(GameTestHelper h) {
    var f = setup(h);
    MachineSettingsMenu[] menu = {null};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              f.machine.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  new ConnectorSettings(
                      ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 1000));
              menu[0] = open(h, f);
              menu[0].setCarried(new ItemStack(Items.WATER_BUCKET));
              preset(f, menu[0], MachinePresetPackets.CARRIED, "");
              var rule = menu[0].presetView().rules().getFirst();
              h.assertTrue(
                  rule.type() == FilterEdits.ITEM
                      && rule.icon().equals("minecraft:water")
                      && menu[0].getCarried().is(Items.WATER_BUCKET),
                  "A held bucket adds its fluid: " + rule);
              menu[0].setCarried(ItemStack.EMPTY);
            })
        .thenIdle(3)
        .thenExecute(() -> cycle(f, menu[0], "minecraft:water"))
        .thenIdle(3)
        .thenExecute(
            () -> {
              h.assertTrue(
                  menu[0].presetView().rules().getFirst().type() == FilterEdits.EXACT,
                  "Item cycles to Exact");
              cycle(f, menu[0], "minecraft:water");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var rule = menu[0].presetView().rules().getFirst();
              h.assertTrue(
                  rule.type() == FilterEdits.TAG && rule.label().contains("water"),
                  "Exact cycles to the fluid tag: " + rule);
            })
        .thenWaitUntil(
            () -> {
              // One cycle every few ticks (the session's command rate gate) until it is Item again.
              var rule = menu[0].presetView().rules().getFirst();
              if (rule.type() != FilterEdits.ITEM) {
                if (h.getTick() % 4 == 0) cycle(f, menu[0], rule.icon());
                h.fail("Still on " + rule);
              }
            })
        .thenExecute(() -> end(f))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void presetUtilitySamplesUseCopiedContainerAndCanonicalEnergy(GameTestHelper h) {
    var f = setup(h);
    MachineSettingsMenu[] menu = {null};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              f.machine.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  new ConnectorSettings(
                      ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 1000));
              f.player.getInventory().setItem(9, new ItemStack(Items.WATER_BUCKET));
              menu[0] = open(h, f);
              preset(f, menu[0], MachinePresetPackets.SAMPLE, "");
              h.assertTrue(
                  menu[0].presetView().rules().getFirst().label().contains("minecraft:water")
                      && f.player.getInventory().getItem(9).is(Items.WATER_BUCKET),
                  "Fluid sample copies contained water without draining original bucket");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              f.machine.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  new ConnectorSettings(
                      ResourceKind.ENERGY, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 1000));
              preset(f, menu[0], MachinePresetPackets.SAMPLE, "");
              h.assertTrue(
                  menu[0].presetView().status() == MachinePresetPackets.Status.STALE,
                  "Kind change discards incompatible fluid draft");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              f.player.getInventory().clearContent();
              preset(f, menu[0], MachinePresetPackets.SAMPLE, "");
              h.assertTrue(
                  menu[0].presetView().rules().size() == 1
                      && menu[0]
                          .presetView()
                          .rules()
                          .getFirst()
                          .label()
                          .contains(ResourceKey.ENERGY.registryId()),
                  "FE sample uses canonical energy identity without an inventory item");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              preset(f, menu[0], MachinePresetPackets.TAG, "minecraft:water");
              h.assertTrue(
                  menu[0].presetView().status() == MachinePresetPackets.Status.INVALID
                      && menu[0].presetView().rules().size() == 1,
                  "Unsupported energy tags reject without changing valid draft");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void presetDeploymentRechecksCurrentDeployGrant(GameTestHelper h) {
    var owner = setup(h);
    var guest = TestPlayers.mock(h);
    TestPlayers.connect(guest);
    guest.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    guest.getInventory().clearContent();
    var f = new Fixture(guest, owner.machine, owner.root);
    var actor = new NetworkPermissions.Actor(owner.player.getUUID(), false);
    var grants = DeviceSavedData.get(guest.server).network(owner.root.handle().id());
    grants.setGrants(actor, guest.getUUID(), EnumSet.allOf(NetworkPermissions.Permission.class));
    MachineSettingsMenu[] menu = {null};
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.machine.canUse(guest), "Granted editor ready"))
        .thenExecute(
            () -> {
              menu[0] = open(h, f);
              preset(f, menu[0], MachinePresetPackets.MODE, "");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              grants.setGrants(
                  actor,
                  guest.getUUID(),
                  Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.EDIT));
              preset(f, menu[0], MachinePresetPackets.DEPLOY, "8");
              h.assertTrue(
                  menu[0].presetView().status() == MachinePresetPackets.Status.DENIED
                      && !menu[0].presetView().editable()
                      && DeviceSavedData.get(guest.server)
                              .machinePresets()
                              .get(f.machine.handle().id())
                          == null,
                  "Revoked DEPLOY blocks publication while retaining view and draft");
              end(f);
              end(owner);
            })
        .thenSucceed();
  }
}
