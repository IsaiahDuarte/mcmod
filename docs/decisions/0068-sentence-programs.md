# 0068 — Sentence programs (friendly language v3, syntax) and the offline program helper

Status: accepted 2026-10-31 (QD11, syntax part: the player chose **plain sentences** and an
**offline MCP helper first**). The other QD11 features (pulse triggers, redstone strength,
source retain, empty slots, quantifiers, relative sides, timer offsets, per-item limits, tag
expressions) are not decided here. They stay open and will extend this grammar later.

## Context

The friendly language (ADRs 0035 and 0062) is close to SFM's in power, but its punctuation reads
like code: `item("minecraft:raw_iron")`, `machines("furnace")`, braces, `has < 8 of`. The player
asked for a language that is "very easy to understand", and for an MCP server.

Research outside Minecraft:

- **Hedy** ([ICER 2020](https://hedy.org/research/Hedy_A_Gradual_Language_for_Programming_Education_2020.pdf)):
  the first form has almost no syntax, and each concept arrives in its simplest form. So a
  one-line rule must be valid, and sides, slots and conditions are optional add-ons.
- **Inform 7:** English-like source is pleasant, but free-form English is easy to misread. So
  this is a **small fixed set of sentence shapes**, not natural-language parsing.
- **IFTTT and Home Assistant automations:** trigger, then check, then action. So the shapes are
  `every …` / `when …`, then `if …`, then `move`, `keep`, `craft` or `turn on`.
- **Elm** ([Compiler errors for humans](https://elm-lang.org/news/compiler-errors-for-humans)):
  an error names where parsing stopped, says what was expected with an example and suggests a
  fix. There is one error per mistake, and a JSON form for tools.

## Decision

### One engine, two spellings

Sentence programs are a second front end (`SentenceCompiler`) that produces the existing
`ProgramIr`. The evaluator, scheduling, permissions, Wasm and limits do not change. A program is
in **sentence style** when its first line that is not blank and not a comment has a `:` and no
`{` outside quoted text. Brace programs keep `:` inside quoted IDs. Otherwise it is a brace
program (v1/v2), compiled exactly as today. The `FriendlyV1PinTest` pins
and the v2 fixtures must not change. The two styles cannot be mixed in one program.

`ProgramPrinter` turns any `ProgramIr.Program` back into sentences. Every v2 program has a
sentence spelling, so the editor's **Convert to sentences** never loses meaning. A test
compiles every fixture and template in both styles and checks that the IR is equal.

### Names and why meaning is pinned

Items are written as words: `coal`, `iron ingots`, `raw materials`. A phrase is resolved by a
`Vocabulary` passed into the compiler, which keeps `core` free of Minecraft types. The
resolution order is:

1. An item whose ID path matches the words joined by `_`, singular or plural
   (`iron ingots` → `iron_ingot`). `minecraft:` comes first. Otherwise there must be exactly one
   match across mods.
2. An item whose English display name matches (`block of iron` → `iron_block`).
3. A common tag `c:<words>` (`raw materials`, `ingots`, `ores`).
4. `fluid` before a phrase (`fluid water`), and `energy`, as in v2.
5. Glob and ID forms stay available for experts: `*_ingot`, `create:brass_ingot`.

Anything else is an error that suggests the closest names.

Display names depend on installed mods and language files, so **deploy saves both** the
sentence source (what the editor shows) and the canonical brace form printed from the IR
(what runs and is recompiled on load). A world opened with a mod removed therefore behaves as
v2 does: TRAPPED with a diagnostic, never a guessed meaning. Drafts store only the source.

### Grammar

Indentation shows blocks. Tabs and spaces may not be mixed within a block. A rule with a single
sentence may be written on one line: `every second: move coal from storage to furnace`.
Keywords ignore case. `#` starts a comment.

```text
rule      = trigger ":" block ;
trigger   = "every" [ NUMBER ] UNIT [ "called" NAME ]          (* NUMBER may be 0.5 *)
          | "when" place "is" ( "powered" | "unpowered" ) [ "called" NAME ] ;
UNIT      = "tick" | "ticks" | "second" | "seconds" | "minute" | "minutes" ;
sentence  = "move" [ amount ] stuff "from" place "to" place
          | "keep" INT stuff "in" place
          | "craft" INT stuff
          | "if" check ":" block { "otherwise if" check ":" block } [ "otherwise" ":" block ]
          | "for each" NAME "in" LABEL ":" block
          | "turn on" LABEL | "turn off" LABEL | "set" LABEL "redstone to" INT
          | "say" STRING ;
amount    = INT | "all" ;
stuff     = ( "everything" | "fluids" | phrase ) { "except" phrase } ;
phrase    = [ "fluid" ] ( WORD { WORD } | STRING ) | "tag" ( WORD | STRING ) ;
            (* "tag ingots" is c:ingots when that tag exists, else minecraft:ingots *)
place     = "storage" | LABEL [ face ] [ "slot" INT | "slots" INT "to" INT ] ;
face      = "top" | "bottom" | "side" | "up" | "down" | "north" | "south" | "east" | "west" ;
check     = part { ( "and" | "or" ) part } ;
part      = [ "not" ] ( place "has" COMPARE INT stuff | place "is powered" | "(" check ")" ) ;
COMPARE   = "less than" | "more than" | "at least" | "at most" | "exactly" ;
LABEL     = WORD | STRING ;   (* any word but "storage": labels sit in fixed places *)
```

### Meanings

- **Intervals:** `every second` is 20 ticks. The interval must be whole ticks within v1's
  10–72,000 range.
- **`when X is powered:`** means `every 10 ticks: if X is powered:`.
- **Faces:** `top`/`bottom` are `up`/`down`, and `side` is `north` (any side of a vanilla
  furnace or hopper).
- **`move` amounts:** with no amount, the limit is 64 as in v2. `move all` uses `MAX_LIMIT`.
- **`keep N stuff in place`:** the v1 `keep` that moves only the shortfall. `Keep` gains an
  optional face and slot range, an additive IR change with v1/v2 IR unchanged. On a label it
  applies to each machine.
- **Redstone:** `turn on X` is `emit redstone 15 on X`, and `turn off X` is strength 0.
- **Logging:** `say "…"` is `log`.
- **Conditions:** `storage has …` is the v1 stock condition, and `and` binds tighter than `or`.

### Errors

`Diagnostic` gains an optional `hint` and an optional `example`. Every sentence error names
what was expected ("I expected `from` after the item, like `move coal from storage to furnace`").
Unknown names get up to three suggestions by edit distance. Only the first error in a sentence
is reported, and the compiler resumes at the next line. The limits (`MAX_DIAGNOSTICS`, source
size, statements, depth) are unchanged.

### Offline program helper (MCP)

`tools/program-helper` is a standalone stdio MCP server. It depends only on `core` and a
generated vocabulary file, with no game, network or world access. Its tools:

- `language_reference`: the sentence guide.
- `list_examples`: the templates, as sentences.
- `check_program`: JSON diagnostics plus the canonical brace form.
- `convert_program`: brace to sentences.

An AI client writes programs from English itself and uses `check_program` until it compiles.
A live link to a running world is a separate later decision. It would need opt-in,
localhost-only access and the player's permissions.

## Consequences

- Templates and the guide switch to sentences, with the brace form still documented.
- The Rust SDK is unaffected.
- Program packets carry the canonical form, which bumps the protocol version.
