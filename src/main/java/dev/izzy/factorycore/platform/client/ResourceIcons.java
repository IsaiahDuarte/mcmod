package dev.izzy.factorycore.platform.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

/** Shared resource icon drawing for terminal rows and filter slots. */
final class ResourceIcons {
  private ResourceIcons() {}

  /**
   * Draws a registered fluid's still texture with its client tint, as AE2/RS do; returns false
   * (caller falls back to the bucket item) for an unknown fluid or a missing sprite.
   */
  static boolean renderFluid(GuiGraphics graphics, String id, int x, int y) {
    var location = ResourceLocation.tryParse(id);
    if (location == null || !BuiltInRegistries.FLUID.containsKey(location)) return false;
    var fluid = BuiltInRegistries.FLUID.get(location);
    if (fluid == Fluids.EMPTY) return false;
    var stack = new FluidStack(fluid, FluidType.BUCKET_VOLUME);
    var extensions = IClientFluidTypeExtensions.of(fluid);
    var texture = extensions.getStillTexture(stack);
    if (texture == null) return false;
    var sprite =
        net.minecraft.client.Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(texture);
    int tint = extensions.getTintColor(stack);
    graphics.blit(
        x,
        y,
        0,
        16,
        16,
        sprite,
        ((tint >> 16) & 0xff) / 255f,
        ((tint >> 8) & 0xff) / 255f,
        (tint & 0xff) / 255f,
        1f);
    return true;
  }
}
