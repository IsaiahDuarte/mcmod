# Super Factory Manager (SFM): feature inventory from the source code

Source: `https://github.com/TeamDman/SuperFactoryManager`, shallow clone of branch `main`, commit `e2ba833b2b235835d69475b641e24b22bfd9551a` (2026-10-21, "gametest: add manual FE reproduction controls").

## How to read this file

**1.21.1 baseline.** The released 1.21.1 build is the generated projection `platform/minecraft/projections/sfm-4.35.0/mc-1.21.1`. It contains mod 4.35.0 and NeoForge 21.1.206. In `platform/minecraft/projections.json` it is listed as `environment: release`, with features `recipe_namespace`, `release_version_4_35_0` and `quantity_sensitive_extraction_progress`. Every other `{% if features.X %}` block in `platform/minecraft/core-liquid-template` is off for that build.

**Build exclusions.** The file `gradle/source-excludes/1.21.1/main-java.txt` also leaves the following out of the 1.21.1 build:
- `BatteryBlock` and `BatteryBlockEntity`
- the AE2 `InterfaceCapabilityProvider`
- `GasResourceType`, `InfuseResourceType`, `PigmentResourceType` and `SlurryResourceType`
- `resourcetype/exclude/**` (MekanismEnergyResourceType)

**Status labels**
- **[R]**: released for 1.21.1. The code is present and active in the `sfm-4.35.0/mc-1.21.1` projection.
- **[OV]**: other version only. It is present in another release projection (for example 1.19.2 or 1.20.x) but not in 1.21.1.
- **[X]**: experimental or flagged. It sits behind a feature flag that is off for every release projection (for example it is only in `sfm-dev`), or it is gated to the IDE or dev environment (`SFMEnvironmentUtils.isInIDE()`).

**Path prefixes.** All paths are relative to the repository root.
- `J:` = `platform/minecraft/projections/sfm-4.35.0/mc-1.21.1/src/main/java/ca/teamdman/`
- `RS:` = `platform/minecraft/projections/sfm-4.35.0/mc-1.21.1/src/main/resources/`
- `GEN:` = `platform/minecraft/projections/sfm-4.35.0/mc-1.21.1/src/generated/resources/`
- `G4:` = `platform/minecraft/projections/sfm-4.35.0/mc-1.21.1/src/main/antlr/sfml/SFML.g4`
- `T:` = `platform/minecraft/core-liquid-template/src/main/` (the Liquid-templated source; used for [X] items)

**Format.** Each line is: name — syntax — behaviour — source — status.

---

## Language — triggers

- Program name — `NAME "text"` (optional, must come first) — sets the program name. On a successful compile it is also written to the disk's item name. — G4 (`program`, `name`); J:sfm/common/item/DiskItem.java — [R]
- Program structure — `NAME? trigger* EOF` — a program is a list of triggers. There are no top-level statements outside triggers. — G4 — [R]
- Timer trigger — `EVERY <interval> DO <statements> END` — runs its block when `tick % interval == offset`. — G4 `TimerTrigger`; J:sfml/ast/TimerTrigger.java, Interval.java — [R]
- Interval units — `TICKS | TICK | SECONDS | SECOND` — a SECOND is 20 ticks. The offset is multiplied by 20 too when the unit is seconds. — J:sfml/ast/ASTBuilder.java `visitIntervalSpace` — [R]
- Number-less interval — `EVERY TICK DO … END` — the number is optional and defaults to 1. It is still subject to the minimum-interval check. — ASTBuilder — [R]
- Local (manager-clock) alignment — `EVERY 20 TICKS` — compares `manager.tick % ticks == offset`. The manager tick counter starts at 0 when the block is placed and is re-randomised (`level.random.nextInt()`) whenever the block entity loads from disk. — Interval.java; J:sfm/common/blockentity/ManagerBlockEntity.java `loadAdditional` — [R]
- Global (world-clock) alignment — `EVERY 20 GLOBAL TICKS`, also `G` as an alias for `GLOBAL` — compares `level.getGameTime() % ticks == offset`. Use it to synchronise several managers. — Interval.java — [R]
- Offset — `EVERY 20 PLUS 1 TICKS`, `EVERY 20 + 1 TICKS`, `EVERY 20 GLOBAL PLUS 1 TICKS`, `EVERY 20 G + 1 TICKS` — phase offset within the period. There is no validation that the offset is smaller than the period; an offset >= the period never fires. — G4 `interval`; Interval.java — [R]
- Shorthand global interval — `EVERY 20G TICKS`, `EVERY 20G+1 TICKS` — `NUMBER_WITH_G_SUFFIX` implies GLOBAL alignment. — G4 `IntervalNoSpace` — [R]
- Minimum timer interval — server config `timerTriggerMinimumIntervalInTicks` (default 20) — a shorter interval is a compile error ("Minimum trigger interval is N ticks."). — ASTBuilder `visitTimerTrigger`; J:sfm/common/config/SFMServerConfig.java — [R]
- Energy-only fast timers — server config `timerTriggerMinimumIntervalInTicksWhenOnlyForgeEnergyIOStatementsPresent` (default 1) — applies when every IO resource in the trigger is `sfm:forge_energy` (or `sfm:mekanism_energy`). A trigger with no IO also qualifies, because `allMatch` on an empty set is true. — TimerTrigger.java `usesOnlyForgeEnergyResourceIO` — [R]
- Redstone pulse trigger — `EVERY REDSTONE PULSE DO … END` — fires on rising edges of redstone power at the manager or at the block directly above it. Pulses are queued (counted). On the next manager tick the block runs once per queued pulse, then the queue is cleared. A `TRIGGERED` blockstate debounces the edge. — G4 `PulseTrigger`; J:sfml/ast/RedstoneTrigger.java; J:sfm/common/block/ManagerBlock.java `neighborChanged` — [R]
- Trigger isolation — each trigger runs in a forked `ProgramContext`. Inputs never carry over between triggers or between ticks. — J:sfml/ast/Program.java `tick` — [R]
- Multiple triggers per tick — all triggers whose condition holds run in program order within one manager tick. — Program.java — [R]
- Per-trigger timing — the elapsed time of each trigger is logged at INFO ("Program trigger tick took %.2f ms"). — Program.java — [R]
- Worded offset — `EVERY 20 TICKS OFFSET BY 5 TICKS` — a new offset syntax with its own unit, plus a "legacy_interval_offset" linter. — T:antlr/sfml/SFML.g4 (`sfml_worded_intervals`); T:java/…/SFMProgramLinters.java — [X] (1.19.2/1.19.4 flag, off in release)
- Interval overflow validation — rejects intervals whose `*20` overflows. — T:java/ca/teamdman/sfml/ast/ASTBuilder.java (`sfml_interval_overflow_validation`) — [X]
- Frame trigger — `EVERY FRAME FOR <labels> AS <id> DO … END` — client-side per-frame trigger for client managers and touch displays. — T:antlr/sfml/SFML.g4 (`client_frame_language`) — [X]
- Execution side declaration — `CLIENT BTW` / `SERVER BTW` at the program head. — T:antlr/sfml/SFML.g4 (`sfml_execution_side`) — [X]

## Language — statements

- INPUT statement — `INPUT [limits] [EXCEPT ids] FROM [EACH] <labelAccess>`, or the reversed form `FROM [EACH] <labelAccess> INPUT [limits] [EXCEPT ids]` — registers an input source in the trigger context. It moves nothing by itself; there is no internal buffer. — G4 `inputStatement`; J:sfml/ast/InputStatement.java — [R]
- OUTPUT statement — `OUTPUT [limits] [EXCEPT ids] TO [EMPTY SLOTS IN] [EACH] <labelAccess>`, or the reversed form `TO [EMPTY SLOTS IN] [EACH] <labelAccess> OUTPUT [limits] [EXCEPT ids]` — moves resources from **all INPUTs currently active in the trigger** into the matching destination slots. — G4 `outputStatement`; J:sfml/ast/OutputStatement.java — [R]
- Input accumulation — every INPUT executed so far in the trigger stays active for every later OUTPUT, including INPUTs inside an earlier IF block. Use FORGET to drop them. — J:sfm/common/program/ProgramContext.java `addInput`; template `forget.sfml` — [R]
- Input slot cache and shared counters — an INPUT gathers its slots once, on the first OUTPUT that uses it, and caches them for the rest of the trigger. Quantity and retain counters are therefore shared across several OUTPUTs that draw from the same INPUT. Slots are freed when the trigger ends. — InputStatement.java `gatherSlots`, `freeSlots` — [R]
- Transfer algorithm — for each input slot × output slot:
  1. Simulate the insert.
  2. Clamp the amount by: the space in the destination, the destination tracker's max, the source tracker's max, the input retain obligation, and **one max stack size per move** (`getMaxStackSize`).
  3. Extract for real, then insert for real.
  4. Track the amounts.

  An input slot that refuses a small extraction is skipped, not marked done (4.35.0 behaviour, #610). — OutputStatement.java `moveTo` — [R] (`quantity_sensitive_extraction_progress` is on in release)
- Resource-loss reporting — if an insert returns a remainder after a successful simulation, SFM logs a "!!!RESOURCE LOSS HAS OCCURRED!!!" report. The report includes slot, block and blockstate details. It can be silenced with `logResourceLossToConsole`. — OutputStatement.java — [R]
- Default output slot filter — a destination slot is a candidate if `amount < slot max`. If the slot max is above 99 (barrels, bins, drawers) the item stack size is ignored; otherwise a slot is skipped once full to `min(slot max, stack max)`. — OutputStatement.java `shouldCreateSlot` — [R]
- EMPTY SLOTS IN — `OUTPUT TO EMPTY SLOTS IN storage` — only inserts into slots that are currently empty. Intended to cut lag on large, full inventories. — G4 `emptyslots`; OutputStatement.java — [R]
- EACH on the label side — `INPUT FROM EACH a`, `OUTPUT TO EACH b` — gives each (block, side) capability its own quantity and retain trackers. Without EACH, trackers are shared across all labelled blocks. — InputStatement/OutputStatement `gatherSlots` — [R]
- FORGET (all) — `FORGET` — drops every active input whose labels are among the labels used anywhere in the program; in effect it clears all inputs. — G4 `forgetStatement`; J:sfml/ast/ForgetStatement.java; ASTBuilder `visitForgetStatement` — [R]
- FORGET (specific labels) — `FORGET a, b,` — removes only those labels from active INPUTs. An INPUT left with no labels is discarded; its other labels stay active. A trailing comma is allowed. — ForgetStatement.java — [R]
- IF / ELSE IF / ELSE — `IF <cond> THEN … (ELSE IF <cond> THEN …)* (ELSE …)? END` — the parser desugars it into nested if/else. It shares the trigger context; there is no new scope. — G4 `ifStatement`; J:sfml/ast/IfStatement.java; ASTBuilder `visitIfStatement` — [R]
- Block — any number of statements. Each statement's execution time is logged at INFO ("Program statement tick took %.2f ms"). — J:sfml/ast/Block.java — [R]
- Comments — `-- comment to end of line` (hidden channel). — G4 `LINE_COMMENT` — [R]
- Case insensitivity — all keywords are case-insensitive (fragment letters). — G4 — [R]
- Trailing commas — allowed in resource lists, EXCEPT lists and FORGET lists. — G4 — [R]
- Keyword reuse as names — `identifier` accepts `REDSTONE, GLOBAL, SECOND(S), TOP, BOTTOM, LEFT, RIGHT, FRONT, BACK`, so they can be used as labels or resource names (for example `INPUT redstone FROM a`). NORTH, SOUTH, EAST, WEST and NULL are not accepted. — G4 `identifier`; template `redstone_item_move.sfml` — [R]
- Quoted labels — `"storage a"` — label names may contain spaces or symbols. `\"` escapes a quote. — G4 `StringLabel`, `STRING`; ASTBuilder `visitString` — [R]
- Packet / value statements — `LET x BE …`, `CREATE INPUT ns:id WITH x`, `INPUT WITH CAPABILITY ns:id …`, `INPUT LIKE pattern … AS name`, `BROADCAST TO x [CHANNEL id]`, `RENDER IMAGE "…" TO x`, JSON and INVOKE expressions — T:antlr/sfml/SFML.g4 (`packet_computation`, `packet_transport_private`, `client_inbox`, `client_frame_render`, `client_program_actions`) — [X]

## Language — resource matching and types

- Resource identifier grammar — `name` | `ns:name` | `type:ns:name` | `typens:type:ns:name` | `"quoted regex"`:
  - Missing parts default to `sfm`, `item` and `*`.
  - `::` becomes `:*:` and a trailing `:` becomes `:*`.
  - The whole identifier is lowercased.
  - Example: `fluid::water` is `sfm:fluid:*:water`, and `fluid::` is all fluids.
  - Watch out: `fluid:water` means *items* in the `fluid` namespace.

  — G4 `resourceId`; ASTBuilder `visitResource`; J:sfml/ast/ResourceIdentifier.java `fromString` — [R]
- Default resource type is item only — omitting resources (`INPUT FROM a`) matches `sfm:item:*:*`. Fluids, energy and other types must be named explicitly. — J:sfml/ast/ResourceIdentifier.java `MATCH_ALL`; ResourceLimit.java — [R]
- Wildcards — `*` inside an unquoted identifier becomes the regex `.*` (for example `*ingot*`, `minecraft:*_log`). A bare `*` is accepted. — G4 `IDENTIFIER`; ASTBuilder — [R]
- Quoted regex — `".*ingot.*"` — the full Java regex is used as-is; `*` is not converted. A bad regex is a compile error ("Invalid resource identifier pattern"). — ASTBuilder `visitStringResource`; ResourceIdentifier `assertValid` — [R]
- Literal matching — non-regex parts are matched with `equalsIgnoreCase`. Common regexes are fast-pathed: prefix, suffix, contains, prefix+suffix. — J:sfm/common/program/RegexCache.java — [R] (note: the `a.*b` fast path can over-match overlapping strings; fixed only behind `regex_overlap_fix` [X], which is on for 1.19.2 and 26.1.2)
- OR disjunction in a limit — `INPUT 5 iron_ingot OR gold_ingot FROM a` — one shared limit over several ids. A trailing OR is allowed. — G4 `resourceIdDisjunction`; ASTBuilder — [R]
- Multiple resource limits — `INPUT 5 stone, 3 iron_ingot, cobblestone, FROM a` — comma-separated, each with its own tracker. — G4 `resourceLimitList`; ResourceLimits.java — [R]
- Quantity — `INPUT 64 stone`, `OUTPUT 8 …` — the most that statement execution moves (shared across blocks unless EACH). Parsed as long. INPUT without a quantity means unlimited. — G4 `quantity`; J:sfml/ast/Limit.java, ResourceQuantity.java — [R]
- RETAIN on input — `INPUT RETAIN 5 stone FROM a` — leaves N in the source, tracked across the source slots. — G4 `retention`; J:sfm/common/program/*InputResourceTracker.java; OutputStatement `moveTo` — [R]
- RETAIN on output — `OUTPUT RETAIN 8 coal TO furnace` — fills the destination only up to N, counting what is already there (retention observation). Output defaults are quantity MAX and retain MAX. — J:sfm/common/program/*OutputResourceTracker.java `updateRetentionObservation` — [R]
- Quantity plus retain — `INPUT 5 RETAIN 3 stone`, `OUTPUT 8 RETAIN 16 …` — both limits apply together. — G4 `QuantityRetentionLimit` — [R]
- EACH on a quantity or retain — `INPUT 5 EACH *ingot* FROM a`, `RETAIN 1 EACH`, `5 EACH RETAIN 3 EACH` — expands the limit per distinct resource id matched by a pattern. There are four tracker classes (shared or expanded quantity × shared or expanded retain). — ResourceLimit.java `createInputTracker`/`createOutputTracker`; J:sfm/common/program/{Shared,Expanded}Quantity{Shared,Expanded}Retention{Input,Output}ResourceTracker.java — [R]
- Limit with WITH but no id — `WITH #forge:ingots` or `3 RETAIN 5 WITH TAG …` — the implicit resource is `sfm:item:*:*`. — G4 `resourceLimit` — [R]
- EXCEPT — `INPUT *ingot* EXCEPT iron_ingot, gold_ingot FROM a`, `OUTPUT 5 EXCEPT copper_ingot TO b` — a statement-wide exclusion list that applies to every limit in the statement. It does not support WITH clauses. — G4 `resourceExclusion`; ResourceLimits `withExclusions` — [R]
- WITH tag — `WITH #ns:path`, `WITH TAG ns:path`, `WITH TAG #ns:path`, `WITH #path` (any namespace), and `WITH TAG *:ingots` — matches if any tag of the stack matches. — G4 `with`/`withClause`/`tagMatcher`; J:sfml/ast/WithTag.java, TagMatcher.java — [R]
- Tag path wildcards — `#tools/*/*_matter`, `#essence/**` — `*` works per segment and `**` matches all remaining segments. The pattern must cover the tag's segment count. — TagMatcher.java `testPath` — [R]
- WITH boolean logic — `WITH NOT #a`, `WITH #a AND #b`, `WITH #a OR #b`, parentheses — combinable tag predicates. — G4 `WithNegation`/`WithConjunction`/`WithDisjunction`/`WithParen`; J:sfml/ast/With*.java — [R]
- WITHOUT — `WITHOUT #a AND #b` — negates the entire WITH expression. — J:sfml/ast/With.java — [R]
- Block tags work for items — template example `INPUT *a* WITH TAG minecraft:mineable/shovel` — matches the tags reported by the resource type for the stack. — RS:assets/sfm/template_programs/tag_matching.sfml — [R]
- Resource type `sfm:item` — item handler capability, keyed by item registry id. — J:sfm/common/resourcetype/ItemResourceType.java; J:sfm/common/registry/registration/SFMResourceTypes.java — [R]
- Resource type `sfm:fluid` — fluid handler capability, keyed by fluid id, amounts in mB (`fluid::water`). — FluidResourceType.java — [R]
- Resource type `sfm:forge_energy` — energy storage capability, a single id `forge:energy`. Aliases `fe::`, `rf::`, `energy::` and `power::` all map to forge_energy. — ForgeEnergyResourceType.java; ResourceIdentifier constructor — [R]
- Resource type `sfm:redstone` — id `minecraft:redstone`. Reads the labelled block's `getSignal` for the given side (0–15, no default side). It is read-only in practice: insert and extract return 0. Meant for `HAS … redstone::`. — RedstoneResourceType.java; J:sfm/common/capability/RedstoneSignalCapabilityProvider.java — [R] (improved live reads and side semantics are 1.19.x-only flags `redstone_live_read`/`redstone_buffer_storage` [X])
- Mekanism `sfm:chemical` plus the aliases `gas`, `infusion`, `pigment`, `slurry` — when Mekanism is loaded, all five ids register the same unified `ChemicalResourceType` (the Mekanism 1.21 chemical capability). They take tags and amounts as longs. — J:sfm/common/compat/SFMMekanismCompat.java `registerResourceTypes`; J:sfm/common/resourcetype/ChemicalResourceType.java — [R]
- Separate Mekanism `gas`/`infusion`/`pigment`/`slurry` classes plus `mekanism_energy` — distinct resource types per chemical kind, and Mekanism energy as its own type. — platform/minecraft/projections/sfm-4.35.0/mc-1.19.2/…/SFMMekanismCompat.java — [OV] (mekanism_energy up to 1.20.3; separate chemical classes on pre-1.21 builds)
- `sfm:image` resource type — T:java/ca/teamdman/sfm/common/registry/registration/SFMResourceTypes.java (`image_resources`) — [X]
- Resource-type validation at compile time — an unknown type ("unknown resource type"), a malformed type ("Resource types must be literals, not wildcards"), and a type disallowed by config are all compile errors. — J:sfml/program_builder/ProgramBuilder.java `checkResourceTypes` — [R]
- Disallowing resource types — server config `disallowedResourceTypesForTransfer` (list of ids such as `sfm:fluid`) — a program referencing one fails to compile. The resource_types template marks such types "(disallowed in config)". — SFMServerConfig.java; ProgramBuilder.java; J:sfm/client/examples/SFMExampleProgram.java — [R]
- Slots — `<labels> SLOTS 0,1,3-4,7-9` or `SLOT 5` — an inclusive range set over capability slot indices. The default is all slots. — G4 `slotqualifier`/`rangeset`/`range`; J:sfml/ast/NumberRangeSet.java — [R]
- Sides — `<labels> TOP, WEST SIDE`; the options are `TOP BOTTOM NORTH EAST SOUTH WEST LEFT RIGHT FRONT BACK NULL` — the capability is queried from that face. With no side clause the **null side** is used. — G4 `sidequalifier`/`side`; J:sfml/ast/Side.java, SideQualifier.java — [R]
- Relative sides — `LEFT/RIGHT/FRONT/BACK` — resolved from the block's `HORIZONTAL_FACING` or `FACING` property (left = clockwise). They resolve to null if the property is absent or if the block faces up or down. — Side.java `resolve` — [R]
- EACH SIDE — `<labels> EACH SIDE` — all six faces plus null. Positions and faces are not de-duplicated, so the same inventory can be visited up to 7 times. — SideQualifier.ALL — [R]
- ROUND ROBIN BY LABEL — `INPUT FROM "a","b" ROUND ROBIN BY LABEL` — each execution uses the next label in the list. The cursor lives in the AST node and persists for as long as the compiled program is cached. — G4 `roundrobin`; J:sfml/ast/RoundRobin.java — [R]
- ROUND ROBIN BY BLOCK — `OUTPUT 128 dirt TO dest ROUND ROBIN BY BLOCK` — each execution picks the single next block from the deduplicated union of the labels' positions. — RoundRobin.java — [R]
- Round-robin advance quirk — the counter advances once per resource type gathered (issue #166) and once per HAS evaluation. — InputStatement.java TODO comment; BoolHas.java — [R]
- Multi-label access — `FROM a, b, "c d"` — the union of the labelled positions. Without round robin a block carrying two of the labels is gathered twice. — G4 `labelAccess`; RoundRobin `UNMODIFIED` — [R]
- Label storage — labels live on the disk (`LabelPositionHolder`, data component `sfm:labels`), not on blocks. Label name max is 256 characters. — J:sfm/common/label/LabelPositionHolder.java; J:sfml/ast/Program.java `MAX_LABEL_LENGTH` — [R]
- Reachability — only positions adjacent to the manager's cable network (or the manager itself) resolve capabilities. — J:sfm/common/block_network/CableNetwork.java `isAdjacentToCable` — [R]
- Glob literals — `SFMLLiteralGlob` replaces `*` → `.*` conversion. — T:java/ca/teamdman/sfml/ast/ASTBuilder.java (`sfml_literal_globs`) — [X]

## Language — conditions

- Boolean literals — `TRUE`, `FALSE` — G4 `boolexpr` — [R]
- Logic operators — `NOT x`, `x AND y`, `x OR y`, `( … )` — G4; J:sfml/ast/BoolNegation/BoolConjunction/BoolDisjunction/BoolParen.java — [R]
- HAS comparison — `[setOp] <labelAccess> HAS <op> <n> [resources joined by OR] [WITH/WITHOUT …] [EXCEPT ids]` — counts matching amounts over the labelled blocks' slots. The label access honours sides, slots and round robin. With no resource given it counts items only. — G4 `BooleanHas`; J:sfml/ast/BoolHas.java — [R]
- HAS EXCEPT is parsed but ignored — the `except` set is stored but never consulted by `BoolHas.test`. — BoolHas.java — [R] (quirk)
- Comparison operators — `GT >`, `LT <`, `EQ =`, `LE <=`, `GE >=` — word and symbol forms are interchangeable. — J:sfml/ast/ComparisonOperator.java — [R]
- Set operator OVERALL — default; compares the total summed over all blocks. — J:sfml/ast/SetOperator.java — [R]
- Set operator SOME — true if at least one block satisfies the comparison. — SetOperator.java — [R]
- Set operator EVERY / EACH — `EACH` is an alias for `EVERY`; every block must satisfy it. It is vacuously true with no blocks. — SetOperator.java — [R]
- Set operator ONE — exactly one block satisfies it. — SetOperator.java — [R]
- Set operator LONE — zero or one block satisfies it. — SetOperator.java — [R]
- Empty check — `IF chest HAS EQ 0 THEN` — supported. — changelog 4.16.0 — [R]
- Redstone condition — `REDSTONE [op n]` — reads `getBestNeighborSignal` at the manager. With no comparison it means `>= 0`, which is always true. — G4 `BooleanRedstone`; J:sfml/ast/BoolRedstone.java — [R]
- Labelled redstone via HAS — `IF lever_lbl HAS GT 0 redstone:: THEN` — the redstone resource type reports the labelled block's emitted signal. — RedstoneResourceType.java — [R] (basic version; the refined semantics are [X])
- Frame-modulo condition — `FRAME MOD n <op> m` — T:antlr/sfml/SFML.g4 (`client_frame_language`) — [X]
- Client value equality — `x = JSON "…"` — T:antlr/sfml/SFML.g4 (`client_program_actions`) — [X]

## Language — limits and diagnostics

- Program length limit — `MAX_PROGRAM_LENGTH` is about 32600 minus header bytes (from the `openScreen` packet limit). Programs sent from the GUI are truncated to it. — J:sfml/ast/Program.java; J:sfm/client/screen/ManagerScreen.java `sendProgram` — [R]
- Label length limit — 256 characters, a compile error if exceeded. The same limit applies in the label gun text box. — ASTBuilder `visitRawLabel`; J:sfm/common/net/ServerboundLabelGunSetActiveLabelPacket.java — [R]
- Disabling program execution — server config `disableProgramExecution` makes every compile fail with "Program execution is disabled via config". A crash in a manager tick logs a FATAL hint, and the crash report gets an "SFM Reminder" and "SFM Details" (diagnostic summary). — ASTBuilder `visitProgram`; J:sfm/common/blockentity/ManagerBlockEntity.java `serverTick`, `fillCrashReportCategory` — [R]
- Syntax errors — ANTLR lexer and parser errors collected as "line L:C msg". — J:sfml/program_builder/ProgramBuilder.java, ListErrorListener.java — [R]
- Build cache — compiled programs are cached by program text (WeakHashMap) and invalidated when the server config revision changes. Results with errors are not cached. — ProgramBuilder.java; SFMServerConfig `bumpRevisionAndClearProgramCache` — [R]
- Errors and warnings on the disk — written to the disk item as components (red errors, yellow warnings) and shown in the tooltip. Truncated at `maxDiskProblems` (default 10). — J:sfm/common/item/DiskItem.java; J:sfm/common/program/linting/ProblemTracker.java — [R]
- Warning rebuild throttling — warnings are recomputed on GUI open and program edits. They are throttled when a disk is swapped frequently by automation (cooldown). — ManagerBlockEntity `shouldRebuildWarnings` — [R]
- Linter `flow` (EachInIOWithoutPattern) — "EACH used without a pattern, statement %s". — J:sfm/common/program/linting/EachInIOWithoutPatternProgramLinter.java; J:sfm/common/registry/registration/SFMProgramLinters.java — [R]
- Linter `resources` — "Resource \"%s\" was not found." for a literal id missing from its registry. — ResourcesProgramLinter.java — [R]
- Linter `label_used_in_program_but_not_present` — "Label \"%s\" is used in code but not assigned in the world." — LabelUsedInProgramButNotPresentProgramLinter.java — [R]
- Linter `label_present_but_not_used` — "Label \"%s\" is assigned in the world but not defined in code." — LabelPresentButNotUsedProgramLinter.java — [R]
- Linter `label_not_connected` — "…not connected by cables." and "…connected by cables but is not detected as a valid inventory." It adds the reminder "Did you remember to push your labels using the label gun?" — LabelNotConnectedProgramLinter.java — [R]
- Linter `round_robin` — "Round robin by block shouldn't be used with EACH" and "Round robin by label should be used with more than one label". — RoundRobinProgramLinter.java — [R]
- Linter `incomplete_io` (path simulation) — simulates all 2^n IF-branch combinations per trigger and reports:
  - "Statement … inputs … but no future output statement consume …"
  - "Statement … uses resource type … which has no matching input statement."

  It keeps only warnings present on every path that runs the statement. If a trigger has more than `maxIfStatementsInTriggerBeforeSimulationIsntAllowed` IFs (default 10), it warns "Too many conditions for simulation". — IncompleteIOProgramLinter.java; GatherWarningsProgramBehaviour.java; J:sfm/common/program/SimulateExploreAllPathsProgramBehaviour.java — [R]
- Linter `no_slot_statement` — "Statement matches no slots", "No slots support extraction", "No slots support insertion" (checked against the live world). — NoSlotStatementProgramLinter.java — [R]
- Linter `mekanism_sidedness` — warns about Mekanism blocks accessed from the null side or with a mismatching side config, and auto-fixes the Mekanism side config. — J:sfm/common/program/linting/compat/mekanism/MekanismSidednessProgramLinter.java (registration commented out on 1.21.1) — [OV] (registered on 1.19.2 only)
- Auto-fix ("attempt to fix warnings") — shift-click the manager's "!" button. It removes labels that are not referenced in code, labels not adjacent to the network, and labels on blocks with no capability. The program text is not changed. — ProgramLinter `fixWarnings`; J:sfm/common/net/ServerboundManagerFixPacket.java — [R]
- Diagnostics to clipboard — click the "!" button. The clipboard text holds:
  - the program
  - date and time
  - Minecraft version, launcher and brand
  - NeoForge and SFM versions
  - errors and warnings
  - a label dump

  — J:sfm/common/diagnostics/SFMDiagnostics.java; J:sfm/client/diagnostics/SFMClientDiagnostics.java — [R]
- Ctrl+Space inspection: resource id — opens a read-only text view listing every registry id that the pattern expands to. — J:sfm/client/ProgramTokenContextActions.java; ResourceIdentifier `expand` — [R]
- Ctrl+Space inspection: label — the server returns the label's positions, block names, chunk-loaded state and per-position export statements (up to 50k characters). — J:sfm/common/net/ServerboundLabelInspectionRequestPacket.java — [R]
- Ctrl+Space inspection: INPUT (cursor on the keyword) — "peek results": the slots that would be gathered, rendered as statements. — ServerboundInputInspectionRequestPacket.java — [R]
- Ctrl+Space inspection: OUTPUT (cursor on the keyword) — for every branch possibility, lists the predicted inputs and predicted outputs ("predictions may differ from actual execution results"). — ServerboundOutputInspectionRequestPacket.java — [R]
- Ctrl+Space inspection: IF / boolean expression — evaluates the condition now and returns TRUE or FALSE. — ServerboundIfStatementInspectionRequestPacket.java, ServerboundBoolExprStatementInspectionRequestPacket.java — [R]
- Context-action hint underline — holding Ctrl in the editor underlines tokens that have a context action (INPUT, OUTPUT, IDENTIFIER, IF, HAS). — J:sfm/client/text_styling/ProgramSyntaxHighlightingHelper.java; ProgramTokenContextActions `hasContextAction` — [R]

## Blocks

- Factory Manager (`sfm:manager`):
  - Holds one disk; the slot only accepts disks.
  - Runs the program every server tick and acts as a cable network node.
  - Its disk slot is exposed as an item handler on all sides, so hoppers and SFM itself can swap disks ("managers can interact with themselves").
  - Takes redstone pulses from itself or the block above.
  - Drops its disk when broken.

  Recipe: chests, cables and a repeater (16 cables come from the cable recipe). — J:sfm/common/block/ManagerBlock.java; J:sfm/common/blockentity/ManagerBlockEntity.java; GEN:data/sfm/recipe/manager.json — [R]
- Tunnelled Factory Manager (`sfm:tunnelled_manager`) — a manager that also passes capabilities queried on one face through to the block on the opposite face (for example hopper → manager → chest). Recipe: manager surrounded by fences. It uncrafts back to a manager. — J:sfm/common/block/TunnelledManagerBlock.java; J:sfm/common/registry/registration/SFMBlockCapabilities.java `createCapabilityProxy` — [R]
- Inventory Cable (`sfm:cable`) — a full-block cable; connects managers to adjacent inventories. Recipe: black dye, light pressure plates, iron bars and a chest; makes 16. — J:sfm/common/block/CableBlock.java; GEN:data/sfm/recipe/cable.json — [R]
- Fancy Inventory Cable (`sfm:fancy_cable`) — a non-full-block cable with connection arms. Arms connect to other cables and to blocks with any capability. It is waterloggable. Converts to and from the plain cable shapelessly. — J:sfm/common/block/FancyCableBlock.java — [R]
- Tough Inventory Cable (`sfm:tough_cable`) — explosion resistance 1200, hardness 10, needs the correct tool. Tooltip: "Resists explosions. Can be facaded as tougher blocks." Recipe: cable plus obsidian. — J:sfm/common/block/ToughCableBlock.java — [R]
- Tough Fancy Inventory Cable (`sfm:tough_fancy_cable`) — the fancy shape with tough properties (hardness 5). — ToughFancyCableBlock.java — [R]
- Tunnelled Inventory Cable (`sfm:tunnelled_cable`) — "Passes capabilities through to the opposite side." This works for every SFM resource-type capability and for other mods' queries. Not on the null side. Cached connections refresh when the far provider changes (#620). Recipe: cable plus fences. — J:sfm/common/block/TunnelledCableBlock.java; J:sfm/common/capability/TunnelledBlockCapabilityRelay.java — [R]
- Tunnelled Fancy Inventory Cable (`sfm:tunnelled_fancy_cable`) — the fancy shape with tunnelling. — TunnelledFancyCableBlock.java — [R]
- Cable facade variants — `cable_facade`, `fancy_cable_facade`, `tough_cable_facade`, `tough_fancy_cable_facade`, `tunnelled_cable_facade`, `tunnelled_fancy_cable_facade`:
  - Each is a block-entity variant that renders as another block (FacadeData: render blockstate, face direction, texture mode FILL/STRETCH; transparency OPAQUE/TRANSLUCENT).
  - It takes the light level of the mimicked block.
  - It drops the base cable.
  - There is no item; it is created with the network tool.

  — J:sfm/common/block/*FacadeBlock.java; J:sfm/common/blockentity/*FacadeBlockEntity.java; J:sfm/common/facade/* — [R]
- Printing Press (`sfm:printing_press`):
  - Three internal slots: form (limit 1, Printing Form only), ink (validated by recipes) and paper (limit 1, validated by recipes).
  - Right-click with an item to insert it; right-click empty-handed to extract (paper, then form, then ink).
  - Place it one block below a downward-facing piston, with an air gap; when the piston head extends onto it, it prints: the paper becomes a copy of the form's referenced item at the paper's count, and one ink is consumed.
  - It exposes an item handler for automation.
  - Tooltip explains the setup.

  — J:sfm/common/block/PrintingPressBlock.java; J:sfm/common/blockentity/PrintingPressBlockEntity.java; J:sfm/common/item/PrintingPressBlockItem.java — [R]
- Water Tank (`sfm:water_tank`):
  - An infinite water source (drain-only fluid handler; draining does not deplete it).
  - It is active when it has 2 or more adjacent water source blocks (`in_water` state).
  - Capacity is 2^(n−1) × 1000 mB, where n is the number of active tanks in the connected tank network.
  - It supports bucket pickup.

  Recipe: water buckets, iron bars and a light pressure plate. — J:sfm/common/block/WaterTankBlock.java; J:sfm/common/blockentity/WaterTankBlockEntity.java; J:sfm/common/block_network/WaterNetworkManager.java — [R]
- Resource Buffer (`sfm:buffer`) — the block is registered (tier MaxUnit) but **the item is registered only in the IDE**. Tiers: Unit, Paltry, Basic, Satisfying, DoubleSatisfying, MaxUnit, MaxGrande. — J:sfm/common/block/BufferBlock.java, BufferBlockTier.java; J:sfm/common/registry/registration/SFMItems.java — [X] (dev-only item)
- Test Barrel and Test Barrel Tank (`sfm:test_barrel`, `sfm:test_barrel_tank`) — test fixtures with no items. — J:sfm/common/block/TestBarrel*.java — [X] (test-only)
- Battery block — excluded from every build. — `BatteryBlock.java` in the source-excludes lists — [X]
- Client Manager (`sfm:client_manager`) — a client-executed manager variant. — T:java/…/SFMBlocks.java (`client_manager`) — [X]
- Touch Display (`sfm:touch_display`) — a renderable display surface. — T:java/…/SFMBlocks.java (`touch_display`) — [X]
- Cauldron fluid capability — SFM registers a fluid handler on cauldrons, lava cauldrons and water cauldrons, so programs can fill and drain them. — J:sfm/common/capability/CauldronBlockCapabilityProvider.java; SFMBlockCapabilities.java — [R]
- Mining tags — datagen adds blocks to `minecraft:mineable/pickaxe` and `minecraft:mineable/axe`. — GEN:data/minecraft/tags/block/mineable/*.json — [R]

## Items and tools

- Factory Manager Program Disk (`sfm:disk`):
  - Stores the program text (`sfm:program`), labels (`sfm:labels`), errors and warnings.
  - The item name becomes the program NAME (aqua).
  - Right-click in hand opens the editor outside a manager.
  - Tooltip: label summary ("Labels" / " - name: N blocks"), errors in red, warnings in yellow, an edit-in-hand reminder, and a hold-Shift hint. Holding Shift shows the syntax-highlighted program with a rainbow separator.

  Recipe: paper, RGB dyes, redstone block, repeater and redstone. — J:sfm/common/item/DiskItem.java; GEN:data/sfm/recipe/disk.json — [R]
- Disk reset recipe (`sfm:disk_reset`) — craft any number of disks to get blank disks back. — J:sfm/common/recipe/DiskResetRecipe.java — [R]
- Label Gun (`sfm:labelgun`) — the labelling tool; see the Label Gun section. The recipe JSON is mis-filed as `tunnelled_manager_vertical.json`: blue dye, sign, black dye, sticks. — J:sfm/common/item/LabelGunItem.java; GEN:data/sfm/recipe/tunnelled_manager_vertical.json — [R]
- Label gun reset recipe (`sfm:label_gun_reset`) — craft a label gun alone to clear it. — J:sfm/common/recipe/LabelGunResetRecipe.java — [R]
- Network Tool (`sfm:network_tool`) — see the Network Tool section. Recipe: redstone lamp, sign, iron ingots and a heavy pressure plate. — J:sfm/common/item/NetworkToolItem.java — [R]
- Printing Form (`sfm:form`) — holds a reference item (`sfm:form_reference`). The tooltip shows the referenced item's tooltip and the item renders as the referenced item. Made by dropping an anvil onto items lying on a block tagged `sfm:anvil_printing_press_forming` (iron and copper blocks); the block is consumed. — J:sfm/common/item/FormItem.java; J:sfm/common/handler/FallingAnvilHandler.java; J:sfm/client/render/FormItemRenderer.java — [R]
- Printing press recipes (type `sfm:printing_press`, keys `form`/`ink`/`paper`):
  - `program_copy`: disk form + black dye + disk.
  - `written_book_copy`: written book form + black dye + book.
  - `enchanted_book_copy`: enchanted book form + Experience Goop + book.
  - `map_copy`: filled map form + black dye + map.

  Datapack-extensible. — GEN:data/sfm/recipe/*_copy.json; J:sfm/common/recipe/PrintingPressRecipe.java — [R]
- Experience Shard (`sfm:xp_shard`) — made by dropping an anvil onto enchanted books lying on obsidian or crying obsidian (`sfm:anvil_disenchanting`). The shard count follows the `levelsToShards` config. — J:sfm/common/item/ExperienceShardItem.java; FallingAnvilHandler.java — [R]
- Experience Goop (`sfm:xp_goop`) — crafted from 9 shards; used as the ink for copying enchanted books. — J:sfm/common/item/ExperienceGoopItem.java — [R]
- Anvil disenchanting — an anvil falling on enchanted items plus plain books lying on the disenchanting block moves one enchantment per stack-size of books onto enchanted books. — FallingAnvilHandler `removeEnchantmentsFromItems` — [R]
- Creative tab "Super Factory Manager" — lists every registered SFM item. — J:sfm/common/registry/registration/SFMCreativeTabs.java — [R]
- `/sfm kit` contents — label gun, manager, disk, network tool, cable, chest. — J:sfm/common/command/SFMCommand.java — [R]

## Label Gun

- Toggle the active label — right-click a block. If any targeted block lacks the label, the label is added to all targets; otherwise it is removed from all. Labels are stored on the gun. — J:sfm/common/label/LabelGunToggleLabelAction.java — [R]
- Open the GUI — right-click air, or the "Open Label Gun GUI" key (unbound by default) while holding the gun. — LabelGunItem `use`; J:sfm/client/handler/LabelGunKeyMappingHandler.java — [R]
- GUI: text box — "Search or enter new label". Typing filters the label buttons; Enter or Done sets the active label (a new label is created if needed). Max 256 characters. — J:sfm/client/screen/LabelGunScreen.java — [R]
- GUI: label buttons — one "label (count)" button per known label; clicking selects it and closes. — LabelGunScreen — [R]
- GUI: Clear — wipes all labels from the gun. — LabelGunScreen; J:sfm/common/net/ServerboundLabelGunClearPacket.java — [R]
- GUI: Prune — removes labels with zero positions. — LabelPositionHolder `prune`; ServerboundLabelGunPrunePacket.java — [R]
- GUI: Cycle label view — cycles the view mode and closes the GUI. — ServerboundLabelGunCycleViewModePacket.java — [R]
- View modes — SHOW_ALL; SHOW_ONLY_ACTIVE_LABEL_AND_TARGETED_BLOCK (blocks carrying the active label, plus every label on the looked-at block); SHOW_ONLY_TARGETED_BLOCK. — LabelGunItem.LabelGunViewMode; J:sfm/client/handler/ItemWorldRenderer.java — [R]
- Cycle-view key — default Left Alt, tapped while holding the gun. It is debounced so it does not fire after an Alt-click pick. — LabelGunKeyMappingHandler — [R]
- Pick modifier — default Left Alt + right-click a block. It sets the active label to the next label (alphabetically) present on the targeted blocks, cycling on repeat clicks. — J:sfm/common/label/LabelGunPickLabelAction.java — [R]
- Clear modifier — default Left Shift + right-click. It removes **all** labels from the targeted blocks; Shift+Alt removes only the active label. If more than one block is targeted, a confirmation screen appears with a 10-tick delay. — J:sfm/common/label/LabelGunUnsetBlockLabelsAction.java; J:sfm/client/ClientLabelGunWarningHelper.java — [R]
- Contiguous modifier — default Left Ctrl + right-click. It flood-fills (including diagonal neighbours) over blocks of the same block type that touch a cable of the adjacent network. It applies toggle, clear or pick to all of them and reports "Skipped %d blocks not touching cables". — J:sfm/common/label/LabelGunPlanTargets.java, LabelGunPlanner.java — [R]
- Push labels to a manager — right-click a manager. Overwrites the manager disk's labels with the gun's labels, then recompiles. Chat: "Pushed labels to the manager…" — J:sfm/common/label/LabelGunManagerPushOrPullAction.java — [R]
- Pull labels from a manager — Pull modifier (default Left Shift) + right-click a manager. Copies the disk's labels plus all labels referenced in the script (empty entries) onto the gun, replacing the gun's labels. Chat: "Pulled labels…" — LabelGunManagerPushOrPullAction.java — [R]
- Target-manager modifier — default Right Alt + right-click a manager. Labels the manager block itself instead of pushing or pulling. — LabelGunPlanner — [R]
- Next / previous label keys — unbound by default; select the adjacent label alphabetically, wrapping around. — LabelGunKeyMappingHandler; LabelGunItem `getNextLabel` — [R]
- Scroll switching — Scroll modifier (default Left Shift) + mouse wheel. Changes the active label of a gun in the main hand or offhand and cancels the hotbar scroll. — J:sfm/client/handler/LabelGunScrollSwitcher.java — [R]
- Gun name and tooltip — the name shows `Label Gun: "label"` (aqua). The tooltip lists labels and counts; holding Shift lists every key binding. — LabelGunItem `getName`/`appendHoverText` — [R]
- In-world label rendering — boxes and floating label text over labelled blocks while the gun is held. The box colour differs in the limited view modes. — ItemWorldRenderer `handleLabelGun` — [R]
- View-mode HUD reminder — top-centre text when a limited mode is active ("Showing blocks with active label…" or "Showing only targeted block labels…"). Toggle with client config `showLabelGunReminderOverlay`. — J:sfm/client/overlay/LabelGunReminderOverlay.java — [R]
- Push/pull chat messages toggle — client config `showLabelGunPushPullMessages` (default true). — J:sfm/client/ClientLabelGunResponseChatHelper.java; J:sfm/common/config/SFMClientConfig.java — [R]
- Read-only label access for CC — T:java (`label_readonly_access`) — [X]

## Network Tool

- Cable overlay — while the tool is held (either hand), it shows cables (green-tinted) and capability-provider blocks (blue-tinted) through walls for networks within 128 blocks. Data refreshes every 20 ticks into item components `sfm:cable_positions` and `sfm:capability_positions`. — J:sfm/common/item/NetworkToolItem.java; J:sfm/client/handler/ItemWorldRenderer.java `handleNetworkTool` — [R]
- Overlay modes — SHOW_ALL, SHOW_SELECTED_NETWORK and HIDDEN. The toggle key (default Left Alt, tap) switches between SHOW_ALL and HIDDEN. — NetworkToolItem `cycleOverlayMode`; J:sfm/client/handler/NetworkToolKeyMappingHandler.java; ServerboundNetworkToolToggleOverlayPacket.java — [R]
- Attune to a network — hold the overlay key and right-click a block. The overlay then shows only that network, and the block is highlighted red if no network is found. — ServerboundNetworkToolUsePacket `handleOverlayFocusSelect` — [R]
- Block inspection — right-click a block face. Opens a text view with:
  - block position and state
  - the adjacent cable networks
  - capability kinds per side
  - generated `INPUT … FROM target <SIDE> SIDE SLOTS n` export statements, with the clicked face first
  - the block entity NBT for ops only (dev builds also add the block entity's toString)

  Truncated at 20480 characters. — ServerboundNetworkToolUsePacket `handleBlockInspectionRequest` — [R]
- Facade application:
  - Hold the network tool in the **offhand** and a block in the main hand, then right-click a cable to make it render as that block. The clicked face sets the orientation.
  - Holding nothing clears the facade.
  - Holding a different cable item (for example a fancy cable) changes the cable shape and keeps the facades.
  - Holding the same cable type clears the facades.

  Managers are skipped. — J:sfm/common/block/CableBlock.java `useItemOn`; J:sfm/common/facade/FacadePlanner.java — [R]
- Facade spread modes — plain click: single block; Ctrl: contiguous blocks with the same paint; Alt: matching paint across the whole network; Ctrl+Alt: the entire network. — J:sfm/common/facade/FacadeSpreadLogic.java — [R]
- Facade confirmations — confirmation screens: "update the facade appearance?", "change the facade world block?" and "clear these facades?", with counts of distinct states and blocks. — J:sfm/client/ClientFacadeWarningHelper.java; ApplyFacadesFacadePlan/ChangeWorldBlockFacadePlan/ClearFacadesFacadePlan.java — [R]
- Tooltip — 8 lines: through-wall overlay, right-click diagnostics, the Ctrl+I reminder, the attune hint, and the facade usage and modifiers. — NetworkToolItem `appendHoverText` — [R]
- HUD reminder — "Toggle network tool overlay with %s" while held. Client config `showNetworkToolReminderOverlay`. — J:sfm/client/overlay/NetworkToolReminderOverlay.java — [R]

## Manager screen and editor

- Manager GUI — disk slot plus the player inventory. Shows "State: running / missing disk / no program / invalid program" in colour and the current log level in small text. — J:sfm/client/screen/ManagerScreen.java; J:sfm/common/containermenu/ManagerContainerMenu.java — [R]
- Read-only for spectators — spectators see the GUI but the edit, paste, reset, rebuild and fix controls are hidden, and the server ignores their packets. — ManagerScreen `isReadOnly`; J:sfm/common/net/SFMPacketHandlingContext.java — [R]
- "Paste from clipboard" button and Ctrl+V — loads the program from the clipboard. If it would overwrite a different non-empty program, a confirmation screen appears ("Paste clipboard" / "Never mind…"). — ManagerScreen — [R]
- "Edit" button and Ctrl+E — opens the text editor on the disk program. — ManagerScreen; SFMKeyMappings `MANAGER_SCREEN_OPEN_TEXT_EDITOR_KEY` — [R]
- "View examples" button and Ctrl+Shift+E — opens the Program Template Picker. Choosing a template opens it in the editor, and Done overwrites the program. — J:sfm/client/screen/ExamplesScreen.java — [R]
- "Discord" button — a confirm-link screen to the SFM Discord. — ManagerScreen — [R]
- "Copy to clipboard" button and Ctrl+C — copies the program text. — ManagerScreen — [R]
- "View logs" button — opens the logs screen. — J:sfm/client/screen/LogsScreen.java — [R]
- "Rebuild cable network" button — rebuilds this manager's cable network cache. If that fails it purges all networks; the result is reported in chat. — J:sfm/common/net/ServerboundManagerRebuildPacket.java — [R]
- "View server config" button — shows the server TOML. — J:sfm/common/net/ServerboundServerConfigRequestPacket.java — [R]
- "Reset" button — "Wipes ALL disk data" (program and labels), with a confirmation screen ("Wipe program and labels"). — ServerboundManagerResetPacket.java — [R]
- "!" diagnostics button — visible when the disk has errors or warnings. Click copies diagnostics; Shift-click attempts to fix the warnings. — ManagerScreen `onDiagButtonClicked` — [R]
- Status flashes — "Saved to clipboard!", "Loaded from clipboard!", "Reset program and labels!", "Rebuilding cache!", "Fixing problems!" — ManagerScreen — [R]
- Tick-time graph — log-scaled line plot of the last 20 executions that did work, coloured green (≤5 ms), yellow (≤15 ms) or red. Hover shows "Hovered tick time"; otherwise "Peak tick time". — ManagerScreen `renderLabels`; ManagerBlockEntity `tickTimes` — [R]
- JEI exclusion zones — JEI avoids the manager screen's side buttons. — J:sfm/client/jei/SFMJEIPlugin.java `registerGuiHandlers` — [R]
- Text editor V1 (default) — a 400×200 multi-line editor with syntax highlighting, virtual scrolling for large documents, click-drag selection and Shift-click extend. Buttons: "#" (config), Done ("Shift+Enter to submit") and Cancel. — J:sfm/client/screen/text_editor/SFMTextEditScreenV1.java — [R]
- Editor: Shift+Enter — save and close. — SFMTextEditScreenV1 `keyPressed` — [R]
- Editor: Tab / Shift+Tab — indent / de-indent by 4 spaces (the current line, or every line in the selection). — J:sfml/manipulation/ProgramStringManipulationUtils.java — [R]
- Editor: Ctrl+/ — toggle `--` comments on the selected lines. — ProgramStringManipulationUtils `toggleComments` — [R]
- Editor: Ctrl+Space — context action or inspection for the token under the cursor (see Diagnostics). — J:sfm/client/ProgramTokenContextActions.java — [R]
- Editor: intellisense pick list — "Intellisense Pick List" popup with Up/Down navigation and Esc to close. The accept key defaults to `\` (rebindable). Suggests grammar tokens via antlr4-c3, labels with block counts from the disk, and, at ADVANCED level, every registered item, fluid and other resource. — J:sfml/intellisense/SFMLIntellisense.java; J:sfm/client/screen/widget/PickList.java — [R]
- Editor config screen — "Program Editor Config": Line Numbers on/off, Intellisense Off/Basic/Advanced, Preferred Editor V1/V2. Saved to `sfm-client-program-editor.toml`. — J:sfm/client/screen/SFMTextEditorConfigScreen.java; J:sfm/common/config/SFMClientTextEditorConfig.java — [R]
- Line numbers — optional `%03d` gutter (default off). — SFMTextEditScreenV1/V2 — [R]
- Syntax colours:
  - dark purple: sides and EACH
  - light purple: INPUT, OUTPUT, FROM, TO
  - blue: NAME, EVERY, DO, END, IF, ELSE, THEN, HAS, TRUE, FALSE, FORGET
  - green: identifiers and strings
  - gray: comments
  - other colours for numbers, time units and NOT/AND/OR (gold)

  — J:sfm/client/text_styling/ProgramSyntaxHighlightingHelper.java — [R]
- Exit protection — "Exit without saving?" / "Save changes" (Overwrite disk / Continue editing) confirmations when leaving with edits. — LocalizationEntry `gui.sfm.exit_without_saving_confirm.*`, `gui.sfm.save_changes_confirm.*`; J:sfm/client/text_editor/ISFMTextEditScreenOpenContext.java — [R]
- Text editor V2 — an alternative multi-cursor editor with registered actions: select all; delete left/right per cursor; move cursors left/right/up/down; extend heads with Shift+arrows; swap head and tail (Ctrl+O). The HUD is hidden while it is open. — J:sfm/client/screen/text_editor/SFMTextEditScreenV2.java; J:sfm/client/registry/SFMTextEditorActions.java; J:sfm/client/text_editor/action/*; J:sfm/client/handler/OverlayHider.java — [R] (opt-in through the config)
- Editor from the title screen and world list — Ctrl+E on the title screen opens a scratch editor. Ctrl+E on the Select World screen opens an editor pre-filled with world names, last-played dates and info. — J:sfm/client/handler/TitleScreenOpenTextEditorKeyHandler.java, SelectWorldScreenOpenTextEditorKeyHandler.java — [R]
- Auto-open the editor on launch — `TitleScreenOpenTextEditorOnLaunchHandler` exists but `firstTime = false`, so it is disabled. — J:sfm/client/handler/TitleScreenOpenTextEditorOnLaunchHandler.java — [X] (disabled)
- In-game TOML editing — `/sfm config edit SERVER|CLIENT` opens the config as text in the V1 editor; saving applies the change and syncs. Results: "Successfully updated SFM config." / "invalid, no changes" / "Failed to find…". — J:sfm/client/screen/TomlEditScreen.java; J:sfm/common/net/ServerboundServerConfigUpdatePacket.java; J:sfm/common/config/SFMConfigReadWriter.java — [R]
- Container inspector (Ctrl+I in any container GUI) — overlays slot indices on every slot and shows "Container Slots: N" / "Inventory Slots: N" plus the notices "GUI slots don't always correspond to automation slots!!!" and "Press %s to toggle this overlay". — J:sfm/client/handler/ContainerScreenInspectorHandler.java — [R]
- "Export Inspector" button (in the Ctrl+I overlay) — the server generates per-side SFML `INPUT` statements for the open container's contents (per slot and aggregated), with a Mekanism null-side warning. The result opens in a text view. — J:sfm/common/net/ServerboundContainerExportsInspectionRequestPacket.java — [R]
- Item inspector key — "(WIP) Copy Hovered Item To Clipboard", unbound by default. Reports "Copied {} characters to clipboard!" — SFMKeyMappings `ITEM_INSPECTOR_KEY`; ContainerScreenInspectorHandler — [R] (WIP)
- Canvas text editor, editor search, command palette, workspace panels, terminal, item picker, keyboard profiles — T:java (`canvas_text_editor`, `editor_search`, `command_palette`, `workspace_panels`, `terminal_*`, `item_picker`, `keyboard_profiles`) — [X]

## Templates

All templates live in `RS:assets/sfm/template_programs/`. They are discovered from **any** resource namespace under `template_programs/*.sfml|*.sfm`, so resource packs can add more (J:sfm/client/examples/SFMExampleProgram.java). Each template is listed under its NAME.

- "A simple program" (`a_simple_program.sfml`) — minimal `INPUT FROM a` / `OUTPUT TO b`; explains that inputs alone do nothing. — [R]
- "AE2 Inscribers" (`ae2_inscribers.sfml`) — keeps inscriber slots stocked with `OUTPUT RETAIN 2 … TO EACH … SLOTS n`, collects results by side, and runs a 200-tick `IF SOME last HAS LT 3` reshuffle. — [R]
- "Changelog" (`changelog.sfml`) — the full release notes back to 4.12.0, also shown by `/sfm changelog`. — [R]
- "Empty Slots" (`empty_slots.sfml`) — `OUTPUT TO EMPTY SLOTS IN storage` plus an occasional 300-second condensing pass. — [R]
- "Filtering" (`filtering.sfml`) — per-item limits, trailing commas, quoted regex vs `*` wildcards, and the statement-wide nature of EXCEPT. — [R]
- "Forget" (`forget.sfml`) — shows input accumulation and how `FORGET` / `FORGET a` scope inputs. — [R]
- "Furnace Manager" (`furnace_manager.sfml`) — smelting: 8 per furnace from the top, fuel `RETAIN 1` from the bottom, results from the bottom side. — [R]
- "IF statements" (`if_statements.sfml`) — worked truth table for EVERY/EACH/SOME/ONE/LONE/OVERALL, plus a NOT/AND example. — [R]
- "Known issues" (`known_issues.sfml`) — Discord and issue links, "Rebuild cable network" / `/sfm bust_cable_network_cache` advice, and the composter null-side caveat. — [R]
- "Limits" (`limits.sfml`) — catalogue of quantity, RETAIN and EACH combinations on INPUT and OUTPUT. — [R]
- "Redstone item movement" (`redstone_item_move.sfml`) — `redstone` used as a resource name without quotes. — [R]
- "Redstone signals" (`redstone_signals.sfml`) — `EVERY REDSTONE PULSE DO` and `IF REDSTONE GE 2`. — [R]
- "Fluids and other resource types" (`resource_types.sfml`) — the default item type, `fluid::`, fully qualified ids, and a generated list of every registered resource type (`$REPLACE_RESOURCE_TYPES_HERE$`, with config-disallowed types marked). — [R]
- "Round Robin" (`round_robin.sfml`) — recommends RETAIN instead, then demonstrates `ROUND ROBIN BY BLOCK` and `BY LABEL`. — [R]
- "Slots and sides" (`slots_and_sides.sfml`) — `top, west side slots 0,1,3-4,7-9,21` in lowercase syntax. — [R]
- "Tag Matching" (`tag_matching.sfml`) — every WITH/TAG/# form, wildcard namespaces and paths, `**`, NOT/AND/WITHOUT semantics, and combining tags with limits and EXCEPT. — [R]
- "Thank you!" (`thank_you.sfml`) — sponsor credits. — [R]
- "Timer triggers" (`timer_triggers.sfml`) — local vs GLOBAL clocks, PLUS offsets, the `20G+1` shorthand, and the random clock initialisation after an unload. — [R]
- Template interpolation — `$REPLACE_RESOURCE_TYPES_HERE$` is replaced at load time with `INPUT <type>:: FROM a` lines. — SFMExampleProgram `readProgramStringFromResource` — [R]
- Compile-failed templates — templates that fail to compile still list as "(compile failed) path". — SFMExampleProgram `fromResource` — [R]

## Performance and logging

- Per-manager logger — log levels OFF, TRACE, DEBUG, INFO, WARN and ERROR, chosen with buttons in the logs screen. TRACE, DEBUG and INFO **switch back to OFF after one program execution** that does work. Logs are pruned so they do not grow without bound. — J:sfm/common/logging/TranslatableLogger.java; ManagerBlockEntity `serverTick` — [R]
- Logs screen — log lines show "Xm Ys ago [LEVEL] message" with styling. Controls: "Copy logs" (Shift-click copies raw), "Clear logs", the level buttons and Done. When empty it shows a help text explaining that logging adds overhead. — J:sfm/client/screen/LogsScreen.java, LogsTextStylingHelper.java, LogsScreenMultiLineEditBox.java — [R]
- Log streaming — logs are only synced while a player has the logs screen open (`ServerboundManagerLogDesireUpdatePacket`). They are sent incrementally and chunked. — ManagerBlockEntity `sendUpdatePacket`; J:sfm/common/net/ClientboundManagerLogsPacket.java — [R]
- Log content:
  - TRACE: the cable-network and label-position dump, capability cache hit/miss, per-move trace.
  - DEBUG: gather-slots details, IF true/false, FORGET, "PROGRAM TICK BEGIN".
  - INFO: per-trigger and per-statement timings, the compile summary.
  - WARN: linter warnings.
  - ERROR: compile errors, capability not present.

  — `log.sfm.*` entries in GEN:assets/sfm/lang/en_us.json — [R]
- Tick-time history — 20-entry ring buffer, recorded only when a trigger fired. It is pushed to open GUIs. — ManagerBlockEntity `tickTimes`; J:sfm/common/net/ClientboundManagerGuiUpdatePacket.java — [R]
- Capability caching — each cable network caches adjacent capabilities per level. The cache is invalidated on cable add or remove; caches merge or split when networks join or break. — J:sfm/common/block_network/CableNetwork.java, SFMBlockCapabilityCacheForLevel.java — [R]
- Object pools — limited input and output slot objects are pooled, with invariant checks. — J:sfm/common/program/LimitedInputSlotObjectPool.java, LimitedOutputSlotObjectPool.java — [R]
- Regex cache — patterns are compiled once and common patterns become string ops. — J:sfm/common/program/RegexCache.java — [R]
- Resource-id expansion cache — J:sfml/ast/ResourceIdentifier.java `expansionCache` — [R]

## Keybindings

All keybindings are in the category "Super Factory Manager" (J:sfm/client/registry/SFMKeyMappings.java).

- Hold For More Info — Left Shift (universal). Shows extended tooltips: label gun key list, disk program preview. — [R]
- Toggle Container Inspector — Ctrl+I (GUI context). — [R]
- (WIP) Copy Hovered Item To Clipboard — unbound (GUI). — [R]
- Cycle label gun view — Left Alt (in-game, tap). — [R]
- Open Label Gun GUI — unbound (in-game). — [R]
- Label Gun Pick Block Modifier — Left Alt. — [R]
- Label Gun Contiguous Modifier — Left Ctrl. — [R]
- Label Gun Clear Modifier — Left Shift. — [R]
- Label Gun Scroll Modifier — Left Shift. — [R]
- Label Gun Next Label — unbound. — [R]
- Label Gun Previous Label — unbound. — [R]
- Label Gun Pull Modifier — Left Shift. — [R]
- Label Gun Target Manager Modifier — Right Alt. — [R]
- Text Editor - Accept Intellisense Suggestion — `\` (backslash) (GUI). — [R]
- Manager Screen - Open Text Editor — Ctrl+E (GUI). — [R]
- Title Screen - Open Text Editor — Ctrl+E (GUI; also on the Select World screen). — [R]
- Toggle network tool overlay — Left Alt (in-game, tap; hold + right-click attunes the tool). — [R]
- Hardcoded GUI keys — Manager screen: Ctrl+V paste, Ctrl+C copy, Ctrl+Shift+E examples. Editor: Shift+Enter, Tab/Shift+Tab, Ctrl+/, Ctrl+Space, Up/Down/Esc for the pick list. Label gun GUI: Enter. — ManagerScreen.java; SFMTextEditScreenV1.java; LabelGunScreen.java — [R]
- Facade modifiers — Ctrl and Alt (vanilla `Screen.hasControlDown/hasAltDown`, not rebindable) when right-clicking cables. — J:sfm/common/block/CableBlock.java — [R]
- Open Command Palette — Ctrl+K. — T:java/…/SFMKeyMappings.java (`command_palette`) — [X]

## Config options

Server configs live in `sfm-server.toml` (SERVER type; synced and shown to clients). Source: J:sfm/common/config/SFMServerConfig.java.

- `disableProgramExecution` (bool, false) — emergency stop; every program fails to compile. — [R]
- `logResourceLossToConsole` (bool, true) — print the resource-loss report. — [R]
- `timerTriggerMinimumIntervalInTicks` (int, 20, min 1). — [R]
- `timerTriggerMinimumIntervalInTicksWhenOnlyForgeEnergyIOStatementsPresent` (int, 1, min 1). — [R]
- `maxIfStatementsInTriggerBeforeSimulationIsntAllowed` (int, 10) — 2^n path simulation cap for the linter and output inspection. — [R]
- `maxDiskProblems` (int, 10) — errors and warnings stored on a disk. — [R]
- `disallowedResourceTypesForTransfer` (string list, empty) — pack-maker restriction on resource types. — [R]
- `levelsToShards` (enum JustOne | EachOne | SumLevels | SumLevelsScaledExponentially, default JustOne) — enchanted book to XP shard conversion. — [R]
- Program-cache invalidation — config load and reload bump a revision, clear the program cache and force managers to recompile. — SFMServerConfig `onConfigReloaded` — [R]

Client configs (CLIENT type) are in J:sfm/common/config/SFMClientConfig.java and J:sfm/common/config/SFMClientTextEditorConfig.java.

- `showLabelGunReminderOverlay` (bool, true). — [R]
- `showLabelGunPushPullMessages` (bool, true; added in 4.35.0). — [R]
- `showNetworkToolReminderOverlay` (bool, true). — [R]
- `showLineNumbers` (bool, false) in `sfm-client-program-editor.toml`. — [R]
- `intellisenseLevel` (OFF | BASIC | ADVANCED, default OFF) in `sfm-client-program-editor.toml`. — [R]
- `preferredEditor` (string, default `sfm:v1`; `sfm:v2` is the alternative, and an invalid value resets to v1) in `sfm-client-program-editor.toml`. — [R]

Experimental configs:

- `openAICompatibleEndpoint` (string, `http://localhost:11434`) in `sfm-ai.toml` (COMMON) — registered only in the IDE. — J:sfm/common/config/SFMAIConfig.java, SFMConfig.java — [X]
- `commandPaletteHistoryEnabled`, `terminalRustServerAddress`, `terminalRustServerExecutable` — T:java/…/SFMClientConfig.java (`command_history`, `terminal_*`) — [X]

## Commands

Source: J:sfm/common/command/SFMCommand.java, ConfigCommand.java.

- `/sfm bust_cable_network_cache` — any player — clears all cable network caches. — [R]
- `/sfm bust_water_network_cache` — any player — clears the water-tank network caches. — [R]
- `/sfm show_bad_cable_cache_entries <block>` — op level 2 — places the given block at every position the cable cache believes is a cable but is not (a debugging aid). — [R]
- `/sfm config show CLIENT|SERVER` — any player — shows the config TOML. — [R]
- `/sfm config edit SERVER` — op level 4 (owners) — opens an in-game TOML editor; saving updates and syncs the server config. — [R]
- `/sfm config edit CLIENT` — any player — edits the local client config in game. — [R]
- `/sfm changelog` — any player — opens the changelog template in the text viewer. — [R]
- `/sfm kit [targets]` — op level 2 — gives the starter kit (label gun, manager, disk, network tool, cable, chest). — [R]
- `/sfm export_info <includeHidden>` — registered only on the client side — dumps every item's id, tags and tooltip as JSON into `<gamedir>/sfm/`. The JEI export part is commented out. — J:sfm/client/export/ClientExportHelper.java — [R]
- `/sfm test run <pattern>` — IDE only — runs game tests whose names match a wildcard. — [X]

## Integrations

- JEI: Printing Press category — every `sfm:printing_press` recipe (form, ink, paper → result). — J:sfm/client/jei/PrintingPressJEICategory.java, SFMJEIPlugin.java — [R]
- JEI: Falling Anvil category — form creation from each printing recipe, disenchanting, and enchanted book → XP shard. Shows "Gets consumed" / "Not consumed" markers, and the catalysts follow the block tags. — J:sfm/client/jei/FallingAnvilJEICategory.java, FallingAnvil*Recipe.java — [R]
- JEI: GUI handler — exclusion zones for the manager screen buttons. — SFMJEIPlugin — [R]
- Mekanism (optional, ≥10.3.2): resource types — `chemical`, `gas`, `infusion`, `pigment` and `slurry`, all the unified chemical type on 1.21.1. — J:sfm/common/compat/SFMMekanismCompat.java — [R]
- Mekanism: export-inspector warning — "MEKANISM BLOCKS ARE READ-ONLY FROM THE NULL DIRECTION!!!!!!" is prepended for Mekanism blocks inspected with the null side. — ServerboundContainerExportsInspectionRequestPacket.java — [R]
- Mekanism: side-config inspection helper — `gatherInspectionResults` generates INPUT/OUTPUT lines from the machine's side config. Not wired into the 1.21.1 inspector (the call is commented out). — SFMMekanismCompat.java — [OV]/[X]
- Mekanism sidedness linter and auto side-config fixer — MekanismSidednessProgramLinter — [OV] (1.19.2)
- Mekanism: `mekanism_energy` resource type — [OV] (≤1.20.3)
- AE2 (optional): Energy Acceptor — wraps an AE2 energy acceptor as Forge Energy so `fe::` can push power into AE2. — J:sfm/common/capability/ae2/EnergyAcceptorBlockCapabilityProvider.java; J:sfm/common/registry/registration/SFMGlobalBlockCapabilityProviders.java — [R]
- AE2: Interface capability provider — excluded from the build (commented out). — J:sfm/common/capability/ae2/InterfaceCapabilityProvider.java — [X]
- AE2 inscriber template — RS:assets/sfm/template_programs/ae2_inscribers.sfml — [R]
- CC: Tweaked — `sfm_network` peripheral on cables and managers (manager list, disk and label-gun handles). Documented as supported for 1.21.1, but the `computercraft` flag is only in `sfm-dev`; there is no code in any release projection. — docs/cc tweaked integration.md; T:java (`computercraft`, `disk_readonly_access`, `label_readonly_access`, `form_readonly_access`, `mod_event_filtering`) — [X]
- Create, Ars Nouveau, Thermal, Industrial Foregoing, Dank storage — **no dedicated runtime integration** in the 1.21.1 build. They work only through standard NeoForge capabilities. Thermal, IF and Dank appear only as excluded gametests (source-excludes `gametest-java.txt`). — [R] (generic only); the gametests are [X]
- Generic capability support — any block exposing NeoForge Item, Fluid or Energy (or Mekanism Chemical) block capabilities works. SFM also provides capability fallbacks for cauldrons and for redstone signal. — J:sfm/common/registry/registration/SFMGlobalBlockCapabilityProviders.java; J:sfm/common/capability/* — [R]
- Planned (TODO only, not implemented) — Mekanism heat, Botania mana, Ars source, Flux, PNC pressure and heat, Nature's Aura, Create rotation. — SFMResourceTypes.java comment — n/a
- VSCode extension — external syntax highlighting, not in this repository. — README.md — n/a

## In-world rendering and HUD

- Label gun world overlay — translucent boxes (cached VBO) over labelled blocks, plus floating label names, filtered by view mode. Rendered at the AFTER_PARTICLES stage; the gun and the network tool can render together when held in both hands. — J:sfm/client/handler/ItemWorldRenderer.java — [R]
- Network tool world overlay — cable positions and capability-provider positions, visible through walls, plus a red box when the attuned position has no network. — ItemWorldRenderer — [R]
- Label gun view-mode HUD text — J:sfm/client/overlay/LabelGunReminderOverlay.java; J:sfm/client/registry/SFMOverlays.java — [R]
- Network tool HUD text — J:sfm/client/overlay/NetworkToolReminderOverlay.java — [R]
- HUD hidden in editor V2 — J:sfm/client/handler/OverlayHider.java — [R]
- Printing press renderer — renders the press's paper, form and ink in the world. — J:sfm/client/render/PrintingPressBlockEntityRenderer.java — [R]
- Form item renderer — a Printing Form renders as its referenced item. — J:sfm/client/render/FormItemRenderer.java, FormItemExtensions.java — [R]
- Facade rendering — baked-model wrappers retexture cables or fancy cables with the mimicked block's quads (fill mode, per-face direction), with block colour tinting and light emission. — J:sfm/client/render/CableFacadeBlockModelWrapper.java, FancyCableFacadeBlockModelWrapper.java, RetexturedBakedQuad.java, FacadeBlockColor.java; J:sfm/client/registry/SFMBlockModelWrappers.java, SFMBlockColors.java — [R]
- Manager tick-time graph — ManagerScreen (see the Manager screen section). — [R]
- Container inspector overlay — slot-index numbers drawn over GUI slots. — ContainerScreenInspectorHandler — [R]

## Misc quality-of-life

- "SFM Classic" built-in resource pack — an optional pack that restores the old textures. — J:sfm/client/registry/SFMPackFinders.java; RS:pack/classic — [R]
- Localisations — en_us (generated), fr_fr, pt_br, ru_ru, uk_ua, zh_cn. — RS:assets/sfm/lang/; GEN:assets/sfm/lang/en_us.json — [R]
- Confirmation screens with joke yes/no captions — random captions such as "lol do it" and "ABORT ABORT ABORT". Some screens have an enable delay. — J:sfm/client/screen/SFMConfirmationScreen.java; lang `gui.sfm.confirm.funny.*` — [R]
- Advanced tooltip hint — "Hold %s to know more." — lang `gui.sfm.advanced.tooltip.hint`; J:sfm/common/util/SFMItemUtils.java — [R]
- Program text normalisation — CR characters are stripped, and trailing whitespace and common indent are trimmed on save. — DiskItem `setProgram`; ManagerBlockEntity `setProgram` — [R]
- Disk naming from NAME — DiskItem `setProgramName` — [R]
- Automation-friendly manager — disks can be inserted or extracted by hoppers or SFM. Warnings are not rebuilt every swap. — ManagerBlockEntity — [R]
- Crash-report enrichment — the crash report gets "SFM Reminder" (how to disable execution) and "SFM Details" (disk diagnostics). — ManagerBlockEntity `fillCrashReportCategory` — [R]
- Issue-tracker URL in the mod metadata — RS:META-INF/neoforge.mods.toml — [R]
- Cable network self-repair — "Rebuild cable network" falls back to purging all networks on error. — ServerboundManagerRebuildPacket.java — [R]
- Capability discovery error safety — try/catch around third-party capability lookups. — J:sfm/common/capability/SFMBlockCapabilityDiscovery.java — [R]

## Network packets (client to server, plus the main server-to-client packets)

Source: J:sfm/common/net/, registered in J:sfm/common/registry/registration/SFMPackets.java. All of these are [R].

- `ServerboundManagerProgramPacket` — set the manager's disk program (from editor, paste or template).
- `ServerboundManagerResetPacket` — wipe disk program and labels.
- `ServerboundManagerRebuildPacket` — rebuild the cable network.
- `ServerboundManagerFixPacket` — auto-fix warnings.
- `ServerboundManagerSetLogLevelPacket` — change the manager log level (name ≤64 chars).
- `ServerboundManagerClearLogsPacket` — clear the manager logs.
- `ServerboundManagerLogDesireUpdatePacket` — open or close the log stream for this player.
- `ServerboundDiskItemSetProgramPacket` — save the program to a disk held in hand.
- `ServerboundLabelGunUsePacket` — gun use with flags for contiguous, pick, clear, pull and target-manager.
- `ServerboundLabelGunSetActiveLabelPacket` — set the active label (≤256 chars).
- `ServerboundLabelGunClearPacket` — clear the gun.
- `ServerboundLabelGunPrunePacket` — prune empty labels.
- `ServerboundLabelGunCycleViewModePacket` — cycle the view mode.
- `ServerboundNetworkToolUsePacket` — inspect a block, or attune (overlay key held).
- `ServerboundNetworkToolToggleOverlayPacket` — toggle the overlay.
- `ServerboundFacadePacket` — apply, change or clear facades with a spread mode.
- `ServerboundContainerExportsInspectionRequestPacket` — Export Inspector.
- `ServerboundLabelInspectionRequestPacket` — Ctrl+Space on a label.
- `ServerboundInputInspectionRequestPacket` — Ctrl+Space on an INPUT.
- `ServerboundOutputInspectionRequestPacket` — Ctrl+Space on an OUTPUT.
- `ServerboundIfStatementInspectionRequestPacket` — Ctrl+Space on an IF.
- `ServerboundBoolExprStatementInspectionRequestPacket` — Ctrl+Space on a condition.
- `ServerboundServerConfigRequestPacket` — view or edit the server config (edit needs level 4).
- `ServerboundServerConfigUpdatePacket` — submit edited server TOML (≤32767 chars; level 4).
- Server to client: `ClientboundManagerGuiUpdatePacket`, `ClientboundManagerLogsPacket`, `ClientboundManagerLogLevelUpdatedPacket`, `ClientboundLabelGunUseResponsePacket`, the `Clientbound*InspectionResultsPacket` family, `ClientboundServerConfigCommandPacket`, `ClientboundClientConfigCommandPacket`, `ClientboundShowChangelogPacket`.

## Experimental and flagged features (not in release 1.21.1): summary

Source: `platform/minecraft/core-liquid-template/feature-definitions.json` (206 flags) and the `sfm-dev/*` entries in `projections.json`. All of these are [X].

- Packet-computation language — LET/BE/LIKE/OBJECT/FIELD/GUID/STRING/INVOKE/CAPABILITY/AS/CREATE/NEW, with BROADCAST and CHANNEL for private transport and the client inbox. Flags 1.19.x only.
- Client-side SFML — CLIENT/SERVER BTW, EVERY FRAME FOR … AS, FRAME MOD, RENDER IMAGE, JSON/INVOKE client actions, client program consent and signing, the Client Manager block, the Touch Display block, image resources.
- Worded offsets (`OFFSET BY`) with a legacy-offset linter; interval overflow validation; literal globs; regex overlap fix (on for 1.19.2 and 26.1.2).
- Redstone live read and redstone buffer storage (1.19.x checkpoint, see `docs/redstone-support.md`).
- CC: Tweaked peripheral (`computercraft`), only in `sfm-dev`.
- Developer UI — command palette (Ctrl+K), workspace panels, file explorer, review tools, terminal (local, remote, vox), item inspection panels, canvas text editor, editor search and document history, keyboard profiles, client actions/automation harness, game puppet, screen and input diagnostics.
- Mekanism sidedness messages (26.1.2 only flag).
