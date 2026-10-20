package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.wireless.LinkEndpointEntity;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Paid link drops and unsupported tier data retain exactly one physical owner. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WirelessUpgradeRecoveryGameTests {
  private static final BlockPos LINK = new BlockPos(2, 1, 2);

  private WirelessUpgradeRecoveryGameTests() {}

  @SuppressWarnings("removal")
  private static ServerPlayer player(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(player.connection.getConnection());
    player.setGameMode(GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(LINK)));
    return player;
  }

  private static LinkEndpointEntity place(GameTestHelper h, ServerPlayer player, ItemStack item) {
    h.setBlock(LINK, WirelessContent.LINK.get());
    WirelessContent.LINK
        .get()
        .setPlacedBy(h.getLevel(), h.absolutePos(LINK), h.getBlockState(LINK), player, item);
    return h.getBlockEntity(LINK);
  }

  private static InteractionResult use(GameTestHelper h, ServerPlayer player, ItemStack upgrade) {
    player.setItemInHand(InteractionHand.MAIN_HAND, upgrade);
    var position = h.absolutePos(LINK);
    return upgrade.useOn(
        new UseOnContext(
            player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false)));
  }

  @GameTest(template = "empty")
  public static void paidLinkUseReturnsOneUpgradeOnBreakAndRejectsWrongSteps(GameTestHelper h) {
    var player = player(h);
    var link = place(h, player, new ItemStack(WirelessContent.LINK_ITEM.get()));
    var advanced = new ItemStack(ThroughputContent.THROUGHPUT.get(), 2);
    h.assertTrue(
        use(h, player, advanced) == InteractionResult.FAIL && advanced.getCount() == 2,
        "A link refuses the port-only upgrade without consumption");
    var overdrive = new ItemStack(ThroughputContent.OVERDRIVE.get(), 2);
    h.assertTrue(
        use(h, player, overdrive).consumesAction() && overdrive.getCount() == 1,
        "Orphan owner installs exactly one paid Overdrive");
    h.assertTrue(
        use(h, player, overdrive) == InteractionResult.FAIL && overdrive.getCount() == 1,
        "Repeated link upgrade consumes nothing");
    var tag = link.saveWithoutMetadata(h.getLevel().registryAccess());
    link.loadWithComponents(tag, h.getLevel().registryAccess());
    h.assertTrue(
        link.tier() == ThroughputTier.ENDGAME, "Known link tier survives native NBT reload");
    h.getLevel().destroyBlock(h.absolutePos(LINK), true);
    var drops =
        h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(LINK)).inflate(1));
    int blocks = 0, upgrades = 0;
    for (var drop : drops) {
      if (drop.getItem().is(WirelessContent.LINK_ITEM.get())) blocks += drop.getItem().getCount();
      if (drop.getItem().is(ThroughputContent.OVERDRIVE.get()))
        upgrades += drop.getItem().getCount();
    }
    h.assertTrue(blocks == 1 && upgrades == 1, "Break returns one endpoint and one paid upgrade");
    player.connection.disconnect(Component.literal("Link upgrade complete"));
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void unknownOrMalformedTierStaysInertAndPreservedAcrossActualBreakPlace(
      GameTestHelper h) {
    var player = player(h);
    for (int mode = 0; mode < 3; mode++) {
      var link = place(h, player, new ItemStack(WirelessContent.LINK_ITEM.get()));
      var raw = link.saveWithoutMetadata(h.getLevel().registryAccess());
      if (mode == 0) raw.putString("Throughput", "FUTURE");
      if (mode == 1) raw.putInt("Throughput", 99);
      if (mode == 2) raw.putString("Throughput", "ADVANCED");
      raw.putString("FutureProof", "preserve this before-state");
      raw.putLong("Energy", 902);
      link.loadWithComponents(raw, h.getLevel().registryAccess());
      h.assertTrue(
          link.needsRecovery()
              && !link.active()
              && link.energyStorage().receiveEnergy(1000, false) == 0,
          "Unsupported field does not become a live base endpoint");
      var held = new ItemStack(ThroughputContent.OVERDRIVE.get(), 2);
      h.assertTrue(
          use(h, player, held) == InteractionResult.FAIL && held.getCount() == 2,
          "Unknown tier cannot be overwritten by paying another upgrade");
      var saved = link.saveWithoutMetadata(h.getLevel().registryAccess());
      h.assertTrue(raw.equals(saved), "Full rejected native data is preserved: " + saved);
      h.getLevel().destroyBlock(h.absolutePos(LINK), true);
      var drops =
          h.getLevel()
              .getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(LINK)).inflate(1));
      h.assertTrue(
          drops.size() == 1
              && drops.getFirst().getItem().getCount() == 1
              && drops.getFirst().getItem().is(WirelessContent.LINK_ITEM.get())
              && drops.getFirst().getItem().has(DataComponents.CUSTOM_DATA),
          "Unknown state has exactly one evidence-bearing recovery item, no guessed upgrade");
      var recovery = drops.getFirst().getItem().copy();
      drops.forEach(ItemEntity::discard);
      var restored = place(h, player, recovery);
      h.assertTrue(
          restored.needsRecovery()
              && !restored.active()
              && raw.equals(restored.saveWithoutMetadata(h.getLevel().registryAccess())),
          "Placing recovery item retains the original unsupported data and remains inert");
      h.setBlock(LINK, Blocks.AIR);
      h.getLevel()
          .getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(LINK)).inflate(1))
          .forEach(ItemEntity::discard);
    }
    player.connection.disconnect(Component.literal("Link recovery complete"));
    h.succeed();
  }
}
