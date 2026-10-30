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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key mappings, rebindable under Controls → Factory Core. Q9: holding {@link #LABEL_ROW}
 * (Left Alt) while using the Label Tool on a machine labels the whole row instead of one machine.
 * Q10: {@link #LABEL_PICK} (middle mouse) picks a label with the tool in hand.
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

  /** Q10: with the Label Tool in hand, takes the label of the machine, port or Signal looked at. */
  public static final KeyMapping LABEL_PICK =
      new KeyMapping(
          "key.factorycore.label_pick",
          KeyConflictContext.IN_GAME,
          InputConstants.Type.MOUSE,
          GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
          CATEGORY);

  /** Q11/Q12: cycles the overlay (labels, network view, off) with the tool held; unbound. */
  public static final KeyMapping LABEL_OVERLAY =
      new KeyMapping(
          "key.factorycore.label_overlay",
          KeyConflictContext.IN_GAME,
          InputConstants.UNKNOWN,
          CATEGORY);

  private FactoryCoreKeys() {}

  @SubscribeEvent
  public static void register(RegisterKeyMappingsEvent event) {
    event.register(LABEL_ROW);
    event.register(LABEL_PICK);
    event.register(LABEL_OVERLAY);
  }

  /** The hand holding a Label Tool, or null. */
  private static InteractionHand toolHand(Minecraft minecraft) {
    if (minecraft.player == null) return null;
    for (var hand : InteractionHand.values())
      if (minecraft.player.getItemInHand(hand).is(LabelToolContent.ITEM.get())) return hand;
    return null;
  }

  /** Sends a pick for the block looked at; true when one was sent. */
  static boolean pick(Minecraft minecraft) {
    var hand = toolHand(minecraft);
    if (hand == null
        || !(minecraft.hitResult instanceof BlockHitResult hit)
        || hit.getType() != HitResult.Type.BLOCK) return false;
    PacketDistributor.sendToServer(
        new LabelToolContent.PickLabel(hand == InteractionHand.MAIN_HAND, hit.getBlockPos()));
    return true;
  }

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    var minecraft = Minecraft.getInstance();
    while (LABEL_PICK.consumeClick()) if (minecraft.screen == null) pick(minecraft);
    while (LABEL_OVERLAY.consumeClick()) LabelOverlayRenderer.cycle(minecraft);
  }

  /**
   * A use with the Label Tool on a block while the row key is held goes to the row packet. While
   * the pick key shares vanilla Pick Block's key and the tool is in hand, Pick Block is skipped so
   * the hotbar does not change; the pick itself is sent from {@link #tick}.
   */
  @SubscribeEvent
  public static void use(InputEvent.InteractionKeyMappingTriggered event) {
    var minecraft = Minecraft.getInstance();
    if (event.isPickBlock()
        && toolHand(minecraft) != null
        && event.getKeyMapping().getKey().equals(LABEL_PICK.getKey())) {
      event.setSwingHand(false);
      event.setCanceled(true);
      return;
    }
    // Q18(d): survival Pick Block with a Wireless Terminal also asks the network; the server
    // does nothing when the player already holds the block or has no bound terminal.
    if (event.isPickBlock()
        && minecraft.player != null
        && !minecraft.player.isCreative()
        && minecraft.hitResult instanceof BlockHitResult pick
        && pick.getType() == HitResult.Type.BLOCK
        && minecraft
            .player
            .getInventory()
            .contains(
                stack ->
                    stack.is(
                        dev.izzy.factorycore.platform.wireless.WirelessContent.HANDHELD.get())))
      PacketDistributor.sendToServer(
          new dev.izzy.factorycore.platform.wireless.WirelessPick.Pick(pick.getBlockPos()));
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
