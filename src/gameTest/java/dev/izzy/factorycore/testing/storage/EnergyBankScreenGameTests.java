package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.EnergyBankEntity;
import dev.izzy.factorycore.platform.storage.EnergyBankMenu;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Q20: the Energy Bank screen and its in-place module upgrade. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class EnergyBankScreenGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos CABLE = new BlockPos(2, 1, 1);
  private static final BlockPos BANK = new BlockPos(3, 1, 1);

  private EnergyBankScreenGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(BANK)));
    player.getInventory().clearContent();
    return player;
  }

  private static EnergyBankMenu open(GameTestHelper h, ServerPlayer player) {
    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    h.useBlock(BANK, player);
    h.assertTrue(
        player.containerMenu instanceof EnergyBankMenu, "Empty-hand use opens the bank screen");
    return (EnergyBankMenu) player.containerMenu;
  }

  private static EnergyBankEntity bank(GameTestHelper h) {
    return h.getBlockEntity(BANK);
  }

  private static boolean valid(GameTestHelper h) {
    return WorldNetworks.get(h.getLevel().getServer())
            .membership(Positions.of(h.getLevel(), h.absolutePos(BANK)))
            .status()
        == NetworkTopology.Status.VALID;
  }

  /**
   * A module in the screen's slot upgrades the bank where it stands: same lease identity, same
   * energy, module used up, screen still open. A skipped level and a downgrade are refused and the
   * module stays in the slot (returned when the screen closes); a player without Edit is refused.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void moduleSlotUpgradesInPlaceKeepingEnergy(GameTestHelper h) {
    var owner = player(h);
    var stranger = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.cable(CABLE);
    net.bank(BANK, EnergyBankTier.STARTER);
    var backing = new java.util.UUID[1];
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(valid(h), "Bank network ready"))
        .thenExecute(
            () -> {
              var handle = bank(h).handle();
              backing[0] = handle.backing();
              DeviceSavedData.get(h.getLevel().getServer())
                  .registry()
                  .insert(handle, ResourceKey.ENERGY, 60_000);
              var menu = open(h, owner);
              h.assertTrue(
                  menu.energy() == 60_000 && menu.tier() == EnergyBankTier.STARTER,
                  "The screen shows the charge and tier: " + menu.energy());

              menu.getSlot(0).setByPlayer(new ItemStack(StorageContent.ADVANCED.get()));
              h.assertTrue(
                  bank(h).tier() == EnergyBankTier.STARTER
                      && menu.getSlot(0).getItem().is(StorageContent.ADVANCED.get()),
                  "An Advanced Module cannot skip Expanded; it stays in the slot");
              owner.closeContainer();
              h.assertTrue(
                  owner.getInventory().contains(new ItemStack(StorageContent.ADVANCED.get())),
                  "Closing the screen returns the refused module");

              var foreign = open(h, stranger);
              foreign.getSlot(0).setByPlayer(new ItemStack(StorageContent.EXPANSION.get()));
              h.assertTrue(
                  bank(h).tier() == EnergyBankTier.STARTER
                      && foreign.getSlot(0).getItem().is(StorageContent.EXPANSION.get()),
                  "A player without Edit cannot upgrade the bank");
              stranger.closeContainer();

              menu = open(h, owner);
              menu.getSlot(0).setByPlayer(new ItemStack(StorageContent.EXPANSION.get()));
              var upgraded = bank(h);
              h.assertTrue(
                  upgraded.tier() == EnergyBankTier.EXPANDED
                      && h.getBlockState(BANK).is(EnergyBankContent.block(EnergyBankTier.EXPANDED))
                      && upgraded.handle().backing().equals(backing[0])
                      && menu.getSlot(0).getItem().isEmpty()
                      && menu.stillValid(owner),
                  "An Expansion Module upgrades the bank in place and is used up");
              h.assertTrue(
                  DeviceSavedData.get(h.getLevel().getServer())
                          .registry()
                          .stock(upgraded.handle(), ResourceKey.ENERGY)
                          .total()
                      == 60_000,
                  "The upgraded bank keeps its energy");

              menu.getSlot(0).setByPlayer(new ItemStack(StorageContent.EXPANSION.get()));
              h.assertTrue(
                  bank(h).tier() == EnergyBankTier.EXPANDED
                      && menu.getSlot(0).getItem().is(StorageContent.EXPANSION.get()),
                  "A second Expansion Module (no step, or back) is refused");
              owner.closeContainer();
            })
        .thenWaitUntil(() -> h.assertTrue(valid(h), "The upgraded bank rejoins its network"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  bank(h).getEnergyStored() == 60_000
                      && bank(h).getMaxEnergyStored() == EnergyBankTier.EXPANDED.capacity(),
                  "Online again with its energy and the Expanded capacity");
              owner.connection.disconnect(Component.literal("Bank fixture complete"));
              stranger.connection.disconnect(Component.literal("Bank fixture complete"));
            })
        .thenSucceed();
  }
}
