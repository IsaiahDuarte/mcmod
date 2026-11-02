package dev.izzy.factorycore.emitesting;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiDragDropHandlers;
import dev.emi.emi.registry.EmiExclusionAreas;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.client.BufferSettingsScreen;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.ProgramTextEditor;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.client.UpgradeSlotWidget;
import dev.izzy.factorycore.platform.client.integration.emi.EmiRequests;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.TerminalSearchSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Q16: the EMI plugin on a real client with EMI loaded and JEI absent (ADR 0069). Fills go through
 * EMI's own {@code EmiRecipeFiller.performFill} with the recipes EMI lists, and drops through EMI's
 * {@code EmiDragDropHandlers.dropStack}, so EMI resolves Factory Core's handlers as a click would.
 * Physical drags and the fill button click are not simulated; Ctrl is called directly, as JEI's run
 * does.
 */
final class EmiClientVerification {
  private static final BlockPos ROOT = new BlockPos(1, 65, 1),
      DRIVE = new BlockPos(2, 65, 1),
      TERMINAL = new BlockPos(1, 65, 2),
      PROGRAM = new BlockPos(1, 65, 0),
      CRAFTER = new BlockPos(0, 65, 1),
      BUFFER = new BlockPos(1, 66, 1);
  private static final Set<String> CAPTURES = manifest();
  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean ready = new AtomicBoolean();
  private final StringBuilder evidence = new StringBuilder();
  private volatile DeviceRegistry.Handle cell;
  private int stage, ticks, entered, captureAt;
  private long revision;
  private String pending;
  private Runnable then;

  void register() {
    // Q147: written for the Items view; the default All view has its own presentation check.
    dev.izzy.factorycore.platform.client.TerminalScreen.startKindForTest(0);
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    var client = Minecraft.getInstance();
    ticks++;
    if (ticks == 1) {
      client.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
      client.options.save();
    }
    if (stage == 99) return;
    if (failure.get() != null) throw new IllegalStateException("EMI fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "EMI stage timeout: "
              + stage
              + " loaded="
              + EmiReloadManager.isLoaded()
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    if (pending != null) {
      // Captures read the last rendered frame, so wait a few frames, then continue.
      if (ticks < captureAt) return;
      capture(client, pending);
      pending = null;
      var action = then;
      then = null;
      action.run();
      return;
    }
    if (step(client)) next();
  }

  /** Captures {@code name} once the screen has redrawn, then runs {@code action}. */
  private void later(String name, Runnable action) {
    pending = name;
    captureAt = ticks + 5;
    then = action;
  }

  /** Runs the current stage; true moves to the next one. */
  private boolean step(Minecraft client) {
    switch (stage) {
      case 0 -> {
        if (client.screen instanceof AccessibilityOnboardingScreen onboarding) {
          onboarding.onClose();
          return false;
        }
        if (!(client.screen instanceof TitleScreen)) return false;
        var window = client.getWindow();
        double ratio = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
        window.setWindowed((int) Math.round(1708 / ratio), (int) Math.round(960 / ratio));
        client.options.guiScale().set(2);
        client.options.pauseOnLostFocus = false;
        client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                "factorycore-emi-verification",
                new LevelSettings(
                    "EMI verification",
                    GameType.CREATIVE,
                    false,
                    Difficulty.PEACEFUL,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.DEFAULT),
                new WorldOptions(0x7E4A1L, false, false),
                registries ->
                    registries
                        .registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT)
                        .value()
                        .createWorldDimensions(),
                client.screen);
        return true;
      }
      case 1 -> {
        if (client.player == null
            || client.screen != null
            || client.getSingleplayerServer() == null) return false;
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        server.execute(
            () -> {
              try {
                setup(server.getPlayerList().getPlayer(principal));
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
        return true;
      }
      case 2 -> {
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        if (!ready.get()) {
          server.execute(() -> seed(server.getPlayerList().getPlayer(principal)));
          return false;
        }
        if (!EmiReloadManager.isLoaded() || ticks - entered < 40) return false;
        use(client, TERMINAL);
        return true;
      }
      case 3 -> {
        var screen = terminal(client);
        if (screen == null
            || screen.getMenu().view().status() != TerminalPackets.Status.READY
            || screen.getMenu().view().rows().isEmpty()
            || ticks - entered < 20) return false;
        check(
            EmiRecipeFiller.getAllHandlers(screen).size() == 1,
            "EMI resolves Factory Core's terminal handler");
        later(
            "emi-terminal.png",
            () -> {
              fill(screen, VanillaEmiRecipeCategories.CRAFTING, "crafting_table");
            });
        return true;
      }
      case 4 -> {
        var screen = terminal(client);
        if (screen == null || !filled(screen) || !screen.craftingOpen()) return false;
        var grid = screen.getMenu().gridView();
        check(
            !grid.processing()
                && grid.table() == PatternContents.Table.CRAFTING.ordinal()
                && grid.result().equals("minecraft:crafting_table")
                && grid.cells().get(0).equals("minecraft:oak_planks")
                && grid.cells().get(4).equals("minecraft:oak_planks")
                && grid.cells().get(2).isEmpty(),
            "EMI's crafting table recipe fills the Table grid with the stocked planks: " + grid);
        later(
            "emi-crafting-fill.png",
            () -> {
              evidence.append(
                  "emi: crafting fill -> Table grid, oak planks, result crafting table\n");
              check(
                  EmiRequests.request(
                      screen.getMenu(),
                      recipe(VanillaEmiRecipeCategories.CRAFTING, "crafting_table")),
                  "The crafting table recipe has an item output");
            });
        return true;
      }
      case 5 -> {
        var screen = terminal(client);
        if (screen == null
            || !screen.jobsMode()
            || screen.getMenu().jobView().craftables().stream()
                .noneMatch(entry -> entry.icon().equals("minecraft:crafting_table"))) return false;
        later(
            "emi-request-jobs.png",
            () -> {
              evidence.append("emi: Ctrl request opens Jobs with the crafting table selected\n");
              fill(screen, VanillaEmiRecipeCategories.SMELTING, "glass");
            });
        return true;
      }
      case 6 -> {
        var screen = terminal(client);
        if (screen == null || !filled(screen) || !screen.craftingOpen()) return false;
        var grid = screen.getMenu().gridView();
        check(
            grid.processing()
                && grid.cells().get(0).equals("minecraft:red_sand")
                && grid.amount(0) == 1
                && grid.cells().get(9).equals("minecraft:glass")
                && grid.amount(9) == 1,
            "A furnace recipe fills processing Inputs/Outputs with the stocked red sand: " + grid);
        later(
            "emi-processing-fill.png",
            () -> {
              evidence.append(
                  "emi: smelting fill -> processing, red sand (plentiful option) -> glass\n");
              fill(
                  screen,
                  VanillaEmiRecipeCategories.STONECUTTING,
                  "stone_bricks_from_stone_stonecutting");
            });
        return true;
      }
      case 7 -> {
        var screen = terminal(client);
        if (screen == null || !filled(screen) || !screen.craftingOpen()) return false;
        var grid = screen.getMenu().gridView();
        check(
            !grid.processing()
                && grid.table() == PatternContents.Table.STONECUTTING.ordinal()
                && grid.cells().get(0).equals("minecraft:stone")
                && grid.result().equals("minecraft:stone_bricks"),
            "A stonecutter recipe fills the stonecutter and chooses its result: " + grid);
        later(
            "emi-stonecutter-fill.png",
            () -> {
              fill(screen, VanillaEmiRecipeCategories.SMITHING, "netherite_sword_smithing");
            });
        return true;
      }
      case 8 -> {
        var screen = terminal(client);
        if (screen == null || !filled(screen) || !screen.craftingOpen()) return false;
        var grid = screen.getMenu().gridView();
        check(
            grid.table() == PatternContents.Table.SMITHING.ordinal()
                && grid.cells().get(0).equals("minecraft:netherite_upgrade_smithing_template")
                && grid.cells().get(1).equals("minecraft:diamond_sword")
                && grid.cells().get(2).equals("minecraft:netherite_ingot")
                && grid.result().equals("minecraft:netherite_sword"),
            "A smithing recipe fills template, base and addition: " + grid);
        later(
            "emi-smithing-fill.png",
            () -> {
              evidence.append(
                  "emi: stonecutter (stone bricks) and smithing (netherite sword) fills\n");
              check(press(screen, "Sync"), "The search-sync tool is shown with EMI installed");
            });
        return true;
      }
      case 9 -> {
        var screen = terminal(client);
        if (screen == null || !TerminalSearchSupport.sync(screen.getMenu().view().sort()))
          return false;
        screen.typeSearch("cobble");
        return true;
      }
      case 10 -> {
        if (!EmiApi.getSearchText().equals("cobble")) return false;
        EmiApi.setSearchText("stone");
        return true;
      }
      case 11 -> {
        var screen = terminal(client);
        if (screen == null
            || !screen.searchText().equals("stone")
            || !screen.getMenu().view().search().equals("stone")
            || ticks - entered < 5) return false;
        later(
            "emi-search-sync.png",
            () -> {
              evidence.append(
                  "emi: search sync copies cobble into EMI and stone back to the terminal\n");
              check(press(screen, "Sync"), "Sync turns off");
            });
        return true;
      }
      case 12 -> {
        var screen = terminal(client);
        if (screen == null) return false;
        // The press is not retried: the stage times out if the server drops it (Q127).
        if (TerminalSearchSupport.sync(screen.getMenu().view().sort())) return false;
        EmiApi.setSearchText("");
        screen.typeSearch("");
        client.player.closeContainer();
        return true;
      }
      case 13 -> {
        if (client.screen != null || ticks - entered < 5) return false;
        use(client, PROGRAM);
        return true;
      }
      case 14 -> {
        if (!(client.screen instanceof ProgramScreen screen) || ticks - entered < 10) return false;
        var editor =
            screen.children().stream()
                .filter(child -> child instanceof ProgramTextEditor)
                .map(child -> (ProgramTextEditor) child)
                .findFirst()
                .orElseThrow();
        editor.setValue("");
        var area = screen.editorArea();
        int x = area.getX() + area.getWidth() / 2, y = area.getY() + area.getHeight() / 2;
        check(
            EmiDragDropHandlers.dropStack(screen, EmiStack.of(Items.IRON_INGOT), x, y),
            "EMI drops an item on the program editor");
        check(
            EmiDragDropHandlers.dropStack(screen, EmiStack.of(Fluids.WATER, 1000), x, y),
            "EMI drops a fluid on the program editor");
        check(
            !EmiDragDropHandlers.dropStack(screen, EmiStack.of(Items.IRON_INGOT), 0, 0),
            "A drop outside the editor is left to EMI");
        check(
            editor.getValue().equals("iron ingot fluid water"),
            "Drops type the item and fluid as programs name them: " + editor.getValue());
        evidence.append("emi: program drops -> ").append(editor.getValue()).append('\n');
        return true;
      }
      case 15 -> {
        if (ticks - entered < 5) return false;
        later(
            "emi-program-drop.png",
            () -> {
              client.player.closeContainer();
              client.player.getInventory().selected = 8;
              client.player.connection.send(
                  new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(8));
            });
        return true;
      }
      case 16 -> {
        if (client.screen != null || ticks - entered < 5) return false;
        use(client, BUFFER);
        return true;
      }
      case 17 -> {
        if (!(client.screen instanceof BufferSettingsScreen screen)
            || !screen.getMenu().view().editable()
            || ticks - entered < 10) return false;
        var area = screen.filterArea();
        check(area != null, "The Buffer shows its filter slots");
        check(
            EmiDragDropHandlers.dropStack(
                screen, EmiStack.of(Items.IRON_INGOT), area.getX() + 4, area.getY() + 4),
            "EMI drops onto the Buffer's filter slots");
        // Q19: the upgrade panel beside device screens is kept clear of EMI's lists.
        var bounds = new ArrayList<dev.emi.emi.api.widget.Bounds>();
        for (var exclusion : EmiExclusionAreas.generic) exclusion(exclusion, screen, bounds);
        long slots =
            screen.children().stream()
                .filter(child -> child instanceof UpgradeSlotWidget slot && slot.visible)
                .count();
        check(
            slots > 0 && bounds.size() == slots,
            "The upgrade panel is an EMI exclusion area: " + bounds);
        evidence.append("emi: upgrade panel excluded: ").append(bounds).append('\n');
        return true;
      }
      case 18 -> {
        if (!(client.screen instanceof BufferSettingsScreen screen)
            || screen.getMenu().view().rules().isEmpty()
            || ticks - entered < 5) return false;
        var rule = screen.getMenu().view().rules().getFirst();
        check(rule.icon().equals("minecraft:iron_ingot"), "EMI drop added iron: " + rule);
        later(
            "emi-buffer-drop.png",
            () -> {
              evidence
                  .append("emi: drop onto Buffer filter adds ")
                  .append(rule.label())
                  .append('\n');
            });
        return true;
      }
      case 19 -> {
        if (ticks - entered < 5) return false;
        try {
          Files.writeString(
              report,
              "PASS\n"
                  + evidence
                  + "Fills and drops go through EMI's own filler and drag-drop registries; the"
                  + " physical click and drag are not simulated.\n");
        } catch (IOException error) {
          throw new IllegalStateException(error);
        }
        stage = 99;
        client.player.closeContainer();
        client.level.disconnect();
        client.disconnect();
        client.stop();
        return false;
      }
      default -> throw new IllegalStateException("Unknown EMI stage " + stage);
    }
  }

  @SuppressWarnings("unchecked")
  private static void exclusion(
      dev.emi.emi.api.EmiExclusionArea<?> area,
      net.minecraft.client.gui.screens.Screen screen,
      List<dev.emi.emi.api.widget.Bounds> into) {
    ((dev.emi.emi.api.EmiExclusionArea<net.minecraft.client.gui.screens.Screen>) area)
        .addExclusionArea(screen, into::add);
  }

  private static TerminalScreen terminal(Minecraft client) {
    return client.screen instanceof TerminalScreen screen ? screen : null;
  }

  private boolean filled(TerminalScreen screen) {
    var grid = screen.getMenu().gridView();
    return grid.gridRevision() != revision
        && grid.status() == TerminalCraftingPackets.GridStatus.FILLED;
  }

  /** Fills the terminal from EMI's recipe {@code id} of {@code category}, as its button does. */
  private void fill(AbstractContainerScreen<?> screen, EmiRecipeCategory category, String id) {
    revision = ((TerminalScreen) screen).getMenu().gridView().gridRevision();
    var recipe = recipe(category, id);
    check(EmiRecipeFiller.isSupported(recipe), "EMI shows a fill button on " + id);
    check(
        EmiRecipeFiller.performFill(
            recipe, screen, EmiCraftContext.Type.FILL_BUTTON, EmiCraftContext.Destination.NONE, 1),
        "EMI fills " + id);
  }

  private static EmiRecipe recipe(EmiRecipeCategory category, String id) {
    var wanted = ResourceLocation.withDefaultNamespace(id);
    return EmiApi.getRecipeManager().getRecipes(category).stream()
        .filter(
            recipe ->
                wanted.equals(recipe.getId())
                    || recipe.getBackingRecipe() != null
                        && wanted.equals(recipe.getBackingRecipe().id()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("EMI lists " + id));
  }

  private static void use(Minecraft client, BlockPos pos) {
    client.gameMode.useItemOn(
        client.player,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
  }

  private static boolean press(TerminalScreen screen, String name) {
    for (var child : screen.children())
      if (child instanceof Button button
          && button.visible
          && button.active
          && button.getMessage().getString().equals(name))
        return screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
    return false;
  }

  private void setup(ServerPlayer player) {
    var level = player.serverLevel();
    level.setDayTime(6000);
    for (int x = -2; x < 8; x++)
      for (int z = -2; z < 8; z++)
        level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 3);
    level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(player.getUUID());
    level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    cell = CellHandleData.read(drive.cell(0));
    level.setBlock(TERMINAL, TerminalContent.BLOCK.get().defaultBlockState(), 3);
    level.setBlock(PROGRAM, ProgramContent.BLOCK.get().defaultBlockState(), 3);
    level.setBlock(CRAFTER, CraftingContent.CRAFTER.get().defaultBlockState(), 3);
    level.setBlock(BUFFER, InterfaceContent.state(InterfaceKind.BUFFER, Direction.NORTH), 3);
    ((BufferedInterfaceEntity) level.getBlockEntity(BUFFER))
        .place(new ItemStack(InterfaceContent.ITEM.get()), player.getUUID());
    // The network crafts crafting tables, so the Ctrl request has a pattern to show.
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    for (int index : new int[] {0, 1, 3, 4}) cells.set(index, new ItemStack(Items.OAK_PLANKS));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.CRAFTING_TABLE), List.of()));
    ((dev.izzy.factorycore.platform.crafting.CrafterEntity) level.getBlockEntity(CRAFTER))
        .patterns()
        .insertItem(0, pattern, false);
    player.getInventory().clearContent();
    player.teleportTo(4.5, 65, 4.5);
    player.setYRot(135);
    player.setXRot(10);
  }

  /** Seeds once the terminal is usable, i.e. the network has validated. */
  private void seed(ServerPlayer player) {
    try {
      if (ready.get()
          || !((TerminalEntity) player.serverLevel().getBlockEntity(TERMINAL)).canUse(player))
        return;
      var codec = new PlatformResourceCodec(player.registryAccess());
      var registry = DeviceSavedData.get(player.server).registry();
      check(
          registry.insert(cell, codec.itemKey(new ItemStack(Items.OAK_PLANKS)), 16).amount() == 16,
          "Seed oak planks");
      // More red sand than sand, so the glass fill must choose red sand.
      check(
          registry.insert(cell, codec.itemKey(new ItemStack(Items.RED_SAND)), 8).amount() == 8,
          "Seed red sand");
      ready.set(true);
    } catch (RuntimeException error) {
      failure.set(error);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private void next() {
    stage++;
    entered = ticks;
    System.out.println("FACTORYCORE_EMI_STAGE " + stage);
  }

  private static Set<String> manifest() {
    try (var stream = EmiClientVerification.class.getResourceAsStream("/captures/emi.txt")) {
      if (stream == null) throw new IllegalStateException("No capture manifest for emi");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read capture manifest emi", error);
    }
  }

  private void capture(Minecraft client, String name) {
    try {
      Files.createDirectories(report.getParent().resolve("screenshots"));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot prepare screenshot directory", error);
    }
    if (!CAPTURES.contains(name))
      throw new IllegalStateException(name + " is not listed in captures/emi.txt");
    evidence.append(name).append('\n');
    Screenshot.grab(
        report.getParent().toFile(),
        name,
        client.getMainRenderTarget(),
        component -> System.out.println("FACTORYCORE_EMI_SCREENSHOT " + component.getString()));
  }
}
