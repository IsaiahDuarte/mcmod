package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.resource.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ResourceFilterGameTests {
  private ResourceFilterGameTests() {}

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void nativeItemTagsIgnoreComponentsWhileExactRulesPreserveThem(
      GameTestHelper helper) {
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    var tags = new PlatformFilterTags(helper.getLevel().getServer());
    var plain = new ItemStack(Items.OAK_LOG);
    var named = plain.copy();
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Tagged variant"));
    var logs =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Tag("minecraft:logs")));
    helper.assertTrue(
        logs.matches(codec.itemKey(plain), tags)
            && logs.matches(codec.itemKey(named), tags)
            && !logs.matches(codec.itemKey(new ItemStack(Items.IRON_INGOT)), tags),
        "Actual loaded vanilla log tags match both component variants and exclude ingots");
    var exact =
        new ResourceFilter(
            ResourceKind.ITEM,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Exact(codec.itemKey(named))));
    helper.assertTrue(
        exact.matches(codec.itemKey(named), tags) && !exact.matches(codec.itemKey(plain), tags),
        "Exact native components remain distinct");
    helper.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void nativeFluidTagsAndMissingTagsAreExplicitlyScoped(GameTestHelper helper) {
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    var tags = new PlatformFilterTags(helper.getLevel().getServer());
    var water = codec.fluidKey(new FluidStack(Fluids.WATER, 1000));
    var lava = codec.fluidKey(new FluidStack(Fluids.LAVA, 1000));
    var filter =
        new ResourceFilter(
            ResourceKind.FLUID,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Tag("minecraft:water")));
    helper.assertTrue(
        filter.matches(water, tags)
            && !filter.matches(lava, tags)
            && !filter.matches(ResourceKey.ENERGY, tags),
        "Actual fluid tags remain separate from lava and FE");
    var missing =
        new ResourceFilter(
            ResourceKind.FLUID,
            ResourceFilter.Mode.INCLUDE,
            List.of(new ResourceFilter.Tag("factorycore:nonexistent")));
    helper.assertTrue(
        !missing.matches(water, tags),
        "Missing tag matches nothing instead of broadening an allowlist");
    helper.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 100)
  public static void nativeTagQueriesRejectWorkerThreadsBeforeRegistryAccess(
      GameTestHelper helper) {
    var tags = new PlatformFilterTags(helper.getLevel().getServer());
    var key =
        new PlatformResourceCodec(helper.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.OAK_LOG));
    var result =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                tags.contains(ResourceKind.ITEM, "minecraft:logs", key);
                return false;
              } catch (IllegalStateException expected) {
                return true;
              }
            });
    helper
        .startSequence()
        .thenWaitUntil(() -> helper.assertTrue(result.isDone(), "Worker thread attempt completes"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    result.join(),
                    "Tag adapter refuses off-thread access before native registry lookup"))
        .thenSucceed();
  }
}
