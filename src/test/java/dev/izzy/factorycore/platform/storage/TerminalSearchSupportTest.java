package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.CatalogCounts;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerminalSearchSupportTest {
  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  @Test
  void namesAndNamespacesMatchCaseInsensitivelyWithoutCollapsingComponents() {
    var codec =
        new PlatformResourceCodec(
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    var stack = new ItemStack(Items.IRON_INGOT);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Factory Reserve"));
    var key = codec.itemKey(stack);
    assertTrue(TerminalSearchSupport.matches(codec, key, "FACTORY RESERVE"));
    assertTrue(TerminalSearchSupport.matches(codec, key, "minecraft:iron"));
    assertTrue(TerminalSearchSupport.matches(codec, key, "@MineCraft"));
    assertFalse(TerminalSearchSupport.matches(codec, key, "@iron"));
    assertFalse(TerminalSearchSupport.matches(codec, key, "missing"));
    assertNotEquals(codec.itemKey(new ItemStack(Items.IRON_INGOT)), key);
  }

  @Test
  void quantitySortUsesArbitraryPrecisionAndStableExactVariantTieBreaks() {
    var first = new ResourceKey(ResourceKind.ITEM, "test:a", new byte[] {1});
    var variant = new ResourceKey(ResourceKind.ITEM, "test:a", new byte[] {2});
    var bigger = new ResourceKey(ResourceKind.ITEM, "test:z", new byte[0]);
    var names = Map.of(first, "Same", variant, "Same", bigger, "Zulu");
    var huge = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
    var a = row(first, huge);
    var b = row(variant, huge);
    var z = row(bigger, huge.add(BigInteger.ONE));
    var ordered =
        new TreeSet<>(TerminalSearchSupport.order(TerminalSearchSupport.QUANTITY, names::get));
    ordered.addAll(List.of(b, a, z));
    assertEquals(List.of(z, a, b), List.copyOf(ordered));
    assertEquals(
        List.of(a, b, z),
        List.of(z, b, a).stream()
            .sorted(TerminalSearchSupport.order(TerminalSearchSupport.NAME, names::get))
            .toList());
    assertThrows(IllegalArgumentException.class, () -> TerminalSearchSupport.order(2, names::get));
  }

  private static CatalogCounts.Row row(ResourceKey key, BigInteger amount) {
    return new CatalogCounts.Row(key, amount, BigInteger.ZERO, amount, null);
  }
}
