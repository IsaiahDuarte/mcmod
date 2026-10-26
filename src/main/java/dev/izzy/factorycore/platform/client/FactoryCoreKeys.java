package dev.izzy.factorycore.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key mappings, rebindable under Controls → Factory Core. Q9: holding {@link #LABEL_ROW}
 * (Left Alt) while using the Label Tool on a machine labels the whole row instead of one machine.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class FactoryCoreKeys {
  public static final String CATEGORY = "key.categories.factorycore";

  public static final KeyMapping LABEL_ROW =
      new KeyMapping(
          "key.factorycore.label_row",
          KeyConflictContext.IN_GAME,
          InputConstants.Type.KEYSYM,
          GLFW.GLFW_KEY_LEFT_ALT,
          CATEGORY);

  private FactoryCoreKeys() {}

  @SubscribeEvent
  public static void register(RegisterKeyMappingsEvent event) {
    event.register(LABEL_ROW);
  }

  /** A use with the Label Tool on a block while the row key is held goes to the row packet. */
  @SubscribeEvent
  public static void use(InputEvent.InteractionKeyMappingTriggered event) {
    var minecraft = Minecraft.getInstance();
    if (!event.isUseItem()
        || !LABEL_ROW.isDown()
        || minecraft.player == null
        || !(minecraft.hitResult instanceof BlockHitResult hit)
        || hit.getType() != HitResult.Type.BLOCK
        || !minecraft.player.getItemInHand(event.getHand()).is(LabelToolContent.ITEM.get())) return;
    PacketDistributor.sendToServer(
        new LabelToolContent.LabelRow(
            event.getHand() == InteractionHand.MAIN_HAND, hit.getBlockPos()));
    event.setSwingHand(true);
    event.setCanceled(true);
  }
}
