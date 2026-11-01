package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.PortContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q8 (QD4): an Exporter's Keep with Craft missing starts one job for what storage lacks, under its
 * applier's grants, then sends the result; refusals show on the Exporter.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ExporterCraftGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 2, 1),
      DRIVE = new BlockPos(1, 2, 2),
      UNIT = new BlockPos(0, 2, 1),
      PORT = new BlockPos(2, 2, 1),
      TARGET = new BlockPos(3, 2, 1);

  private ExporterCraftGameTests() {}

  private record Fixture(
      ServerPlayer owner,
      Net net,
      CrafterEntity unit,
      MachineInterfaceEntity exporter,
      ChestBlockEntity chest,
      ResourceFilter planks) {
    long stock(net.minecraft.world.item.Item item) {
      return net.saved().registry().stock(net.cell(DRIVE, 0), net.key(item)).total();
    }

    int inChest() {
      int count = 0;
      for (int slot = 0; slot < chest.getContainerSize(); slot++)
        count += chest.getItem(slot).getCount();
      return count;
    }

    long jobsOf(UUID principal) {
      return net.saved().craftingJobs().all().stream()
          .filter(job -> job.principal().equals(principal))
          .count();
    }

    String craft() {
      return net.runtime().machinePresets().status(exporter).craft();
    }
  }

  private static Fixture setup(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_LOG));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.OAK_PLANKS, 4), List.of()));
    unit.patterns().insertItem(0, pattern, false);
    h.setBlock(TARGET, Blocks.CHEST);
    h.setBlock(
        PORT,
        PortContent.EXPORTER
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    var position = h.absolutePos(PORT);
    var state = h.getLevel().getBlockState(position);
    state
        .getBlock()
        .setPlacedBy(
            h.getLevel(), position, state, owner, new ItemStack(PortContent.EXPORTER.get()));
    return new Fixture(
        owner,
        net,
        unit,
        h.getBlockEntity(PORT),
        h.getBlockEntity(TARGET),
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Exact(net.key(Items.OAK_PLANKS)))));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    f.net.assertValid(PORT);
    h.assertTrue(f.exporter.handle() != null, "Exporter placed");
  }

  private static void end(Fixture f) {
    f.owner.connection.disconnect(Component.literal("Exporter craft fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void keepWithCraftMissingCraftsOnceThenSends(GameTestHelper h) {
    var f = setup(h);
    long[] seen = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(
            (MachineInterfaceEntity.RESUME_ATTEMPTS + 2) * MachineInterfaceEntity.RESUME_TICKS)
        .thenExecute(
            () -> {
              f.net.saved().registry().insert(f.net.cell(DRIVE, 0), f.net.key(Items.OAK_LOG), 3);
              var deployment =
                  f.exporter.deployPreset(
                      Actors.of(f.owner), MachinePresets.Mode.STOCK, f.planks, 8, true);
              h.assertTrue(deployment.craft(), "Craft missing is saved with the Keep");
            })
        .thenWaitUntil(
            () -> {
              seen[0] = Math.max(seen[0], f.jobsOf(f.owner.getUUID()));
              h.assertTrue(f.jobsOf(f.owner.getUUID()) <= 1, "Never a second job while one runs");
              h.assertTrue(
                  f.inChest() == 8,
                  "The crafted planks reach the chest: " + f.inChest() + " " + f.craft());
            })
        .thenExecute(
            () -> {
              h.assertTrue(seen[0] == 1, "Exactly one job ran");
              h.assertTrue(
                  f.stock(Items.OAK_LOG) == 1 && f.stock(Items.OAK_PLANKS) == 0,
                  "Two logs became the eight planks sent: logs "
                      + f.stock(Items.OAK_LOG)
                      + ", planks "
                      + f.stock(Items.OAK_PLANKS));
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void craftMissingNeedsCraftAndShowsWhyAJobWasRefused(GameTestHelper h) {
    var f = setup(h);
    var builder = UUID.randomUUID();
    var builderActor = new NetworkPermissions.Actor(builder, false);
    var jobs = new ArrayList<UUID>();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenIdle(
            (MachineInterfaceEntity.RESUME_ATTEMPTS + 2) * MachineInterfaceEntity.RESUME_TICKS)
        .thenExecute(
            () -> {
              var network = f.net.runtime().membership(f.net.at(PORT)).scope().network();
              var grants =
                  EnumSet.of(
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.DEPOSIT,
                      NetworkPermissions.Permission.WITHDRAW,
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY);
              f.net.saved().network(network).setGrants(Actors.of(f.owner), builder, grants);
              boolean refused;
              try {
                f.exporter.deployPreset(builderActor, MachinePresets.Mode.STOCK, f.planks, 8, true);
                refused = false;
              } catch (IllegalStateException expected) {
                refused = true;
              }
              h.assertTrue(refused, "Craft missing needs the Craft grant");
              // The owner fills their job limit with jobs that wait for a removed pattern.
              f.net.saved().registry().insert(f.net.cell(DRIVE, 0), f.net.key(Items.OAK_LOG), 16);
              var crafting = WorldCraftingJobs.get(h.getLevel().getServer());
              for (int i = 0; i < 8; i++) {
                var submitted =
                    crafting.submit(
                        Actors.of(f.owner), f.net.at(UNIT), f.net.key(Items.OAK_PLANKS), 4);
                h.assertTrue(submitted.job() != null, "Job " + i + ": " + submitted.reason());
                jobs.add(submitted.job());
              }
              f.unit.patterns().extractItem(0, 1, false);
              f.net
                  .saved()
                  .registry()
                  .extract(f.net.cell(DRIVE, 0), f.net.key(Items.OAK_LOG), 8, null);
              f.exporter.deployPreset(
                  Actors.of(f.owner), MachinePresets.Mode.STOCK, f.planks, 8, true);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    "craft_principal_limit".equals(f.craft()),
                    "The job limit shows on the Exporter: " + f.craft()))
        .thenExecute(
            () -> {
              h.assertTrue(f.inChest() == 0, "Nothing was sent");
              var crafting = WorldCraftingJobs.get(h.getLevel().getServer());
              for (var job : jobs) crafting.cancel(Actors.of(f.owner), job);
              end(f);
            })
        .thenSucceed();
  }
}
