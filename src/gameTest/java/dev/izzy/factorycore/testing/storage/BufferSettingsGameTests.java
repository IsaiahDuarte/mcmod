package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.storage.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferSettingsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), BUFFER = new BlockPos(2, 1, 1);

  private record Fixture(ServerPlayer player, BufferedInterfaceEntity buffer) {}

  private BufferSettingsGameTests() {}

  @SuppressWarnings("removal")
  private static Fixture setup(GameTestHelper helper) {
    var player = helper.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    player.getInventory().clearContent();
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), player.getUUID());
    return new Fixture(player, buffer);
  }

  private static BufferSettingsMenu menu(Fixture f) {
    return (BufferSettingsMenu) f.player.containerMenu;
  }

  private static void open(GameTestHelper helper, Fixture f) {
    helper.useBlock(BUFFER, f.player);
    helper.assertTrue(
        f.player.containerMenu instanceof BufferSettingsMenu,
        "Physical interaction opens authenticated native settings");
    menu(f).broadcastChanges();
  }

  private static void command(
      Fixture f, int action, int selection, int value, String text, byte[] hash) {
    var m = menu(f);
    m.command(
        f.player,
        new BufferSettingsPackets.Command(
            m.containerId, m.session(), m.view().revision(), action, selection, value, text, hash));
  }

  private static void end(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Buffer settings fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void draftApplyResetAndExternalChange(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> helper.assertTrue(f.buffer.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              open(helper, f);
              command(f, BufferSettingsPackets.RATE, 0, 8, "", new byte[32]);
              helper.assertTrue(
                  menu(f).view().dirty() && menu(f).view().rate() == 8, "Editable draft projected");
              helper.assertTrue(
                  DeviceSavedData.get(f.player.server).buffers().deployment(f.buffer.handle().id())
                      == null,
                  "Draft does not deploy or persist");
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              command(f, BufferSettingsPackets.APPLY, 0, 0, "", new byte[32]);
              helper.assertTrue(
                  DeviceSavedData.get(f.player.server)
                          .buffers()
                          .deployment(f.buffer.handle().id())
                          .policy()
                          .rate()
                      == 8,
                  "Apply publishes canonical policy");
            })
        .thenIdle(2)
        .thenExecute(() -> command(f, BufferSettingsPackets.TARGET, 0, 16, "", new byte[32]))
        .thenIdle(2)
        .thenExecute(
            () -> {
              command(f, BufferSettingsPackets.RESET, 0, 0, "", new byte[32]);
              helper.assertTrue(
                  menu(f).view().target() == 64 && !menu(f).view().dirty(), "Reset discards draft");
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              f.buffer.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  BufferRegistry.Mode.PAUSED,
                  new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 4, 32));
              command(f, BufferSettingsPackets.APPLY, 0, 0, "", new byte[32]);
              helper.assertTrue(
                  menu(f).view().status().equals("stale") && menu(f).view().rate() == 4,
                  "External edits invalidate old draft before Apply");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void exactSampleNonceAndStaleInventorySelection(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> helper.assertTrue(f.buffer.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              open(helper, f);
              f.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
              var key =
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                          f.player.registryAccess())
                      .itemKey(f.player.getInventory().getItem(0));
              var m = menu(f);
              m.command(
                  f.player,
                  new BufferSettingsPackets.Command(
                      m.containerId,
                      UUID.randomUUID(),
                      m.view().revision(),
                      BufferSettingsPackets.MODE,
                      0,
                      0,
                      "",
                      new byte[32]));
              helper.assertTrue(!m.view().dirty(), "Wrong nonce has no authority");
              command(f, BufferSettingsPackets.EXACT, 0, 0, "", TerminalPackets.fingerprint(key));
              helper.assertTrue(
                  m.view().rules().size() == 1
                      && f.player.getInventory().getItem(0).getCount() == 3,
                  "Exact ghost sample preserves owned items");
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(f);
              long old = m.view().revision();
              var codec =
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                      f.player.registryAccess());
              f.buffer
                  .inventory()
                  .insertSlot(0, codec.itemKey(new ItemStack(Items.GOLD_INGOT)), 2, false);
              m.command(
                  f.player,
                  new BufferSettingsPackets.Command(
                      m.containerId,
                      m.session(),
                      old,
                      BufferSettingsPackets.TAKE,
                      0,
                      1,
                      "",
                      new byte[32]));
              helper.assertTrue(
                  !m.view().busy() && f.player.getInventory().getItem(0).getCount() == 3,
                  "Old empty projection cannot select newly inserted key");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void scheduledBufferWithdrawDepositConserves(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> helper.assertTrue(f.buffer.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var codec =
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                      f.player.registryAccess());
              f.buffer
                  .inventory()
                  .insertSlot(0, codec.itemKey(new ItemStack(Items.IRON_INGOT)), 7, false);
              open(helper, f);
              command(f, BufferSettingsPackets.TAKE, 0, 3, "", new byte[32]);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.player.getInventory().getItem(0).getCount() == 3,
                    "Scheduled withdrawal delivered"))
        .thenIdle(2)
        .thenExecute(
            () -> {
              var key =
                  new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                          f.player.registryAccess())
                      .itemKey(f.player.getInventory().getItem(0));
              command(f, BufferSettingsPackets.DEPOSIT, 0, 3, "", TerminalPackets.fingerprint(key));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.player.getInventory().isEmpty()
                        && f.buffer.inventory().snapshot().slots().getFirst().count() == 7,
                    "Scheduled round trip preserves exact physical stock"))
        .thenExecute(() -> end(f))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void rangeInvalidationRejectsApplyAndClosesMenu(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> helper.assertTrue(f.buffer.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              open(helper, f);
              command(f, BufferSettingsPackets.RATE, 0, 8, "", new byte[32]);
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var m = menu(f);
              f.player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)).add(20, 0, 0));
              command(f, BufferSettingsPackets.APPLY, 0, 0, "", new byte[32]);
              helper.assertTrue(
                  DeviceSavedData.get(f.player.server).buffers().deployment(f.buffer.handle().id())
                      == null,
                  "Out-of-range draft cannot publish");
              m.broadcastChanges();
              helper.assertTrue(
                  f.player.containerMenu != m, "Invalid physical access closes native menu");
              end(f);
            })
        .thenSucceed();
  }
}
