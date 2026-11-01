package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.power.CreativeEnergySourceEntity;
import dev.izzy.factorycore.platform.power.FuelGeneratorContent;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q26 (QD13): creative-tab-only items with no recipe (RecipeGameTests checks that): the Creative
 * Energy Source, the creative Wireless Terminal, and the Infinite item and fluid cells as the
 * creative cells.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CreativeItemsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      POINT = new BlockPos(1, 1, 2),
      SOURCE = new BlockPos(1, 2, 2);

  private CreativeItemsGameTests() {}

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void theCreativeSourcePowersAnAccessPointAndNeverRunsOut(GameTestHelper h) {
    h.setBlock(POINT, WirelessContent.ACCESS_POINT.get());
    AccessPointEntity point = h.getBlockEntity(POINT);
    h.setBlock(SOURCE, FuelGeneratorContent.CREATIVE_BLOCK.get());
    CreativeEnergySourceEntity source = h.getBlockEntity(SOURCE);
    var exposed =
        h.getLevel()
            .getCapability(Capabilities.EnergyStorage.BLOCK, h.absolutePos(SOURCE), Direction.DOWN);
    h.assertTrue(exposed != null, "The source exposes FE");
    h.assertTrue(
        exposed.extractEnergy(Integer.MAX_VALUE, false) == Integer.MAX_VALUE
            && exposed.extractEnergy(1_000, false) == 1_000
            && exposed.getEnergyStored() == Integer.MAX_VALUE
            && exposed.receiveEnergy(1_000, false) == 0,
        "Extraction never runs it down, and it takes nothing in");
    h.assertTrue(point.energy() == 0, "The access point starts empty");
    h.succeedWhen(
        () ->
            h.assertTrue(
                point.energy() == point.energyStorage().getMaxEnergyStored() && source != null,
                "The access point beside it fills with no other power: " + point.energy()));
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void theCreativeHandheldNeedsNoChargeAndReachesAcrossTheDimension(
      GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    var cell = CellHandleData.read(Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM).cell(0));
    h.setBlock(POINT, WirelessContent.ACCESS_POINT.get());
    AccessPointEntity point = h.getBlockEntity(POINT);
    h.setBlock(SOURCE, FuelGeneratorContent.CREATIVE_BLOCK.get());
    var binding =
        new WirelessContent.Binding(
            h.getLevel().dimension().location().toString(), h.absolutePos(POINT).asLong());
    var creative = new ItemStack(WirelessContent.CREATIVE_HANDHELD.get());
    creative.set(WirelessContent.BINDING.get(), binding);
    var ordinary = new ItemStack(WirelessContent.HANDHELD.get());
    ordinary.set(WirelessContent.BINDING.get(), binding);
    ordinary.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(20_000, false);
    var cobble =
        new dev.izzy.factorycore.platform.resource.PlatformResourceCodec(
                h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    // Well past the access point's 64-block range, in the same dimension. Straight up, so no other
    // chunk loads: GameTests are placed at random, and loading a chunk beside another test's
    // network makes it revalidate mid-test.
    var far = Vec3.atCenterOf(h.absolutePos(ROOT)).add(0, 250, 0);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    point.current()
                        && point.energy() >= point.tickCost()
                        && saved.registry().stock(cell, cobble).loaded(),
                    "Network ready"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  creative.getCapability(Capabilities.EnergyStorage.ITEM) == null
                      && WirelessTerminalItem.creative(creative)
                      && !WirelessTerminalItem.creative(ordinary),
                  "The creative handheld has no battery");
              player.moveTo(far);
              player.setItemInHand(InteractionHand.MAIN_HAND, ordinary);
              h.assertTrue(
                  WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND)
                      .equals("out_of_range"),
                  "A charged ordinary handheld is out of range 250 blocks away");
              player.setItemInHand(InteractionHand.MAIN_HAND, creative);
              var reason = WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
              h.assertTrue(
                  reason.isEmpty() && player.containerMenu instanceof TerminalMenu,
                  "The uncharged creative handheld opens from there: " + reason);
            })
        .thenIdle(5)
        .thenExecute(
            () -> {
              player.containerMenu.broadcastChanges();
              h.assertTrue(
                  player.containerMenu instanceof TerminalMenu, "And stays open while it is held");
              // Swapping in the ordinary handheld ends the far session rather than keeping reach.
              player.setItemInHand(InteractionHand.MAIN_HAND, ordinary);
            })
        .thenWaitUntil(
            () -> {
              // Run the menu's validity check now, as the player's own tick would.
              player.containerMenu.broadcastChanges();
              h.assertTrue(
                  !(player.containerMenu instanceof TerminalMenu),
                  "An ordinary handheld does not inherit the creative reach");
            })
        .thenExecute(() -> player.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void theCreativeTabOffersTheCreativeItems(GameTestHelper h) {
    var tab = StorageContent.TAB.get();
    tab.buildContents(
        new CreativeModeTab.ItemDisplayParameters(
            FeatureFlags.DEFAULT_FLAGS, true, h.getLevel().registryAccess()));
    var shown = tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
    for (var item :
        java.util.List.of(
            FuelGeneratorContent.CREATIVE_ITEM.get(),
            WirelessContent.CREATIVE_HANDHELD.get(),
            // The creative item and fluid cells: unlimited and obtainable only by upgrades.
            StorageContent.cell(CellTier.INFINITE_ITEM),
            StorageContent.cell(CellTier.INFINITE_FLUID)))
      h.assertTrue(shown.contains(item), "The creative tab lists " + item);
    h.succeed();
  }
}
