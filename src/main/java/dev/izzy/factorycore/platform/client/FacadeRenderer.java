package dev.izzy.factorycore.platform.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.izzy.factorycore.platform.network.FacadeEntity;
import dev.izzy.factorycore.platform.network.FacadeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Q30: draws a faced cable as its block, a hair larger than the cell so the cable's own arm ends
 * never show through; and the facade item as the same block.
 */
public final class FacadeRenderer implements BlockEntityRenderer<FacadeEntity> {
  private static final float GROW = 0.002f;

  public FacadeRenderer(BlockEntityRendererProvider.Context context) {}

  static void block(
      BlockState state, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
    Minecraft.getInstance()
        .getBlockRenderer()
        .renderSingleBlock(state, pose, buffers, light, overlay, ModelData.EMPTY, null);
  }

  @Override
  public void render(
      FacadeEntity facade,
      float partialTick,
      PoseStack pose,
      MultiBufferSource buffers,
      int light,
      int overlay) {
    var shown = facade.shown();
    if (shown == null) return;
    pose.pushPose();
    pose.translate(-GROW / 2, -GROW / 2, -GROW / 2);
    pose.scale(1 + GROW, 1 + GROW, 1 + GROW);
    block(shown.defaultBlockState(), pose, buffers, light, overlay);
    pose.popPose();
  }

  /** The facade item, drawn as its block with the item model's block transforms. */
  static final class Item extends BlockEntityWithoutLevelRenderer {
    Item() {
      super(
          Minecraft.getInstance().getBlockEntityRenderDispatcher(),
          Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(
        ItemStack stack,
        ItemDisplayContext context,
        PoseStack pose,
        MultiBufferSource buffers,
        int light,
        int overlay) {
      var shown = FacadeItem.shown(stack);
      if (shown != null) block(shown.defaultBlockState(), pose, buffers, light, overlay);
    }
  }
}
