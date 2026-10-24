package dev.izzy.factorycore.testing.wireless;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.network.GatewayPolicyMenu;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Paid link drops and unsupported tier data retain exactly one physical owner. Since ADR 0061 the
 * link lives in a gateway; these checks run against a linked gateway.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WirelessUpgradeRecoveryGameTests {
  private static final BlockPos LINK = new BlockPos(2, 1, 2);

  private WirelessUpgradeRecoveryGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(LINK)));
    return player;
  }

  /** Uses {@code item} on the gateway's downstream (east) face. */
  private static InteractionResult use(GameTestHelper h, ServerPlayer player, ItemStack item) {
    player.setItemInHand(InteractionHand.MAIN_HAND, item);
    var position = h.absolutePos(LINK);
    return item.useOn(
        new UseOnContext(
            player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(position), Direction.EAST, position, false)));
  }

  /** A rootless gateway with a link installed by {@code player} through the actual item. */
  private static NetworkNodeEntity place(GameTestHelper h, ServerPlayer player, ItemStack link) {
    h.setBlock(
        LINK,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(LINK);
    gateway.commission(null);
    h.assertTrue(use(h, player, link).consumesAction(), "The link item installs on the gateway");
    return gateway;
  }

  private static List<ItemEntity> drops(GameTestHelper h) {
    return h.getLevel()
        .getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(LINK)).inflate(1));
  }

  @GameTest(template = "empty")
  public static void paidLinkUseReturnsOneUpgradeOnBreakAndRejectsWrongSteps(GameTestHelper h) {
    var player = player(h);
    var gateway = place(h, player, new ItemStack(WirelessContent.LINK_ITEM.get()));
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
    var tag = gateway.saveWithoutMetadata(h.getLevel().registryAccess());
    gateway.loadWithComponents(tag, h.getLevel().registryAccess());
    h.assertTrue(
        gateway.link().tier() == ThroughputTier.ENDGAME,
        "Known link tier survives native NBT reload");
    h.getLevel().destroyBlock(h.absolutePos(LINK), true);
    int links = 0, upgrades = 0;
    for (var drop : drops(h)) {
      if (drop.getItem().is(WirelessContent.LINK_ITEM.get())) links += drop.getItem().getCount();
      if (drop.getItem().is(ThroughputContent.OVERDRIVE.get()))
        upgrades += drop.getItem().getCount();
    }
    h.assertTrue(links == 1 && upgrades == 1, "Break returns one link and one paid upgrade");
    player.connection.disconnect(Component.literal("Link upgrade complete"));
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void unknownOrMalformedTierStaysInertAndPreservedAcrossActualBreakPlace(
      GameTestHelper h) {
    var player = player(h);
    for (int mode = 0; mode < 3; mode++) {
      var gateway = place(h, player, new ItemStack(WirelessContent.LINK_ITEM.get()));
      var whole = gateway.saveWithoutMetadata(h.getLevel().registryAccess());
      var raw = whole.getCompound(LinkModule.FIELD);
      if (mode == 0) raw.putString("Throughput", "FUTURE");
      if (mode == 1) raw.putInt("Throughput", 99);
      if (mode == 2) raw.putString("Throughput", "ADVANCED");
      raw.putString("FutureProof", "preserve this before-state");
      raw.putLong("Energy", 902);
      whole.put(LinkModule.FIELD, raw);
      gateway.loadWithComponents(whole, h.getLevel().registryAccess());
      var link = gateway.link();
      h.assertTrue(
          link.needsRecovery()
              && !link.active()
              && link.energyStorage().receiveEnergy(1000, false) == 0,
          "Unsupported field does not become a live base link");
      var held = new ItemStack(ThroughputContent.OVERDRIVE.get(), 2);
      h.assertTrue(
          use(h, player, held) == InteractionResult.FAIL && held.getCount() == 2,
          "Unknown tier cannot be overwritten by paying another upgrade");
      var saved =
          gateway.saveWithoutMetadata(h.getLevel().registryAccess()).getCompound(LinkModule.FIELD);
      h.assertTrue(raw.equals(saved), "Full rejected native data is preserved: " + saved);
      h.getLevel().destroyBlock(h.absolutePos(LINK), true);
      var links =
          drops(h).stream()
              .filter(drop -> drop.getItem().is(WirelessContent.LINK_ITEM.get()))
              .toList();
      h.assertTrue(
          links.size() == 1
              && links.getFirst().getItem().getCount() == 1
              && links.getFirst().getItem().has(DataComponents.CUSTOM_DATA)
              && drops(h).stream()
                  .noneMatch(drop -> drop.getItem().is(ThroughputContent.OVERDRIVE.get())),
          "Unknown state has exactly one evidence-bearing recovery item, no guessed upgrade");
      var recovery = links.getFirst().getItem().copy();
      drops(h).forEach(ItemEntity::discard);
      var restored = place(h, player, recovery).link();
      h.assertTrue(
          restored.needsRecovery() && !restored.active() && raw.equals(restored.save()),
          "Installing the recovery item retains the original unsupported data and stays inert");
      h.setBlock(LINK, Blocks.AIR);
      drops(h).forEach(ItemEntity::discard);
    }
    player.connection.disconnect(Component.literal("Link recovery complete"));
    h.succeed();
  }

  /** ADR 0061: the link installs only on a gateway's cross or dot face, once. */
  @GameTest(template = "empty")
  public static void linkItemInstallsOnlyOnAGatewaySideOnce(GameTestHelper h) {
    var player = player(h);
    h.setBlock(
        LINK,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(LINK);
    gateway.commission(null);
    var held = new ItemStack(WirelessContent.LINK_ITEM.get(), 3);
    player.setItemInHand(InteractionHand.MAIN_HAND, held);
    var position = h.absolutePos(LINK);
    var top =
        held.useOn(
            new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false)));
    h.assertTrue(
        top == InteractionResult.FAIL && held.getCount() == 3 && gateway.link() == null,
        "A non-gateway-side face installs nothing and consumes nothing");
    h.assertTrue(
        use(h, player, held).consumesAction()
            && held.getCount() == 2
            && gateway.link() != null
            && gateway.link().downstream(),
        "The dot (downstream) face installs one link");
    h.assertTrue(
        use(h, player, held) == InteractionResult.FAIL && held.getCount() == 2,
        "A second link on the same gateway is refused without consumption");
    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    h.useBlock(LINK, player);
    h.assertTrue(
        player.containerMenu instanceof GatewayPolicyMenu menu
            && menu.linkState().equals("unpaired")
            && menu.deviceStatus().reason().equals("status.factorycore.link.unpaired"),
        "The gateway screen reports the installed link's state");
    player.connection.disconnect(Component.literal("Link install complete"));
    h.succeed();
  }
}
