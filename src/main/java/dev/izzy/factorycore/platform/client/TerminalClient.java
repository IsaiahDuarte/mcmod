package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceContent;
import dev.izzy.factorycore.platform.storage.ConnectorContent;
import dev.izzy.factorycore.platform.storage.RecoveryContent;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class TerminalClient {
  private TerminalClient() {}

  /** A5: job notices become toasts over open screens (see {@link JobToast}). */
  @SubscribeEvent
  public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
    dev.izzy.factorycore.platform.crafting.JobNotice.sink(JobToast::receive);
    dev.izzy.factorycore.platform.ClientScreens.install(new ItemScreens());
    dev.izzy.factorycore.platform.storage.LabelOverlay.sink(LabelOverlayRenderer::receive);
    dev.izzy.factorycore.platform.storage.MachineInspection.sink(InspectScreen::open);
  }

  /** Q29: the cable stripe (tint index 0) takes the cable's colour, in the world and as an item. */
  @SubscribeEvent
  public static void blockColors(
      net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block event) {
    event.register(
        (state, level, position, tint) ->
            tint == 0 ? 0xFF000000 | NetworkCableBlock.color(state).tint() : -1,
        NetworkContent.CABLE.get());
  }

  @SubscribeEvent
  public static void itemColors(
      net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
    event.register(
        (stack, tint) -> tint == 0 ? 0xFF000000 | NetworkCableBlock.color(stack).tint() : -1,
        NetworkContent.CABLE_ITEM.get());
  }

  /** Q28: the Storage Monitor's face. */
  @SubscribeEvent
  public static void renderers(
      net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
    event.registerBlockEntityRenderer(
        dev.izzy.factorycore.platform.monitor.MonitorContent.ENTITY.get(), MonitorRenderer::new);
    event.registerBlockEntityRenderer(NetworkContent.FACADE_ENTITY.get(), FacadeRenderer::new);
  }

  /** Q30: the facade item is drawn as the block it shows. */
  @SubscribeEvent
  public static void clientExtensions(
      net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
    event.registerItem(
        new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() {
          private FacadeRenderer.Item renderer;

          @Override
          public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if (renderer == null) renderer = new FacadeRenderer.Item();
            return renderer;
          }
        },
        NetworkContent.FACADE_ITEM.get());
  }

  @SubscribeEvent
  public static void screens(RegisterMenuScreensEvent event) {
    event.register(TerminalContent.MENU.get(), TerminalScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.PortableContent.MENU.get(),
        PortableStorageScreen::new);
    event.register(StorageContent.DRIVE_MENU.get(), DriveScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.PortContent.IMPORTER_MENU.get(), PortScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.PortContent.EXPORTER_MENU.get(), PortScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.PortContent.CABLE_PORT_MENU.get(),
        CablePortScreen::new);
    event.register(ConnectorContent.MENU.get(), ConnectorSettingsScreen::new);
    event.register(BufferedInterfaceContent.MENU.get(), BufferSettingsScreen::new);
    event.register(ProgramContent.MENU.get(), ProgramScreen::new);
    event.register(NetworkContent.POLICY_MENU.get(), GatewayPolicyScreen::new);
    event.register(
        dev.izzy.factorycore.platform.storage.EnergyBankContent.MENU.get(), EnergyBankScreen::new);
    event.register(NetworkContent.ACCESS_MENU.get(), NetworkAccessScreen::new);
    event.register(NetworkContent.OVERVIEW_MENU.get(), OverviewScreen::new);
    event.register(RecoveryContent.MENU.get(), RecoveryScreen::new);
    event.register(
        dev.izzy.factorycore.platform.signal.SignalContent.MENU.get(), SignalScreen::new);
    event.register(
        dev.izzy.factorycore.platform.crafting.CraftingContent.CRAFTER_MENU.get(),
        CrafterScreen::new);
  }
}
