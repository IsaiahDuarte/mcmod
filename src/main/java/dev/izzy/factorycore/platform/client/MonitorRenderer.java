package dev.izzy.factorycore.platform.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.izzy.factorycore.platform.monitor.MonitorEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Q28: draws the Storage Monitor's item and its stored count on the front face, at full brightness
 * like a lit screen. The count is short ({@code 1.2k}); "—" while it cannot be read.
 */
public final class MonitorRenderer implements BlockEntityRenderer<MonitorEntity> {
  private final Font font;

  public MonitorRenderer(BlockEntityRendererProvider.Context context) {
    font = context.getFont();
  }

  @Override
  public void render(
      MonitorEntity monitor,
      float partialTick,
      PoseStack pose,
      MultiBufferSource buffers,
      int light,
      int overlay) {
    var key = monitor.shown();
    var level = monitor.getLevel();
    if (key == null || level == null) return;
    var stack = new PlatformResourceCodec(level.registryAccess()).itemStack(key, 1);
    var facing = monitor.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
    pose.pushPose();
    pose.translate(0.5, 0.5, 0.5);
    pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
    pose.translate(0, 0, 0.5005);
    // The item, flat on the screen's upper half.
    pose.pushPose();
    pose.translate(0, 0.1, 0);
    pose.scale(0.4f, 0.4f, 0.001f);
    Minecraft.getInstance()
        .getItemRenderer()
        .renderStatic(
            stack,
            ItemDisplayContext.GUI,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            pose,
            buffers,
            level,
            0);
    pose.popPose();
    // The count under it.
    long count = monitor.count();
    String text = count < 0 ? "—" : CountText.compact(Long.toString(count));
    if (monitor.locked()) text = text + " ⚿";
    pose.translate(0, -0.22, 0.001);
    pose.scale(0.0125f, -0.0125f, 0.0125f);
    font.drawInBatch(
        text,
        -font.width(text) / 2f,
        -font.lineHeight / 2f,
        0xFFFFFFFF,
        false,
        pose.last().pose(),
        buffers,
        Font.DisplayMode.POLYGON_OFFSET,
        0,
        LightTexture.FULL_BRIGHT);
    pose.popPose();
  }
}
