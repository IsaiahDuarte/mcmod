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
    assertEquals(
        List.of(z, b, a),
        List.of(a, z, b).stream()
            .sorted(
                TerminalSearchSupport.order(
                    TerminalSearchSupport.NAME | TerminalSearchSupport.REVERSED, names::get))
            .toList());
    assertThrows(IllegalArgumentException.class, () -> TerminalSearchSupport.order(3, names::get));
    // 8 is Tall (H2), 16 recipe-viewer sync (Q17), 32 search auto-focus (Q18); 64 is unknown.
    assertThrows(IllegalArgumentException.class, () -> TerminalSearchSupport.order(64, names::get));
    assertThrows(IllegalArgumentException.class, () -> TerminalSearchSupport.order(-1, names::get));
  }

  @Test
  void modSortGroupsByNamespaceThenNameAndReverses() {
    var vanillaZ = new ResourceKey(ResourceKind.ITEM, "minecraft:zinc", new byte[0]);
    var vanillaA = new ResourceKey(ResourceKind.ITEM, "minecraft:apple", new byte[0]);
    var modded = new ResourceKey(ResourceKind.ITEM, "alpha:widget", new byte[0]);
    var names = Map.of(vanillaZ, "Zinc", vanillaA, "Apple", modded, "Widget");
    var rows =
        List.of(
            row(vanillaZ, BigInteger.ONE),
            row(modded, BigInteger.TWO),
            row(vanillaA, BigInteger.TEN));
    var mod =
        rows.stream()
            .sorted(TerminalSearchSupport.order(TerminalSearchSupport.MOD, names::get))
            .map(r -> r.key())
            .toList();
    assertEquals(List.of(modded, vanillaA, vanillaZ), mod);
    var back =
        rows.stream()
            .sorted(
                TerminalSearchSupport.order(
                    TerminalSearchSupport.MOD | TerminalSearchSupport.REVERSED, names::get))
            .map(r -> r.key())
            .toList();
    assertEquals(List.of(vanillaZ, vanillaA, modded), back);
    var quantityUp =
        rows.stream()
            .sorted(
                TerminalSearchSupport.order(
                    TerminalSearchSupport.QUANTITY | TerminalSearchSupport.REVERSED, names::get))
            .map(r -> r.key())
            .toList();
    assertEquals(List.of(vanillaZ, modded, vanillaA), quantityUp);
    // H2: the Tall bit is a client layout preference and never changes the order.
    var tall =
        rows.stream()
            .sorted(
                TerminalSearchSupport.order(
                    TerminalSearchSupport.QUANTITY
                        | TerminalSearchSupport.REVERSED
                        | TerminalSearchSupport.TALL,
                    names::get))
            .map(r -> r.key())
            .toList();
    assertEquals(quantityUp, tall);
  }

  @Test
  void tallRidesInTheSortPreferenceWithoutChangingTheMode() {
    int sort =
        TerminalSearchSupport.MOD | TerminalSearchSupport.REVERSED | TerminalSearchSupport.TALL;
    assertTrue(TerminalSearchSupport.validSort(sort));
    assertEquals(TerminalSearchSupport.MOD, TerminalSearchSupport.mode(sort));
    assertTrue(TerminalSearchSupport.reversed(sort));
    assertTrue(TerminalSearchSupport.tall(sort));
    assertFalse(TerminalSearchSupport.tall(TerminalSearchSupport.MOD));
    assertFalse(
        TerminalSearchSupport.validSort(3 | TerminalSearchSupport.TALL), "mode 3 is unknown");
    // Q17: 16 is the recipe-viewer sync bit; the next bit is still unknown.
    assertTrue(TerminalSearchSupport.validSort(TerminalSearchSupport.SYNC));
    assertTrue(TerminalSearchSupport.sync(TerminalSearchSupport.SYNC | TerminalSearchSupport.MOD));
    assertEquals(
        TerminalSearchSupport.MOD,
        TerminalSearchSupport.mode(TerminalSearchSupport.SYNC | TerminalSearchSupport.MOD));
    // Q18: 32 is search auto-focus; 64 is still unknown.
    assertTrue(TerminalSearchSupport.focus(TerminalSearchSupport.FOCUS));
    assertEquals(
        TerminalSearchSupport.NAME, TerminalSearchSupport.mode(TerminalSearchSupport.FOCUS));
    assertFalse(TerminalSearchSupport.validSort(64), "unknown bits are refused");
    assertFalse(TerminalSearchSupport.validSort(-1));
  }

  private static CatalogCounts.Row row(ResourceKey key, BigInteger amount) {
    return new CatalogCounts.Row(key, amount, BigInteger.ZERO, amount, null);
  }
}
