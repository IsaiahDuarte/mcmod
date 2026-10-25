package dev.izzy.factorycore.platform.crafting;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PatternContentsTest {
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private static List<ItemStack> cake() {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int i = 0; i < 3; i++) cells.set(i, new ItemStack(Items.MILK_BUCKET));
    var sugar = new ItemStack(Items.SUGAR);
    sugar.set(DataComponents.CUSTOM_NAME, Component.literal("Exact sugar"));
    cells.set(3, sugar);
    cells.set(4, new ItemStack(Items.EGG));
    cells.set(5, sugar.copy());
    for (int i = 6; i < 9; i++) cells.set(i, new ItemStack(Items.WHEAT));
    return cells;
  }

  private static PatternContents contents(UUID id) {
    return new PatternContents(
        id, 3, 5, cake(), new ItemStack(Items.CAKE), List.of(new ItemStack(Items.BUCKET, 3)));
  }

  @Test
  void codecsRoundTripExactCellsOutputAndRemaindersWithValueEquality() {
    var id = UUID.randomUUID();
    var value = contents(id);
    assertEquals(value, contents(id));
    assertNotEquals(value, contents(UUID.randomUUID()));
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    var json = PatternContents.CODEC.encodeStart(ops, value).getOrThrow();
    assertEquals(value, PatternContents.CODEC.parse(ops, json).getOrThrow());
    var buffer =
        new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
    try {
      PatternContents.STREAM_CODEC.encode(buffer, value);
      assertEquals(value, PatternContents.STREAM_CODEC.decode(buffer));
      assertFalse(buffer.isReadable());
    } finally {
      buffer.release();
    }
    var cells = value.cells();
    cells.get(0).setCount(5);
    assertEquals(1, value.cells().get(0).getCount(), "Exposed stacks are copies");
  }

  @Test
  void portablePatternCountsExactKeysAndRemainders() {
    var codec = new PlatformResourceCodec(registries);
    var pattern = contents(new UUID(0, 9)).pattern(codec);
    var sugar = codec.itemKey(cake().get(3));
    assertEquals(Pattern.Type.CRAFTING, pattern.type());
    assertEquals(3, pattern.version());
    assertEquals(5, pattern.priority());
    assertEquals(3L, pattern.inputs().get(codec.itemKey(new ItemStack(Items.MILK_BUCKET))));
    assertEquals(2L, pattern.inputs().get(sugar));
    assertNull(
        pattern.inputs().get(codec.itemKey(new ItemStack(Items.SUGAR))),
        "Component identity stays exact");
    assertEquals(codec.itemKey(new ItemStack(Items.CAKE)), pattern.output());
    assertEquals(1, pattern.outputAmount());
    assertEquals(Map.of(codec.itemKey(new ItemStack(Items.BUCKET)), 3L), pattern.byproducts());
  }

  @Test
  void invalidContentsReject() {
    var id = UUID.randomUUID();
    var empty = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternContents(id, 1, 0, empty, new ItemStack(Items.CAKE), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PatternContents(
                id, 1, 0, cake().subList(0, 8), new ItemStack(Items.CAKE), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternContents(id, 1, 0, cake(), ItemStack.EMPTY, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternContents(id, 0, 0, cake(), new ItemStack(Items.CAKE), List.of()));
    var thick = cake();
    thick.set(0, new ItemStack(Items.MILK_BUCKET, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternContents(id, 1, 0, thick, new ItemStack(Items.CAKE), List.of()));
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    var json = PatternContents.CODEC.encodeStart(ops, contents(id)).getOrThrow().getAsJsonObject();
    json.addProperty("version", 0);
    assertTrue(PatternContents.CODEC.parse(ops, json).isError(), "Invalid saved data fails decode");
  }

  @Test
  void substitutesRoundTripAndBecomeDisjointAlternatives() {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    var alternatives = new ArrayList<List<String>>(Collections.nCopies(9, List.<String>of()));
    cells.set(0, new ItemStack(Items.OAK_PLANKS));
    cells.set(3, new ItemStack(Items.OAK_PLANKS));
    alternatives.set(0, List.of("minecraft:birch_planks", "minecraft:spruce_planks"));
    alternatives.set(3, List.of("minecraft:birch_planks"));
    var value =
        new PatternContents(
            new UUID(0, 3), 1, 0, cells, new ItemStack(Items.STICK, 4), List.of(), alternatives);
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    assertEquals(
        value,
        PatternContents.CODEC
            .parse(ops, PatternContents.CODEC.encodeStart(ops, value).getOrThrow())
            .getOrThrow());
    var buffer =
        new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
    try {
      PatternContents.STREAM_CODEC.encode(buffer, value);
      assertEquals(value, PatternContents.STREAM_CODEC.decode(buffer));
    } finally {
      buffer.release();
    }
    var codec = new PlatformResourceCodec(registries);
    var pattern = value.pattern(codec);
    assertEquals(
        Map.of(
            codec.itemKey(new ItemStack(Items.OAK_PLANKS)),
            List.of(codec.itemKey(new ItemStack(Items.BIRCH_PLANKS)))),
        pattern.substitutes(),
        "Cells sharing oak keep only what both allow");
    assertTrue(
        contents(new UUID(0, 4)).pattern(codec).substitutes().isEmpty(), "Exact stays exact");
  }
}
