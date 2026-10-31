package dev.izzy.factorycore.jeitesting;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.client.ProgramScreen;
import dev.izzy.factorycore.platform.client.ProgramTextEditor;
import dev.izzy.factorycore.platform.client.TerminalScreen;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.TerminalSearchSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
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
import net.neoforged.neoforge.fluids.FluidStack;
import org.lwjgl.glfw.GLFW;

/**
 * JEI integration on a real client with JEI loaded (ADR 0043). It drives Factory Core's handlers
 * through JEI's own runtime: the terminal transfer handler JEI resolves for the open terminal menu,
 * fed by JEI's recipe layout for the vanilla crafting table, and the program editor ghost handler
 * JEI returns for the screen. Physical drag and the RecipesGui "+" click are not simulated.
 */
final class JeiClientVerification {
  private static final BlockPos ROOT = new BlockPos(1, 65, 1),
      DRIVE = new BlockPos(2, 65, 1),
      TERMINAL = new BlockPos(1, 65, 2),
      PROGRAM = new BlockPos(1, 65, 0),
      CRAFTER = new BlockPos(0, 65, 1),
      BUFFER = new BlockPos(1, 66, 1);
  private static final ResourceLocation CRAFTING_TABLE =
      ResourceLocation.withDefaultNamespace("crafting_table");
  private final Path report = Path.of(System.getProperty("factorycore.client.report"));
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean ready = new AtomicBoolean();

  /** The suite's screenshot names; Gradle requires the same list (captures/jei.txt). */
  private static final Set<String> CAPTURES = manifest();

  private final StringBuilder evidence = new StringBuilder();
  private IRecipeCategory<RecipeHolder<CraftingRecipe>> category;
  private RecipeHolder<CraftingRecipe> recipe;
  private volatile DeviceRegistry.Handle cell;
  private int stage, ticks, entered, panelStage, panelEntered;
  private long processingRevision;

  void register() {
    NeoForge.EVENT_BUS.addListener(this::tick);
  }

  private void tick(ClientTickEvent.Post event) {
    var client = Minecraft.getInstance();
    ticks++;
    // The verification client plays no music (set once the sound system is up).
    if (ticks == 1) {
      client.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
      client.options.save();
    }
    if (stage == 99) return;
    if (failure.get() != null) throw new IllegalStateException("JEI fixture failed", failure.get());
    if (ticks - entered > 1200)
      throw new IllegalStateException(
          "JEI stage timeout: "
              + stage
              + " runtime="
              + (RuntimeCapture.runtime != null)
              + " screen="
              + (client.screen == null ? "none" : client.screen.getClass().getName()));
    switch (stage) {
      case 0 -> {
        if (client.screen instanceof AccessibilityOnboardingScreen onboarding) {
          onboarding.onClose();
          return;
        }
        if (!(client.screen instanceof TitleScreen)) return;
        var window = client.getWindow();
        double ratio = (double) window.getWidth() / Math.max(1, window.getScreenWidth());
        window.setWindowed((int) Math.round(1708 / ratio), (int) Math.round(960 / ratio));
        client.options.guiScale().set(2);
        client.options.pauseOnLostFocus = false;
        client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                "factorycore-jei-verification",
                new LevelSettings(
                    "JEI verification",
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
        next();
      }
      case 1 -> {
        if (client.player == null
            || client.screen != null
            || client.getSingleplayerServer() == null) return;
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
        next();
      }
      case 2 -> {
        var server = client.getSingleplayerServer();
        var principal = client.player.getUUID();
        if (!ready.get()) {
          server.execute(() -> seed(server.getPlayerList().getPlayer(principal)));
          return;
        }
        var runtime = RuntimeCapture.runtime;
        if (runtime == null || ticks - entered < 40) return;
        category = runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING);
        recipe =
            runtime
                .getRecipeManager()
                .createRecipeLookup(RecipeTypes.CRAFTING)
                .get()
                .filter(holder -> holder.id().equals(CRAFTING_TABLE))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JEI lists the crafting table"));
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.UP, TERMINAL, false));
        next();
      }
      case 3 -> {
        if (!(client.screen instanceof TerminalScreen screen)
            || screen.getMenu().view().status() != TerminalPackets.Status.READY
            || screen.getMenu().view().rows().isEmpty()
            || ticks - entered < 20) return;
        var runtime = RuntimeCapture.runtime;
        check(
            runtime.getIngredientListOverlay().isListDisplayed(),
            "JEI shows its ingredient list beside the terminal");
        capture(client, "jei-terminal-overlay.png");
        runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
        next();
      }
      case 4 -> {
        if (client.screen instanceof TerminalScreen
            || client.screen == null
            || ticks - entered < 20) return;
        var runtime = RuntimeCapture.runtime;
        check(
            runtime.getRecipesGui().getParentScreen().orElse(null) instanceof TerminalScreen,
            "JEI recipe view opened over the terminal");
        capture(client, "jei-recipe-over-terminal.png");
        var terminal = (TerminalScreen) runtime.getRecipesGui().getParentScreen().orElseThrow();
        TerminalMenu menu = terminal.getMenu();
        var handler = transfer(client, menu);
        client.setScreen(terminal);
        evidence.append("jei: resolved terminal transfer handler ").append(handler).append('\n');
        next();
      }
      case 5 -> {
        if (!(client.screen instanceof TerminalScreen screen)) return;
        var grid = screen.getMenu().gridView();
        if (panelStage == 0
            && (!grid.result().equals("minecraft:crafting_table") || ticks - entered < 10)) return;
        if (!panelTour(client, screen)) return;
        client.player.closeContainer();
        next();
      }
      case 6 -> {
        if (clearStep < 3) {
          clearOnOpen(client);
          return;
        }
        if (client.screen != null) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(PROGRAM), Direction.UP, PROGRAM, false));
        next();
      }
      case 7 -> {
        if (!(client.screen instanceof ProgramScreen screen) || ticks - entered < 10) return;
        var runtime = RuntimeCapture.runtime;
        IGhostIngredientHandler<ProgramScreen> handler =
            runtime.getScreenHelper().getGhostIngredientHandlers(screen).stream()
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException("JEI resolves the program ghost handler"));
        var editor =
            screen.children().stream()
                .filter(child -> child instanceof ProgramTextEditor)
                .map(child -> (ProgramTextEditor) child)
                .findFirst()
                .orElseThrow();
        editor.setValue("");
        drop(
            handler,
            screen,
            runtime
                .getIngredientManager()
                .createTypedIngredient(
                    VanillaTypes.ITEM_STACK, new ItemStack(Items.IRON_INGOT), false)
                .orElseThrow());
        drop(
            handler,
            screen,
            runtime
                .getIngredientManager()
                .createTypedIngredient(
                    NeoForgeTypes.FLUID_STACK, new FluidStack(Fluids.WATER, 1000), false)
                .orElseThrow());
        check(
            editor.getValue().equals("item(\"minecraft:iron_ingot\") fluid(\"minecraft:water\")"),
            "Ghost drops type item and fluid expressions: " + editor.getValue());
        evidence
            .append("jei: program ghost drops -> ")
            .append(editor.getValue().replace('\n', ' '))
            .append('\n');
        next();
      }
      case 8 -> {
        // Captures read the last rendered frame, so capture a few ticks after the drops.
        if (ticks - entered < 5) return;
        capture(client, "jei-program-ghost.png");
        next();
      }
      case 9 -> {
        client.player.closeContainer();
        // Use the Buffer with an empty hand: the hotbar now holds the patterns made above.
        client.player.getInventory().selected = 8;
        client.player.connection.send(
            new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(8));
        next();
      }
      case 10 -> {
        if (client.screen != null || ticks - entered < 5) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(BUFFER), Direction.UP, BUFFER, false));
        next();
      }
      case 11 -> {
        if (!(client.screen
                instanceof dev.izzy.factorycore.platform.client.BufferSettingsScreen screen)
            || !screen.getMenu().view().editable()
            || ticks - entered < 10) return;
        var runtime = RuntimeCapture.runtime;
        var handlers = runtime.getScreenHelper().getGhostIngredientHandlers(screen);
        IGhostIngredientHandler<dev.izzy.factorycore.platform.client.BufferSettingsScreen> handler =
            handlers.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JEI resolves the filter handler"));
        var ingredient =
            runtime
                .getIngredientManager()
                .createTypedIngredient(
                    VanillaTypes.ITEM_STACK, new ItemStack(Items.IRON_INGOT), false)
                .orElseThrow();
        var targets = handler.getTargetsTyped(screen, ingredient, false);
        check(targets.size() == 1, "One filter drop target");
        targets.getFirst().accept(ingredient.getIngredient());
        handler.onComplete();
        next();
      }
      case 12 -> {
        if (!(client.screen
                instanceof dev.izzy.factorycore.platform.client.BufferSettingsScreen screen)
            || screen.getMenu().view().rules().isEmpty()
            || ticks - entered < 5) return;
        var rule = screen.getMenu().view().rules().getFirst();
        check(rule.icon().equals("minecraft:iron_ingot"), "JEI drop added iron: " + rule);
        capture(client, "jei-buffer-ghost.png");
        evidence
            .append("jei: drag onto Buffer ghost slots adds ")
            .append(rule.label())
            .append('\n');
        next();
      }
      case 13 -> {
        if (ticks - entered < 5) return;
        try {
          Files.writeString(
              report,
              "PASS\n"
                  + evidence
                  + "Handlers are resolved and fed by JEI's own runtime; physical drag and the"
                  + " RecipesGui transfer button click are not simulated.\n");
        } catch (IOException error) {
          throw new IllegalStateException(error);
        }
        stage = 99;
        client.player.closeContainer();
        client.level.disconnect();
        client.disconnect();
        client.stop();
      }
      default -> throw new IllegalStateException("Unknown JEI stage " + stage);
    }
  }

  /** Q5: transfers vanilla recipe {@code id} of {@code type} through the handler JEI resolves. */
  private <R extends net.minecraft.world.item.crafting.Recipe<?>> void transferProcessing(
      Minecraft client,
      TerminalMenu menu,
      mezz.jei.api.recipe.RecipeType<RecipeHolder<R>> type,
      String id) {
    var runtime = RuntimeCapture.runtime;
    var processing = runtime.getRecipeManager().getRecipeCategory(type);
    var holder =
        runtime
            .getRecipeManager()
            .createRecipeLookup(type)
            .get()
            .filter(candidate -> candidate.id().equals(ResourceLocation.withDefaultNamespace(id)))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("JEI lists " + id));
    var handler =
        runtime
            .getRecipeTransferManager()
            .getRecipeTransferHandler(menu, processing)
            .orElseThrow(() -> new IllegalStateException("JEI resolves a handler for " + id));
    var layout =
        runtime
            .getRecipeManager()
            .createRecipeLayoutDrawable(
                processing, holder, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
            .orElseThrow();
    @SuppressWarnings("removal")
    var result =
        handler.transferRecipe(
            menu, holder, layout.getRecipeSlotsView(), client.player, false, true);
    check(result == null, "JEI processing transfer reports no error: " + result);
  }

  private String transfer(Minecraft client, TerminalMenu menu) {
    var runtime = RuntimeCapture.runtime;
    var handler =
        runtime
            .getRecipeTransferManager()
            .getRecipeTransferHandler(menu, category)
            .orElseThrow(
                () -> new IllegalStateException("JEI resolves Factory Core's terminal handler"));
    var layout =
        runtime
            .getRecipeManager()
            .createRecipeLayoutDrawable(
                category, recipe, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
            .orElseThrow();
    @SuppressWarnings("removal")
    var preview =
        handler.transferRecipe(
            menu, recipe, layout.getRecipeSlotsView(), client.player, false, false);
    check(preview == null, "JEI transfer preview reports no error: " + preview);
    @SuppressWarnings("removal")
    var result =
        handler.transferRecipe(
            menu, recipe, layout.getRecipeSlotsView(), client.player, false, true);
    check(result == null, "JEI transfer reports no error: " + result);
    return handler.getClass().getName();
  }

  /** The action-bar text, read from the HUD (dev mappings name the field). */
  private static String overlay(Minecraft client) {
    try {
      var field = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
      field.setAccessible(true);
      var value = (Component) field.get(client.gui);
      return value == null ? "" : value.getString();
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("Action bar not readable", error);
    }
  }

  private int clearStep;

  /**
   * Q99 (M111): reopens the terminal with clear-on-open set and JEI's search at "dirt"; the
   * terminal empties it. Then turns the option off again and closes the terminal.
   */
  private void clearOnOpen(Minecraft client) {
    switch (clearStep) {
      case 0 -> {
        if (client.screen != null) return;
        client.gameMode.useItemOn(
            client.player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(TERMINAL), Direction.UP, TERMINAL, false));
        clearStep = 1;
        entered = ticks;
      }
      case 1 -> {
        if (!(client.screen instanceof TerminalScreen screen)
            || !screen.getMenu().viewReceived()
            || screen.getMenu().view().status() != TerminalPackets.Status.READY
            || ticks - entered < 10) return;
        var text = RuntimeCapture.runtime.getIngredientFilter().getFilterText();
        check(text.isEmpty(), "Opening the terminal emptied JEI's search: " + text);
        evidence.append(
            "jei: clear-on-open emptied JEI's search (dirt) when the terminal opened\n");
        check(press(screen, "J", 1), "Right-click on J again");
        clearStep = 2;
      }
      case 2 -> {
        if (!(client.screen instanceof TerminalScreen screen)
            || TerminalSearchSupport.clearViewer(screen.getMenu().view().sort())) return;
        client.player.closeContainer();
        clearStep = 3;
        entered = ticks;
      }
      default -> {}
    }
  }

  private boolean panelTour(Minecraft client, TerminalScreen screen) {
    var grid = screen.getMenu().gridView();
    if (ticks - panelEntered < 6) return false;
    switch (panelStage) {
      case 0 -> {
        check(screen.craftingOpen(), "JEI fill automatically opens the collapsed crafting panel");
        long planks =
            grid.cells().stream().filter(cell -> cell.equals("minecraft:oak_planks")).count();
        check(planks == 4, "JEI chooses four stocked oak plank ghosts");
        check(
            client.player.getInventory().countItem(Items.CRAFTING_TABLE) == 0,
            "JEI fill alone produces no real output");
        capture(client, "jei-terminal-filled.png");
        client.options.guiScale().set(4);
        client.resizeDisplay();
      }
      case 1 -> {
        check(client.getWindow().getGuiScale() == 4, "JEI panel runs at the minimum GUI scale");
        capture(client, "jei-crafting-panel-scale-4.png");
        if (!press(screen, "Encode")) return false;
      }
      case 2 -> {
        if (grid.status() != TerminalCraftingPackets.GridStatus.ENCODED
            || client.player.getInventory().countItem(CraftingContent.CRAFTING_PATTERN.get()) != 1)
          return false;
        check(
            client.player.getInventory().countItem(CraftingContent.CRAFTING_PATTERN.get()) == 1,
            "JEI-filled table recipe encodes one crafting pattern");
        if (!press(screen, "Craft")) return false;
      }
      case 3 -> {
        if (grid.status() != TerminalCraftingPackets.GridStatus.CRAFTED
            || client.player.getInventory().countItem(Items.CRAFTING_TABLE) != 1) return false;
        check(
            client.player.getInventory().countItem(Items.CRAFTING_TABLE) == 1,
            "JEI-filled recipe crafts one real table");
        if (!press(screen, "Inputs")) return false;
      }
      case 4 -> {
        if (!grid.processing()) return false;
        if (!press(screen, "Outputs")) return false;
      }
      case 5 -> {
        var rows = screen.getMenu().view().rows();
        int stone = -1;
        for (int row = 0; row < Math.min(8, rows.size()); row++)
          if (rows.get(row).icon().equals("minecraft:stone")) stone = row;
        if (stone < 0 || screen.getMenu().view().busy()) return false;
        screen.keyPressed(GLFW.GLFW_KEY_1 + stone, 0, 0);
        if (!press(screen, "Use selected")) return false;
      }
      case 6 -> {
        if (!grid.cells().get(9).equals("minecraft:stone")) return false;
        capture(client, "jei-processing-panel-scale-4.png");
        if (!press(screen, "Encode")) return false;
      }
      case 7 -> {
        if (grid.status() != TerminalCraftingPackets.GridStatus.ENCODED) return false;
        var patterns =
            client.player.getInventory().items.stream()
                .filter(stack -> stack.has(CraftingContent.PROCESSING.get()))
                .toList();
        if (patterns.isEmpty()) return false;
        check(patterns.size() == 1, "Processing authoring works with JEI loaded");
        var contents = patterns.getFirst().get(CraftingContent.PROCESSING.get());
        check(
            contents.inputs().size() == 4
                && contents.inputs().stream()
                    .allMatch(stack -> stack.is(Items.OAK_PLANKS) && stack.getCount() == 1)
                && contents.outputs().size() == 1
                && contents.outputs().getFirst().is(Items.STONE),
            "Declared processing ghosts encode exact inputs/output");
        check(
            client.player.getInventory().countItem(CraftingContent.BLANK_PATTERN.get()) == 0,
            "Each encoder consumes one blank");
        if (!ready.compareAndSet(true, false)) return false;
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        server.execute(
            () -> {
              try {
                var player = server.getPlayerList().getPlayer(id);
                var codec = new PlatformResourceCodec(player.registryAccess());
                var registry = DeviceSavedData.get(server).registry();
                check(
                    registry.stock(cell, codec.itemKey(new ItemStack(Items.OAK_PLANKS))).total()
                            == 12
                        && registry.stock(cell, codec.itemKey(new ItemStack(Items.STONE))).total()
                            == 1,
                    "Only the actual craft consumes stock; encoding preserves all declared resources");
                ready.set(true);
              } catch (RuntimeException error) {
                failure.set(error);
              }
            });
      }
      case 8 -> {
        if (!ready.get()) return false;
        if (!press(screen, "« Crafting")) return false;
      }
      case 9 -> {
        check(!screen.craftingOpen(), "Panel stays collapsed with processing ghosts");
        transfer(client, screen.getMenu());
      }
      case 10 -> {
        if (grid.processing()
            || grid.status() != TerminalCraftingPackets.GridStatus.FILLED
            || !screen.craftingOpen()) return false;
        check(
            grid.result().equals("minecraft:crafting_table"),
            "A second JEI transfer replaces the processing declaration with its table recipe");
        var table =
            screen.children().stream()
                .filter(
                    child ->
                        child instanceof Button button
                            && button.visible
                            && button.getMessage().getString().equals("Table"))
                .map(child -> (Button) child)
                .findFirst()
                .orElseThrow();
        check(!table.active, "JEI fill selects the Table tab");
        evidence.append(
            "jei: fill reveals crafting panel; real Craft and table Encode; visible Inputs/Outputs author a processing pattern at scale 4; exact stock 12 planks + 1 stone, one crafted table, one pattern of each kind; second JEI transfer reopens Table from collapsed Outputs.\n");
        // A5: the Ctrl branch of the transfer button, fed JEI's own recipe layout.
        var runtime = RuntimeCapture.runtime;
        var layout =
            runtime
                .getRecipeManager()
                .createRecipeLayoutDrawable(
                    category,
                    recipe,
                    runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElseThrow();
        check(
            dev.izzy.factorycore.platform.client.integration.jei.JeiRequests.request(
                screen.getMenu(), layout.getRecipeSlotsView()),
            "The crafting table recipe has an item output");
      }
      case 11 -> {
        var jobs = screen.getMenu().jobView();
        if (!screen.jobsMode()
            || jobs.craftables().stream()
                .noneMatch(entry -> entry.icon().equals("minecraft:crafting_table"))) return false;
        capture(client, "jei-request-jobs.png");
        evidence.append(
            "jei: Ctrl-click request opens Jobs with the recipe's output (crafting table)"
                + " selected for an amount.\n");
        // Q5: a furnace recipe through JEI's universal handler fills the processing grid.
        processingRevision = grid.gridRevision();
        transferProcessing(client, screen.getMenu(), RecipeTypes.SMELTING, "glass");
      }
      case 12 -> {
        if (grid.gridRevision() == processingRevision
            || grid.status() != TerminalCraftingPackets.GridStatus.FILLED) return false;
        check(
            grid.processing()
                && grid.cells().get(0).equals("minecraft:red_sand")
                && grid.amount(0) == 1
                && grid.cells().get(9).equals("minecraft:glass")
                && grid.amount(9) == 1
                && grid.cells().get(1).isEmpty(),
            "A furnace recipe fills processing Inputs and Outputs with the stocked red sand: "
                + grid);
        check(
            overlay(client)
                .equals(
                    Component.translatable("gui.factorycore.terminal.jei.switched_processing")
                        .getString()),
            "The Table-to-processing switch is announced: " + overlay(client));
        capture(client, "jei-processing-transfer.png");
        processingRevision = grid.gridRevision();
        transferProcessing(
            client, screen.getMenu(), RecipeTypes.BLASTING, "iron_ingot_from_blasting_raw_iron");
      }
      case 13 -> {
        if (grid.gridRevision() == processingRevision
            || grid.status() != TerminalCraftingPackets.GridStatus.FILLED) return false;
        check(
            grid.processing()
                && grid.cells().get(0).equals("minecraft:raw_iron")
                && grid.cells().get(9).equals("minecraft:iron_ingot"),
            "A blasting recipe replaces it: " + grid);
        evidence.append(
            "jei: the universal handler fills processing Inputs/Outputs from a furnace recipe"
                + " (red sand, the plentiful option -> glass) and a blasting recipe (raw iron ->"
                + " iron ingot), announcing the switch from Table.\n");
        // Q112: a crafting recipe on a processing grid switches back to Table and says so.
        processingRevision = grid.gridRevision();
        transfer(client, screen.getMenu());
        panelStage = 30;
        panelEntered = ticks;
        return false;
      }
      case 30 -> {
        if (grid.gridRevision() == processingRevision || grid.processing()) return false;
        check(
            overlay(client)
                .equals(
                    Component.translatable("gui.factorycore.terminal.jei.switched_table")
                        .getString()),
            "The processing-to-Table switch is announced: " + overlay(client));
        evidence.append("jei: a crafting recipe on the processing grid announces the switch.\n");
        // Q17: the J tool turns on search sync.
        check(press(screen, "J"), "The search-sync tool is shown with JEI installed");
        panelStage = 14;
        panelEntered = ticks;
        return false;
      }
      case 14 -> {
        if (!TerminalSearchSupport.sync(screen.getMenu().view().sort())) return false;
        screen.typeSearch("cobble");
      }
      case 15 -> {
        var filter = RuntimeCapture.runtime.getIngredientFilter();
        if (!filter.getFilterText().equals("cobble")) return false;
        filter.setFilterText("stone");
      }
      case 16 -> {
        if (!screen.searchText().equals("stone")
            || !screen.getMenu().view().search().equals("stone")) return false;
        capture(client, "jei-search-sync.png");
        evidence.append(
            "jei: search sync copies the terminal search into JEI (cobble) and JEI's back into"
                + " the terminal, which searches it on the server (stone).\n");
        // Q99 (M111): right-click J asks for JEI's search to be emptied when the terminal opens.
        check(press(screen, "J", 1), "Right-click on J");
      }
      case 17 -> {
        if (!TerminalSearchSupport.clearViewer(screen.getMenu().view().sort())) return false;
        check(
            screen.children().stream()
                .anyMatch(
                    child ->
                        child instanceof Button button
                            && button.getMessage().getString().equals("J")
                            && button.getMessage().getStyle().isUnderlined()),
            "J is underlined while clear-on-open is set");
        check(press(screen, "J"), "Sync turns off");
      }
      case 18 -> {
        if (TerminalSearchSupport.sync(screen.getMenu().view().sort())) return false;
        // Left in JEI on purpose: reopening the terminal must empty it.
        RuntimeCapture.runtime.getIngredientFilter().setFilterText("dirt");
        screen.typeSearch("");
        return true;
      }
      default -> throw new IllegalStateException("Unknown panel stage " + panelStage);
    }
    panelStage++;
    panelEntered = ticks;
    return false;
  }

  private static boolean press(TerminalScreen screen, String name) {
    return press(screen, name, 0);
  }

  private static boolean press(TerminalScreen screen, String name, int mouse) {
    for (var child : screen.children())
      if (child instanceof Button button
          && button.visible
          && button.active
          && button.getMessage().getString().equals(name))
        return screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0,
            button.getY() + button.getHeight() / 2.0,
            mouse);
    return false;
  }

  private static <I> void drop(
      IGhostIngredientHandler<ProgramScreen> handler,
      ProgramScreen screen,
      ITypedIngredient<I> ingredient) {
    var targets = handler.getTargetsTyped(screen, ingredient, false);
    check(targets.size() == 1, "One editor drop target");
    var area = targets.getFirst().getArea();
    check(
        area.getWidth() > 0
            && area.getX() >= 0
            && area.getY() >= 0
            && area.getX() + area.getWidth() <= screen.width
            && area.getY() + area.getHeight() <= screen.height,
        "Drop target lies on screen");
    targets.getFirst().accept(ingredient.getIngredient());
    handler.onComplete();
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
    // A5: the network can craft the JEI recipe's output, so Ctrl-click can request it.
    level.setBlock(CRAFTER, CraftingContent.CRAFTER.get().defaultBlockState(), 3);
    // B1: a Buffer whose ghost filter slots take a JEI drop.
    level.setBlock(
        BUFFER,
        dev.izzy.factorycore.platform.storage.InterfaceContent.state(
            dev.izzy.factorycore.platform.storage.InterfaceKind.BUFFER, Direction.NORTH),
        3);
    ((dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity) level.getBlockEntity(BUFFER))
        .place(
            new ItemStack(dev.izzy.factorycore.platform.storage.InterfaceContent.ITEM.get()),
            player.getUUID());
    var cells =
        new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
    for (int cell : new int[] {0, 1, 3, 4}) cells.set(cell, new ItemStack(Items.OAK_PLANKS));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new dev.izzy.factorycore.platform.crafting.PatternContents(
            java.util.UUID.randomUUID(),
            1,
            0,
            cells,
            new ItemStack(Items.CRAFTING_TABLE),
            java.util.List.of()));
    ((dev.izzy.factorycore.platform.crafting.CrafterEntity) level.getBlockEntity(CRAFTER))
        .patterns()
        .insertItem(0, pattern, false);
    player.getInventory().clearContent();
    player.getInventory().setItem(22, new ItemStack(CraftingContent.BLANK_PATTERN.get(), 2));
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
      var planks =
          new PlatformResourceCodec(player.registryAccess())
              .itemKey(new ItemStack(Items.OAK_PLANKS));
      check(
          DeviceSavedData.get(player.server).registry().insert(cell, planks, 16).amount() == 16,
          "Seed sixteen oak planks");
      check(
          DeviceSavedData.get(player.server)
                  .registry()
                  .insert(
                      cell,
                      new PlatformResourceCodec(player.registryAccess())
                          .itemKey(new ItemStack(Items.STONE)),
                      1)
                  .amount()
              == 1,
          "Seed processing output sample");
      // Q112: more red sand than sand, so the glass transfer must choose red sand.
      check(
          DeviceSavedData.get(player.server)
                  .registry()
                  .insert(
                      cell,
                      new PlatformResourceCodec(player.registryAccess())
                          .itemKey(new ItemStack(Items.RED_SAND)),
                      8)
                  .amount()
              == 8,
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
    System.out.println("FACTORYCORE_JEI_STAGE " + stage);
  }

  private static Set<String> manifest() {
    try (var stream = JeiClientVerification.class.getResourceAsStream("/captures/jei.txt")) {
      if (stream == null) throw new IllegalStateException("No capture manifest for jei");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read capture manifest jei", error);
    }
  }

  private void capture(Minecraft client, String name) {
    try {
      Files.createDirectories(report.getParent().resolve("screenshots"));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot prepare screenshot directory", error);
    }
    if (!CAPTURES.contains(name))
      throw new IllegalStateException(name + " is not listed in captures/jei.txt");
    evidence.append(name).append('\n');
    Screenshot.grab(
        report.getParent().toFile(),
        name,
        client.getMainRenderTarget(),
        component -> System.out.println("FACTORYCORE_JEI_SCREENSHOT " + component.getString()));
  }
}
