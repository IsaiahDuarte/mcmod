# ADR 0042 — In-game Factory Guide

Status: selected; headless evidence for content and delivery. The book screen has not been
graphically verified.

## Decision

The **Factory Guide** is a craftable item (book + redstone, shapeless) that opens the
vanilla read-only `BookViewScreen` on the client with 15 translatable pages
(`guide.factorycore.page.0..14`). The pages cover these steps in order:

- first network;
- drive and cell;
- terminal;
- hopper → Buffered Interface → terminal;
- safe cell upgrades;
- machine presets;
- power;
- crafting and patterns;
- processing;
- programs;
- gateways;
- wireless access;
- links;
- material progression by tier (added by [ADR 0048](0048-final-balance.md)).

Basic storage and preset pages need neither scripts nor optional mods.

Each player receives one guide on first login. The marker lives in the player's
persisted NBT, so it survives death. Fixture runs set the system property
`factorycore.noWelcomeGuide=true` so automated players keep empty inventories; normal
servers never set it.

## Alternatives

Using the Patchouli/GuideME optional mods was rejected because basic help must not need
optional mods. A custom screen would duplicate vanilla paging. Pages stored as item
components would freeze one language into the item, whereas translatable components
resolve on the client.

## Consequences

Content changes are lang-file edits; `GuidePagesTest` enforces page count, length and
required topics. Pictures, recipe previews and clickable cross-links remain future work.
