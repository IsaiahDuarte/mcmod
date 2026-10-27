package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.storage.LabelOverlay;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Q11: draws the server's {@link LabelOverlay} snapshot while the Label Tool is held: each label as
 * a see-through tag above its block, and a faint dot on unlabelled machines that touch a cable. The
 * toggle key hides it. Client thread only.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class LabelOverlayRenderer {
  /** Text colours: a label, and the faint marker of an unlabelled machine. */
  private static final int LABEL = 0xffffffff, MARKER = 0x90ffffff;

  private static final float SCALE = 0.025f;

  private static List<LabelOverlay.Entry> entries = List.of();
  private static boolean hidden;

  private LabelOverlayRenderer() {}

  static void receive(LabelOverlay.Snapshot snapshot) {
    entries = snapshot.entries();
  }

  /** The entries shown now (for verification). */
  public static List<LabelOverlay.Entry> entries() {
    return entries;
  }

  static void toggle(Minecraft minecraft) {
    hidden = !hidden;
    if (minecraft.player != null)
      minecraft.player.displayClientMessage(
          Component.translatable(
              "message.factorycore.label_tool." + (hidden ? "overlay_off" : "overlay_on")),
          true);
  }

  private static boolean holding(Minecraft minecraft) {
    return minecraft.player != null
        && (minecraft.player.getMainHandItem().is(LabelToolContent.ITEM.get())
            || minecraft.player.getOffhandItem().is(LabelToolContent.ITEM.get()));
  }

  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
    var minecraft = Minecraft.getInstance();
    if (hidden || entries.isEmpty() || !holding(minecraft)) return;
    var camera = event.getCamera();
    var eye = camera.getPosition();
    var pose = event.getPoseStack();
    var buffers = minecraft.renderBuffers().bufferSource();
    var font = minecraft.font;
    int background = (int) (minecraft.options.getBackgroundOpacity(0.25f) * 255f) << 24;
    double range = LabelOverlay.RANGE;
    for (var entry : entries) {
      var at = BlockPos.of(entry.pos());
      double x = at.getX() + 0.5 - eye.x, y = at.getY() + 1.3 - eye.y, z = at.getZ() + 0.5 - eye.z;
      if (x * x + y * y + z * z > range * range) continue;
      boolean labelled = !entry.label().isEmpty();
      String text = labelled ? entry.label() : "•";
      pose.pushPose();
      pose.translate(x, y, z);
      pose.mulPose(camera.rotation());
      // As vanilla name tags in 1.21.1: a negative x would mirror the text onto its culled side.
      pose.scale(SCALE, -SCALE, SCALE);
      font.drawInBatch(
          text,
          -font.width(text) / 2f,
          0,
          labelled ? LABEL : MARKER,
          false,
          pose.last().pose(),
          buffers,
          Font.DisplayMode.SEE_THROUGH,
          labelled ? background : 0,
          LightTexture.FULL_BRIGHT);
      pose.popPose();
    }
    buffers.endBatch();
  }
}
