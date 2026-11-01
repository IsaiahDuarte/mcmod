# Factory Core programs

A Program Controller runs **rules** written as plain sentences. This page is the whole language.
The [decision record](decisions/0068-sentence-programs.md) explains why it looks this way, and
[Program examples](PROGRAM_EXAMPLES.md) has programs from one line to a whole factory.

```text
# Run a cable to each furnace and label it "furnace" with the Label Tool.
every second:
    move 8 raw materials from storage to furnace top
    keep 8 coal in furnace side
    move everything from furnace bottom to storage
```

## Rules

A rule starts at the left edge with **when** it runs and ends with `:`. The lines indented under
it are what it does. A rule with one sentence can be one line:
`every second: move coal from storage to furnace`.

| Start of a rule | Runs |
| --- | --- |
| `every second:` | every 20 ticks |
| `every 5 seconds:`, `every 0.5 seconds:` | 100 ticks, 10 ticks |
| `every 10 ticks:`, `every minute:`, `every 2 minutes:` | as written |
| `when button is powered:` | every 10 ticks while the machine labelled `button` has redstone |
| `when button is unpowered:` | every 10 ticks while it has none |

- **Timing:** rules run every half second (10 ticks) to every hour.
- **Names:** `called fuel` after the start names a rule: `every second called fuel:`.
  Deploying again replaces rules with the same name. Unnamed rules are `rule-1`, `rule-2`, …
  in order.
- **Indentation:** indent with spaces or tabs, the same within a block. `#` starts a comment.

## Sentences

| Sentence | Does |
| --- | --- |
| `move coal from storage to furnace` | moves up to 64 |
| `move 8 coal from storage to furnace` | moves up to 8 (at most 4,096) |
| `move all cobblestone from chest to storage` | moves up to 4,096 |
| `move iron ingots from furnace to chest` | machine to machine, without storage |
| `keep 8 coal in furnace` | tops each machine up to 8, moving only what is missing |
| `craft 64 oak planks` | asks autocrafting for 64 |
| `turn on alarm` / `turn off alarm` | sets Signal-mode Interfaces labelled `alarm` to 15 / 0 |
| `set lamp redstone to 7` | sets them to 0–15 |
| `say "Low on coal"` | writes a line to the program log |

- **Moves:** a move needs a machine on at least one side; storage to storage is not a move.
- **Labels:** a label names every machine with that label. A move between two labels pairs
  every source with every target (up to 64 pairs).

## Places

- **`storage`:** the network's storage.
- **A label:** what you set with the Label Tool. Write it in quotes if it has spaces:
  `"smelter out"`.
- **A face of the machine:** `top`, `bottom`, `side` (north), `north`, `south`, `east`,
  `west`. For example, `furnace top` is the furnace's input and `furnace bottom` its output.
- **Slots:** `slot 2` or `slots 0 to 3`, counted on the face used, from 0:
  `move everything from furnace bottom slot 0 to storage`.

## Names

Write items the way the game shows them, singular or plural:

- **Items:** `coal`, `iron ingots`, `oak planks`, `block of iron`.
- **Item groups (common tags):** `raw materials`, `ingots`, `ores`. Use `tag c:ingots` to name a
  tag exactly.
- **Fluids:** `fluid water`, `fluid lava`. Energy is `energy` (FE).
- **Everything:** `everything` (every item) and `fluids` (every fluid).
- **Leaving things out:** `except`, as in `everything except dirt` or
  `*_ingot except iron ingot`.
- **Patterns:** one `*` in an ID: `*_ingot` is every mod's ingots, `minecraft:raw_*` only
  vanilla's.
- **Other mods:** a name only one mod has works as is. When two mods share a name, write the one
  you mean with its mod: `create:brass_ingot`.

## Checks

```text
every second:
    if furnace has less than 8 coal:
        move 8 coal from storage to furnace side
    otherwise if storage has no coal:
        say "Out of coal"
    otherwise:
        say "Fuelled"
```

| Check | True when |
| --- | --- |
| `furnace has less than 8 coal` | the labelled machines hold fewer than 8 in total |
| `storage has at least 64 oak planks` | storage holds 64 or more |
| `button is powered` / `button is unpowered` | that machine has redstone power / has none |

- **Comparisons:** `less than`, `more than`, `at least`, `at most`, `exactly`; `has no coal`
  means exactly 0, and `has any coal` means more than 0.
- **Combining:** `and`, `or` and `not`, with parentheses to group; `and` binds tighter than
  `or`.

## Each machine

`for each` repeats sentences for every machine with a label. The name you choose stands for
one machine:

```text
every second:
    for each worker in workers:
        if worker has less than 16 cobblestone:
            move 16 cobblestone from storage to worker
```

## Limits

- **Size:** 64 rules, 512 sentences, blocks 8 deep, 16,384 characters, labels of 64
  characters and text of 128.
- **Cost:** each rule run costs about 4 FE. A rule's moves run one after another; give slow
  or important moves their own rule.
- **When a rule cannot act:** its log line says why (machine busy, stock reserved, rate
  limit), and it tries again next time without losing items.

## Mistakes

The editor underlines a problem as you type and says what it expected, often with a fix:

```text
2:10 I don't know an item called 'iron ingit'
Did you mean iron ingot?
```

**Check** runs the same check on the server, against the items and labels in your world.
