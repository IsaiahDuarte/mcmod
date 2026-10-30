package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.storage.LabelOverlay;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws the server's {@link LabelOverlay} snapshot while the Label Tool is held. Q11 labels: each
 * label as a see-through tag above its block, and a faint dot on unlabelled machines that touch a
 * cable. Q12 network view: an outline per block, coloured by branch, and a white one on each
 * machine a port reaches. The overlay key cycles labels, network view and off. Client thread only.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class LabelOverlayRenderer {
  /** Text colours: a label, and the faint marker of an unlabelled machine. */

  /** Branch colours as RGB, cycled for networks with more branches. */
  private static final float[][] BRANCHES = {
    {0.25f, 0.85f, 1f},
    {1f, 0.6f, 0.15f},
    {0.4f, 0.95f, 0.35f},
    {0.95f, 0.4f, 0.95f},
    {1f, 0.95f, 0.3f},
    {0.45f, 0.5f, 1f},
    {1f, 0.35f, 0.35f}
  };

  private static final float[] MACHINE = {1f, 1f, 1f};

  private static final float SCALE = 0.025f;

  private static List<LabelOverlay.Entry> entries = List.of();
  private static LabelOverlay.Mode mode = LabelOverlay.Mode.LABELS;

  private LabelOverlayRenderer() {}

  static void receive(LabelOverlay.Snapshot snapshot) {
    entries = snapshot.entries();
  }

  /** The entries shown now (for verification). */
  public static List<LabelOverlay.Entry> entries() {
    return entries;
  }

  /** The overlay key: labels, then the network view, then off; the server is told. */
  static void cycle(Minecraft minecraft) {
    mode = mode.next();
    PacketDistributor.sendToServer(new LabelOverlay.SetMode(mode));
    if (minecraft.player != null)
      minecraft.player.displayClientMessage(
          Component.translatable(
              "message.factorycore.label_tool."
                  + switch (mode) {
                    case LABELS -> "overlay_on";
                    case NETWORK -> "overlay_network";
                    case OFF -> "overlay_off";
                  }),
          true);
  }

  private static boolean holding(Minecraft minecraft) {
    return minecraft.player != null
        && (minecraft.player.getMainHandItem().is(LabelToolContent.ITEM.get())
            || minecraft.player.getOffhandItem().is(LabelToolContent.ITEM.get()));
  }

  private static boolean text(LabelOverlay.Entry entry) {
    return entry.kind() == LabelOverlay.Kind.LABEL || entry.kind() == LabelOverlay.Kind.MARKER;
  }

  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
    var minecraft = Minecraft.getInstance();
    if (mode == LabelOverlay.Mode.OFF || entries.isEmpty() || !holding(minecraft)) return;
    var camera = event.getCamera();
    var eye = camera.getPosition();
    var pose = event.getPoseStack();
    var buffers = minecraft.renderBuffers().bufferSource();
    var lines = buffers.getBuffer(RenderType.lines());
    pose.pushPose();
    pose.translate(-eye.x, -eye.y, -eye.z);
    for (var entry : entries) {
      if (text(entry)) continue;
      var kind = entry.kind();
      double inflate =
          kind == LabelOverlay.Kind.CABLE ? -0.3 : kind == LabelOverlay.Kind.GATEWAY ? 0.04 : 0.002;
      var color =
          kind == LabelOverlay.Kind.MACHINE ? MACHINE : BRANCHES[entry.branch() % BRANCHES.length];
      LevelRenderer.renderLineBox(
          pose,
          lines,
          new AABB(BlockPos.of(entry.pos())).inflate(inflate),
          color[0],
          color[1],
          color[2],
          1f);
    }
    pose.popPose();
    var font = minecraft.font;
    int background = (int) (minecraft.options.getBackgroundOpacity(0.25f) * 255f) << 24;
    double range = LabelOverlay.RANGE;
    for (var entry : entries) {
      if (!text(entry)) continue;
      var at = BlockPos.of(entry.pos());
      double x = at.getX() + 0.5 - eye.x, y = at.getY() + 1.3 - eye.y, z = at.getZ() + 0.5 - eye.z;
      if (x * x + y * y + z * z > range * range) continue;
      boolean labelled = entry.kind() == LabelOverlay.Kind.LABEL;
      String shown = labelled ? entry.label() : "•";
      pose.pushPose();
      pose.translate(x, y, z);
      pose.mulPose(camera.rotation());
      // As vanilla name tags in 1.21.1: a negative x would mirror the text onto its culled side.
      pose.scale(SCALE, -SCALE, SCALE);
      font.drawInBatch(
          shown,
          -font.width(shown) / 2f,
          0,
          labelled ? Panels.OVERLAY_LABEL : Panels.OVERLAY_MARKER,
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
