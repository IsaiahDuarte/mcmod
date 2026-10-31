# 0068 — Sentence programs (friendly language v3, syntax) and the offline program helper

Status: accepted 2026-10-31 (QD11, syntax part: the player chose **plain sentences** and an
**offline MCP helper first**). Amended 2026-10-31: sentences are the **only** program language.
The brace language of ADRs 0035 and 0062 is removed, because this is a new mod with no worlds
or programs to keep working. The other QD11 features (pulse triggers, redstone strength,
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

### One language

`SentenceCompiler`, used through `FriendlyCompiler`, produces the existing `ProgramIr`. The
evaluator, scheduling, permissions, Wasm and limits do not change. The brace grammar of ADRs
0035 and 0062 was removed. A program written with braces gets "Programs don't use braces" and a
hint. The IR records keep their names, and their meanings are unchanged.

`ProgramPrinter` turns any `ProgramIr.Program` back into sentences. It is used for pinned
deploys, the offline helper and the editor's inserts. Before the brace language was removed,
every brace fixture and template was printed to sentences and compiled back to equal IR. The
tests and examples were converted that way. The sentence fixtures are now pinned to their IR in
`program/sentences-ir.txt`.

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
sentence source (what the editor shows) and a canonical form: the same program printed with no
vocabulary, so every name is its ID path or full ID (`iron ingot`, `create:brass_ingot`,
`tag c:ingots`). The canonical form is what is recompiled on load, so a changed mod list or
language never changes a deployed program's meaning. Drafts store only the source. Pinning
happens on the server, so packets do not change.

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
- The game vocabulary (`GameVocabulary`) comes from the live registries and loaded tags, so any
  installed mod's items work. A generated copy (`program_vocabulary.txt`, from `runData`) serves
  tests and the offline helper.
