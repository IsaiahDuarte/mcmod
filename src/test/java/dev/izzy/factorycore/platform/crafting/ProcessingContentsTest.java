package dev.izzy.factorycore.platform.crafting;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProcessingContentsTest {
  private static RegistryAccess registries;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
  }

  private static ProcessingContents ore(UUID id) {
    return new ProcessingContents(
        id,
        2,
        3,
        List.of(new ItemStack(Items.RAW_IRON, 2), new ItemStack(Items.COAL)),
        List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.FLINT)));
  }

  @Test
  void codecsRoundTripAndPortablePatternSplitsPrimaryAndByproducts() {
    var id = UUID.randomUUID();
    var value = ore(id);
    assertEquals(value, ore(id));
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    assertEquals(
        value,
        ProcessingContents.CODEC
            .parse(ops, ProcessingContents.CODEC.encodeStart(ops, value).getOrThrow())
            .getOrThrow());
    var buffer =
        new RegistryFriendlyByteBuf(
            Unpooled.buffer(),
            registries,
            net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
    try {
      ProcessingContents.STREAM_CODEC.encode(buffer, value);
      assertEquals(value, ProcessingContents.STREAM_CODEC.decode(buffer));
    } finally {
      buffer.release();
    }
    var codec = new PlatformResourceCodec(registries);
    var pattern = value.pattern(codec);
    assertEquals(Pattern.Type.PROCESSING, pattern.type());
    assertEquals(codec.itemKey(new ItemStack(Items.IRON_INGOT)), pattern.output());
    assertEquals(2, pattern.outputAmount());
    assertEquals(2L, pattern.inputs().get(codec.itemKey(new ItemStack(Items.RAW_IRON))));
    assertEquals(Map.of(codec.itemKey(new ItemStack(Items.FLINT)), 1L), pattern.byproducts());
  }

  @Test
  void invalidProcessingContentsReject() {
    var id = UUID.randomUUID();
    var ingot = List.of(new ItemStack(Items.IRON_INGOT));
    assertThrows(
        IllegalArgumentException.class, () -> new ProcessingContents(id, 1, 0, List.of(), ingot));
    assertThrows(
        IllegalArgumentException.class, () -> new ProcessingContents(id, 1, 0, ingot, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessingContents(
                id,
                1,
                0,
                ingot,
                List.of(
                    new ItemStack(Items.FLINT),
                    new ItemStack(Items.FLINT),
                    new ItemStack(Items.FLINT),
                    new ItemStack(Items.FLINT))));
    assertThrows(
        IllegalArgumentException.class, () -> new ProcessingContents(id, 0, 0, ingot, ingot));
    var codec = new PlatformResourceCodec(registries);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessingContents(
                    id,
                    1,
                    0,
                    List.of(new ItemStack(Items.RAW_IRON)),
                    List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT)))
                .pattern(codec),
        "Primary output cannot repeat as a byproduct");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessingContents(id, 1, 0, ingot, ingot).pattern(codec),
        "Output cannot be its own input");
  }

  private static net.neoforged.neoforge.fluids.FluidStack fluid(
      net.minecraft.world.level.material.Fluid fluid, int amount) {
    return new net.neoforged.neoforge.fluids.FluidStack(fluid, amount);
  }

  @Test
  void fluidEntriesRoundTripAndBecomeFluidKeysInThePattern() {
    var id = UUID.randomUUID();
    var water = net.minecraft.world.level.material.Fluids.WATER;
    var lava = net.minecraft.world.level.material.Fluids.LAVA;
    var value =
        new ProcessingContents(
            id,
            1,
            0,
            List.of(new ItemStack(Items.COBBLESTONE)),
            List.of(new ItemStack(Items.STONE)),
            List.of(fluid(water, 1_000)),
            List.of(fluid(lava, 250)));
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    assertEquals(
        value,
        ProcessingContents.CODEC
            .parse(ops, ProcessingContents.CODEC.encodeStart(ops, value).getOrThrow())
            .getOrThrow());
    var buffer =
        new RegistryFriendlyByteBuf(
            Unpooled.buffer(),
            registries,
            net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
    try {
      ProcessingContents.STREAM_CODEC.encode(buffer, value);
      assertEquals(value, ProcessingContents.STREAM_CODEC.decode(buffer));
    } finally {
      buffer.release();
    }
    var codec = new PlatformResourceCodec(registries);
    var pattern = value.pattern(codec);
    var waterKey = codec.fluidKey(fluid(water, 1));
    var lavaKey = codec.fluidKey(fluid(lava, 1));
    assertEquals(1_000L, pattern.inputs().get(waterKey));
    assertEquals(codec.itemKey(new ItemStack(Items.STONE)), pattern.output(), "Items are primary");
    assertEquals(Map.of(lavaKey, 250L), pattern.byproducts());
    var fluidOnly =
        new ProcessingContents(
                id,
                1,
                0,
                List.of(),
                List.of(),
                List.of(fluid(water, 500)),
                List.of(fluid(lava, 100)))
            .pattern(codec);
    assertEquals(lavaKey, fluidOnly.output(), "Without item outputs the first fluid is primary");
  }

  @Test
  void fluidBoundsAndLegacyItemOnlyEncodingStillHold() {
    var id = UUID.randomUUID();
    var water = net.minecraft.world.level.material.Fluids.WATER;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessingContents(
                id,
                1,
                0,
                List.of(),
                List.of(),
                List.of(fluid(water, 16_001)),
                List.of(fluid(water, 1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessingContents(
                id, 1, 0, List.of(), List.of(), List.of(), List.of(fluid(water, 1))),
        "A pattern still needs at least one input");
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    // An encoding from before fluid support has no fluid fields and must still decode.
    var legacy = ProcessingContents.CODEC.encodeStart(ops, ore(id)).getOrThrow().getAsJsonObject();
    legacy.remove("fluid_inputs");
    legacy.remove("fluid_outputs");
    assertEquals(ore(id), ProcessingContents.CODEC.parse(ops, legacy).getOrThrow());
  }
}
