package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class TerminalGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      TERMINAL = new BlockPos(1, 1, 2);

  private record Fixture(
      ServerPlayer player,
      TerminalEntity terminal,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ResourceKey key) {}

  private TerminalGameTests() {}

  private static ItemStack named(int amount) {
    var stack = new ItemStack(Items.IRON_INGOT, amount);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Terminal component variant"));
    return stack;
  }

  @SuppressWarnings("removal") // Pinned helper performs actual PlayerList authentication.
  private static Fixture setup(GameTestHelper helper) {
    var player = helper.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(player.connection.getConnection());
    player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    player.getInventory().clearContent();
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(player.getUUID());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    helper.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(
        player,
        helper.getBlockEntity(TERMINAL),
        DeviceSavedData.get(player.server),
        CellHandleData.read(drive.cell(0)),
        new PlatformResourceCodec(player.registryAccess()).itemKey(named(1)));
  }

  private static void ready(GameTestHelper helper, Fixture f) {
    helper.assertTrue(f.terminal.canUse(f.player), "Wired terminal authorizes authenticated owner");
  }

  private static TerminalMenu menu(Fixture f) {
    return (TerminalMenu) f.player.containerMenu;
  }

  private static void open(GameTestHelper helper, Fixture f) {
    helper.useBlock(TERMINAL, f.player);
    helper.assertTrue(
        f.player.containerMenu instanceof TerminalMenu, "Physical interaction opens native menu");
    menu(f).broadcastChanges();
  }

  private static void loaded(GameTestHelper helper, Fixture f) {
    menu(f).broadcastChanges();
    helper.assertTrue(
        menu(f).view().status().equals("ready"), "Bounded asynchronous menu search ready");
  }

  private static void command(Fixture f, int action, int selected, int amount, byte[] fingerprint) {
    var m = menu(f);
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            action,
            selected,
            amount,
            "",
            fingerprint));
  }

  private static void logout(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Terminal fixture complete"));
  }

  private static void search(Fixture f, String text) {
    var menu = menu(f);
    menu.command(
        f.player,
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            TerminalPackets.SEARCH,
            0,
            1,
            text,
            new byte[32]));
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void displayRegistryModTagSearchAndPlayerSortSurviveReopen(GameTestHelper helper) {
    var f = setup(helper);
    var codec = new PlatformResourceCodec(f.player.registryAccess());
    var gold = codec.itemKey(new ItemStack(Items.GOLD_INGOT));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              f.saved.registry().insert(f.cell, gold, 48);
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  menu(f).view().rows().getFirst().icon().equals("minecraft:gold_ingot"),
                  "Default name sorting uses the display name");
              search(f, "component variant");
            })
        .thenWaitUntil(
            () -> {
              loaded(helper, f);
              helper.assertTrue(
                  menu(f).view().rows().size() == 1,
                  "Display-name-only query finds exact custom component item");
            })
        .thenIdle(3)
        .thenExecute(() -> search(f, "minecraft:gold_ingot"))
        .thenWaitUntil(
            () -> {
              loaded(helper, f);
              helper.assertTrue(
                  menu(f).view().rows().getFirst().icon().equals("minecraft:gold_ingot"),
                  "Registry query selects gold");
            })
        .thenIdle(3)
        .thenExecute(() -> search(f, "@minecraft"))
        .thenWaitUntil(
            () -> {
              loaded(helper, f);
              helper.assertTrue(
                  menu(f).view().rows().size() == 2, "Namespace query finds both variants");
            })
        .thenIdle(3)
        .thenExecute(() -> search(f, "#minecraft:planks"))
        .thenWaitUntil(
            () -> {
              loaded(helper, f);
              helper.assertTrue(
                  menu(f).view().rows().isEmpty(), "Nonmatching loaded tag has no rows");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var planks = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
              f.saved.registry().insert(f.cell, planks, 64);
              search(f, "#minecraft:planks");
            })
        .thenWaitUntil(
            () -> {
              loaded(helper, f);
              helper.assertTrue(
                  menu(f).view().rows().size() == 1
                      && menu(f).view().rows().getFirst().icon().equals("minecraft:oak_planks"),
                  "Loaded registry tag matches its member only");
            })
        .thenIdle(3)
        .thenExecute(() -> search(f, "@minecraft"))
        .thenWaitUntil(() -> loaded(helper, f))
        .thenIdle(3)
        .thenExecute(
            () -> command(f, TerminalPackets.SORT, TerminalSearchSupport.QUANTITY, 1, new byte[32]))
        .thenWaitUntil(
            () -> {
              loaded(helper, f);
              helper.assertTrue(
                  menu(f).view().rows().getFirst().total().equals("64"),
                  "Quantity sort orders exact aggregate totals descending");
            })
        .thenExecute(
            () -> {
              f.player.closeContainer();
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  menu(f).view().search().equals("@minecraft")
                      && menu(f).view().sort() == TerminalSearchSupport.QUANTITY
                      && menu(f).view().rows().getFirst().total().equals("64"),
                  "Per-player persisted query and sort are restored on reopen");
              helper.assertTrue(
                  f.player.getInventory().isEmpty()
                      && f.saved.registry().stock(f.cell, f.key).total() == 32,
                  "All searches and sorts are read-only");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void nativeMenuSearchWithdrawAndExactShiftDeposit(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              var view = menu(f).view();
              helper.assertTrue(
                  view.rows().size() == 1
                      && view.rows().getFirst().total().equals("32")
                      && view.rows().getFirst().name().equals("Terminal component variant"),
                  "Authorized exact component row projects bounded name and exact total");
              command(f, TerminalPackets.WITHDRAW, 0, 17, new byte[32]);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.player.getInventory().getItem(0).getCount() == 17,
                    "Native main inventory receives selected exact items"))
        .thenIdle(5)
        .thenExecute(
            () -> {
              menu(f).broadcastChanges();
              command(f, TerminalPackets.DEPOSIT, 0, 17, TerminalPackets.fingerprint(f.key));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.key).total() == 32
                        && f.player.getInventory().getItem(0).isEmpty(),
                    "Deposit restores exact conserved network stock"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved
                          .transfers()
                          .unresolved(
                              TransferContext.playerBacking(
                                  f.saved.registry().world(), f.player.getUUID()))
                      == null,
                  "Completed terminal operations retire without orphan player staging");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void wrongSessionRevisionAndChangedSlotCannotMoveItems(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              var m = menu(f);
              m.command(
                  f.player,
                  new TerminalPackets.Command(
                      m.containerId,
                      UUID.randomUUID(),
                      m.view().revision(),
                      TerminalPackets.WITHDRAW,
                      0,
                      8,
                      "",
                      new byte[32]));
              m.command(
                  f.player,
                  new TerminalPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision() - 1,
                      TerminalPackets.WITHDRAW,
                      0,
                      8,
                      "",
                      new byte[32]));
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              f.player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 9));
              command(f, TerminalPackets.DEPOSIT, 0, 9, TerminalPackets.fingerprint(f.key));
            })
        .thenIdle(15)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 32
                      && f.player.getInventory().getItem(0).is(Items.GOLD_INGOT)
                      && f.player.getInventory().getItem(0).getCount() == 9,
                  "Wrong session, old row and changed exact own-slot identity perform no extraction");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void closingQueuedActionAndReplacingHolderInvalidateOldMenu(GameTestHelper helper) {
    var f = setup(helper);
    TerminalMenu[] old = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              old[0] = menu(f);
              command(f, TerminalPackets.WITHDRAW, 0, 12, new byte[32]);
              f.player.closeContainer();
              helper.setBlock(TERMINAL, Blocks.AIR);
              helper.setBlock(TERMINAL, TerminalContent.BLOCK.get());
              old[0].command(
                  f.player,
                  new TerminalPackets.Command(
                      old[0].containerId,
                      old[0].session(),
                      old[0].view().revision(),
                      TerminalPackets.WITHDRAW,
                      0,
                      12,
                      "",
                      new byte[32]));
            })
        .thenIdle(20)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  !old[0].stillValid(f.player)
                      && f.saved.registry().stock(f.cell, f.key).total() == 32
                      && f.player.getInventory().isEmpty(),
                  "Closed/replaced holder cannot extract through stale menu and queued cancellation conserves stock");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fullInventoryRejectsWithdrawalWithoutStaging(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              for (int i = 0; i < 36; i++)
                f.player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(() -> command(f, TerminalPackets.WITHDRAW, 0, 32, new byte[32]))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              helper.assertTrue(
                  !menu(f).view().busy(), "Full inventory route completes with typed rejection");
            })
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 32
                      && menu(f).view().status().equals("full"),
                  "Full main inventory keeps source untouched and reports full");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void realHopperImportAppearsInSearchAndWithdrawsIntoPlayer(GameTestHelper helper) {
    var f = setup(helper);
    var bufferPos = new BlockPos(3, 1, 1);
    helper.setBlock(bufferPos, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(bufferPos);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), f.player.getUUID());
    helper.setBlock(
        bufferPos.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = helper.getBlockEntity(bufferPos.above());
    helper
        .startSequence()
        .thenWaitUntil(
            () -> {
              ready(helper, f);
              helper.assertTrue(
                  WorldNetworks.get(f.player.server).membership(buffer.position()).status()
                      == NetworkTopology.Status.VALID,
                  "Hopper buffer joins same bounded network");
            })
        .thenExecute(
            () -> {
              buffer.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  BufferRegistry.Mode.IMPORT);
              hopper.setItem(0, named(23));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.key).total() == 23,
                    "Actual vanilla hopper imports into digital cell without code"))
        .thenExecute(() -> open(helper, f))
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  menu(f).view().rows().getFirst().available().equals("23"),
                  "Imported resources appear as exact free terminal stock");
              command(f, TerminalPackets.WITHDRAW, 0, 23, new byte[32]);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.player.getInventory().getItem(0).getCount() == 23,
                    "Search-selected hopper input withdraws to real player inventory"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  hopper.isEmpty()
                      && buffer.inventory().total(f.key) == 0
                      && f.saved.registry().stock(f.cell, f.key).total() == 0,
                  "End-to-end hopper/buffer/cell/player loop conserves all 23 component items");
              logout(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void rangeLossClosesSessionAndCancelsQueuedMutation(GameTestHelper helper) {
    var f = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              command(f, TerminalPackets.WITHDRAW, 0, 12, new byte[32]);
              f.player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)).add(20, 0, 0));
              menu(f).broadcastChanges();
            })
        .thenIdle(20)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  !(f.player.containerMenu instanceof TerminalMenu)
                      && f.saved.registry().stock(f.cell, f.key).total() == 32
                      && f.player.getInventory().isEmpty(),
                  "Range loss closes menu and queued mutation cannot extract");
              logout(f);
            })
        .thenSucceed();
  }

  @SuppressWarnings(
      "removal") // Real authenticated second principal, with a mocked NeoForge channel negotiation.
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void revokedViewerCannotReadOrFinishQueuedWithdrawal(GameTestHelper helper) {
    var owner = setup(helper);
    var guest = helper.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(guest.connection.getConnection());
    guest.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    guest.getInventory().clearContent();
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    var actor = new NetworkPermissions.Actor(owner.player.getUUID(), false);
    var grants = owner.saved.network(root.handle().id());
    grants.setGrants(
        actor,
        guest.getUUID(),
        Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.WITHDRAW));
    var f = new Fixture(guest, owner.terminal, owner.saved, owner.cell, owner.key);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.saved.registry().insert(f.cell, f.key, 32);
              open(helper, f);
            })
        .thenWaitUntil(() -> loaded(helper, f))
        .thenExecute(
            () -> {
              command(f, TerminalPackets.WITHDRAW, 0, 12, new byte[32]);
              grants.setGrants(actor, guest.getUUID(), Set.of());
              menu(f).broadcastChanges();
            })
        .thenIdle(20)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  !(guest.containerMenu instanceof TerminalMenu)
                      && !f.terminal.canUse(guest)
                      && f.saved.registry().stock(f.cell, f.key).total() == 32
                      && guest.getInventory().isEmpty(),
                  "Revoked viewer loses physical/menu access and queued action before any extraction");
              helper.useBlock(TERMINAL, guest);
              helper.assertTrue(
                  !(guest.containerMenu instanceof TerminalMenu),
                  "Reopening a copied access shell grants no access");
              logout(f);
              logout(owner);
            })
        .thenSucceed();
  }

  /**
   * Two open sessions on one terminal: stock changed by the other session (or by the owner's grant
   * administration) reaches an idle viewer without any action of its own.
   */
  @SuppressWarnings("removal") // Real authenticated second principal.
  @GameTest(template = "empty", timeoutTicks = 400)
  public static void idleViewerSeesOtherSessionsChangesAndGrantEdits(GameTestHelper helper) {
    var owner = setup(helper);
    var guest = helper.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(guest.connection.getConnection());
    guest.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    guest.getInventory().clearContent();
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    var actor = new NetworkPermissions.Actor(owner.player.getUUID(), false);
    var grants = owner.saved.network(root.handle().id());
    var f = new Fixture(guest, owner.terminal, owner.saved, owner.cell, owner.key);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, owner))
        .thenExecute(
            () -> {
              grants.setGrants(
                  actor,
                  guest.getUUID(),
                  Set.of(
                      NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.WITHDRAW));
              f.saved.registry().insert(f.cell, f.key, 32);
              open(helper, owner);
              open(helper, f);
            })
        .thenWaitUntil(
            () -> {
              loaded(helper, owner);
              loaded(helper, f);
              helper.assertTrue(
                  menu(owner).view().rows().getFirst().total().equals("32")
                      && menu(f).view().rows().getFirst().total().equals("32"),
                  "Both sessions show 32");
            })
        .thenExecute(() -> command(f, TerminalPackets.WITHDRAW, 0, 12, new byte[32]))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              menu(owner).broadcastChanges();
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total() == 20
                      && menu(owner).view().rows().getFirst().total().equals("20"),
                  "Idle owner session shows the guest's withdrawal: " + menu(owner).view());
            })
        .thenExecute(
            () ->
                grants.setGrants(
                    actor, UUID.randomUUID(), Set.of(NetworkPermissions.Permission.VIEW)))
        .thenWaitUntil(
            () -> {
              menu(f).broadcastChanges();
              menu(owner).broadcastChanges();
              helper.assertTrue(
                  menu(owner).view().status().equals("ready")
                      && menu(owner).view().rows().size() == 1
                      && menu(owner).view().rows().getFirst().total().equals("20"),
                  "A grant edit re-reads instead of leaving the owner denied: "
                      + menu(owner).view());
              helper.assertTrue(
                  !menu(f).view().busy()
                      && menu(f).view().status().equals("ok")
                      && menu(f).view().rows().size() == 1
                      && menu(f).view().rows().getFirst().total().equals("20"),
                  "The acting session retains its successful result while refreshing current stock");
            })
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.key).total()
                          + guest.getInventory().countItem(Items.IRON_INGOT)
                      == 32,
                  "Exactly 32 conserved");
              logout(f);
              logout(owner);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void terminalRecipeUsesAffordableMaterialsAndNoOwnershipReference(
      GameTestHelper helper) {
    var id = ResourceLocation.fromNamespaceAndPath("factorycore", "storage_terminal");
    var recipe = helper.getLevel().getRecipeManager().byKey(id).orElseThrow().value();
    var costs = new HashMap<String, Integer>();
    for (var ingredient : recipe.getIngredients())
      for (var stack : ingredient.getItems()) {
        var item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        helper.assertTrue(
            item.getNamespace().equals("minecraft"),
            "Starter terminal uses only vanilla materials");
        costs.merge(item.getPath(), 1, Integer::sum);
      }
    helper.assertTrue(
        costs.equals(Map.of("iron_ingot", 4, "glass", 3, "redstone", 2)),
        "Exact affordable terminal recipe");
    var result = recipe.getResultItem(helper.getLevel().registryAccess());
    helper.assertTrue(
        result.is(TerminalContent.ITEM.get())
            && result.getCount() == 1
            && result.get(DataComponents.CUSTOM_DATA) == null,
        "Access-only shell has no copied inventory or authority");
    helper.assertTrue(
        helper
                .getLevel()
                .getServer()
                .getAdvancements()
                .get(
                    ResourceLocation.fromNamespaceAndPath(
                        "factorycore", "recipes/misc/storage_terminal"))
            != null,
        "Recipe-book unlock exists");
    helper.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void nativeInitializationDoesNotSendCloseForPriorInventoryMenu(
      GameTestHelper helper) {
    var f = setup(helper);
    var closed = new AtomicInteger();
    var pipeline = f.player.connection.getConnection().channel().pipeline();
    pipeline.addBefore(
        pipeline.context(f.player.connection.getConnection()).name(),
        "terminal_close_regression",
        new io.netty.channel.ChannelOutboundHandlerAdapter() {
          @Override
          public void write(
              io.netty.channel.ChannelHandlerContext context,
              Object message,
              io.netty.channel.ChannelPromise promise)
              throws Exception {
            if (message instanceof ClientboundContainerClosePacket) closed.incrementAndGet();
            super.write(context, message, promise);
          }
        });
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              open(helper, f);
              helper.assertTrue(
                  closed.get() == 0,
                  "Native initMenu must not emit an old-menu close after opening the terminal");
              helper.assertTrue(
                  f.player.containerMenu instanceof TerminalMenu,
                  "New native menu remains installed");
              logout(f);
            })
        .thenSucceed();
  }
}
