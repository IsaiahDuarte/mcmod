package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCrafting;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.testing.Net;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BranchCraftingGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      GATEWAY = new BlockPos(2, 1, 1),
      CHILD_UNIT = new BlockPos(3, 1, 1);

  private record Fixture(
      UUID owner,
      NodeRegistry.Position root,
      NodeRegistry.Position child,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      NetworkNodeEntity gate,
      ResourceKey log,
      ResourceKey stick) {}

  private BranchCraftingGameTests() {}

  private static ItemStack pattern(int[] cells, Item input, Item output, int count) {
    var stacks = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int cell : cells) stacks.set(cell, new ItemStack(input));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, stacks, new ItemStack(output, count), List.of()));
    return stack;
  }

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos pos) {
    return Positions.of(h.getLevel(), h.absolutePos(pos));
  }

  private static Fixture setup(GameTestHelper h) {
    var owner = UUID.randomUUID();
    NetworkNodeEntity root = Net.in(h, owner).controller(ROOT);
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gate = h.getBlockEntity(GATEWAY);
    gate.commission(null);
    h.setBlock(CHILD_UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(CHILD_UNIT);
    unit.patterns()
        .insertItem(0, pattern(new int[] {0}, Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns()
        .insertItem(1, pattern(new int[] {0, 3}, Items.OAK_PLANKS, Items.STICK, 4), false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        owner,
        position(h, ROOT),
        position(h, CHILD_UNIT),
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        gate,
        codec.itemKey(new ItemStack(Items.OAK_LOG)),
        codec.itemKey(new ItemStack(Items.STICK)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    var networks = WorldNetworks.get(h.getLevel().getServer());
    h.assertTrue(
        networks.membership(f.root).status() == NetworkTopology.Status.VALID
            && networks.membership(f.child).status() == NetworkTopology.Status.VALID
            && f.saved.registry().stock(f.cell, f.log).loaded(),
        "Root and branch ready");
  }

  private static NetworkTopology.Scope scope(GameTestHelper h, NodeRegistry.Position position) {
    return WorldNetworks.get(h.getLevel().getServer()).membership(position).scope();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void rootRunsPublishedBranchRecipeWithOneReservationAndNoBranchStorage(
      GameTestHelper h) {
    var f = setup(h);
    UUID[] id = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              h.assertTrue(
                  !scope(h, f.root).segment().equals(scope(h, f.child).segment()),
                  "Unit is in a separate child segment");
              var offer =
                  WorldCrafting.get(h.getLevel().getServer())
                      .index(scope(h, f.root))
                      .byOutput()
                      .get(f.stick)
                      .getFirst();
              h.assertTrue(offer.published(), "Root sees the branch recipe as published");
              f.saved.registry().insert(f.cell, f.log, 2);
              var submission =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(new NetworkPermissions.Actor(f.owner, false), f.root, f.stick, 8);
              h.assertTrue(submission.job() != null, "Root submits: " + submission.reason());
              id[0] = submission.job();
              h.assertTrue(
                  f.saved.registry().stock(f.cell, f.log).reserved() == 1
                      && f.saved
                          .craftingJobs()
                          .get(id[0])
                          .segment()
                          .equals(scope(h, f.root).segment()),
                  "Parent reserves its own log once; the job belongs to the root segment");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.saved.craftingJobs().get(id[0]) == null, "Branch executes and job finishes"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.saved.registry().stock(f.cell, f.stick).total() == 8
                        && f.saved.registry().stock(f.cell, f.log).total() == 1
                        && f.saved.registry().stock(f.cell, f.log).reserved() == 0,
                    "Outputs return to root storage; nothing reserved twice or left behind"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void gatewayWithoutBranchCraftDoesNotPublish(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var branch = EnumSet.allOf(NetworkPermissions.Permission.class);
              branch.remove(NetworkPermissions.Permission.CRAFT);
              var failure =
                  f.saved
                      .nodes()
                      .setPolicy(
                          f.gate.handle(),
                          new GatewayPolicy(
                              branch,
                              EnumSet.allOf(NetworkPermissions.Permission.class),
                              EnumSet.allOf(ResourceKind.class)));
              h.assertTrue(failure == NodeRegistry.Failure.OK, "Policy updated");
              WorldNetworks.changed(h.getLevel(), h.absolutePos(GATEWAY));
              f.saved.registry().insert(f.cell, f.log, 2);
            })
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  WorldCrafting.get(h.getLevel().getServer())
                      .index(scope(h, f.root))
                      .producers(f.stick)
                      .isEmpty(),
                  "Restricted branch recipes are not published");
            })
        .thenExecute(
            () -> {
              var submission =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(new NetworkPermissions.Actor(f.owner, false), f.root, f.stick, 8);
              h.assertTrue(
                  submission.job() == null
                      && submission.reason().equals("missing")
                      && f.saved.registry().stock(f.cell, f.log).reserved() == 0,
                  "Root cannot request an unpublished recipe and reserves nothing");
            })
        .thenSucceed();
  }
}
