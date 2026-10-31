# Q84 — Translations ready (2026-10-31)

## Audit

`Component.literal` has 55 uses in `src/main/java`.
- **Symbols:** ±, ★, arrows, ✉ and —.
- **Player-chosen names and labels.**
- **Item names and numbers.**
- **Program source and compiler diagnostics:** code, not prose.
- **Operator command diagnostics.**
- **Raw exception messages shown to players (the gap):** `NetworkNodeBlock` (2), `StorageDriveBlock` (2)
  and `CellUpgradeItem` sent `failure.getMessage()` to the action bar. The Drive screen sent the
  same English as its `detail` text.

## Change

- **`core/Refusal`:** `state(key, english, args…)` and `argument(...)` return an
  `IllegalStateException` or `IllegalArgumentException` that also carries a translation key and
  plain arguments. Existing handlers and tests still catch them, and `getMessage()` stays English
  for logs.
- **Converted throw sites:**
  - drive bays empty, occupied or all full;
  - drive needs recovery;
  - cell already placed;
  - hold one cell or bank in the offhand;
  - cell still in its drive, or bank still placed, when upgrading;
  - cell or bank upgrade refused (with its reason).
- **`platform/PlayerMessages.refusal`:** shows a refusal's own translation, or wraps any other
  failure as the translated "Refused: %s". All five action-bar sites use it.
- **Drive screen:** a translated refusal with no arguments travels as its key, and `DriveScreen`
  translates details that start with `message.factorycore.`. The out-of-range priority message is
  a key too.
- **`LangKeysTest`:** also reads keys passed to `Refusal.state`/`argument`.
- **New test:** `otherLanguagesUseOnlyKnownKeysWithMatchingPlaceholders` checks every shipped
  `lang/*.json` other than `en_us`, plus a stub `src/test/resources/lang-stub/fr_fr.json`, for
  unknown keys and `%s` count mismatches. It also proves the check catches both kinds of error.
- **Guide:** [TRANSLATING.md](../TRANSLATING.md) explains how to add a language.

## Tests

- `DriveScreenGameTests.fullDriveAndFluidInfiniteRefuseWithoutLoss` checks that the full-drive
  refusal arrives as `message.factorycore.refusal.drive_full`.
- `LangKeysTest` passes.
- Full GameTest suite: 8 consecutive runs, 492/492 each.

**Test fix found on the way:** `PlayerInventoryGameTests` waited only for the first VALID before
capturing a scope for a transfer context. A late second rebuild then made
`playerPendingIntentCannotReplayRefundOrAllowFreshExtraction` fail intermittently, with result
DENIED instead of RESERVED (diagnosed with a new failure message). Its `ready` now uses the Q114
settle helper, `TestMenus.ready`.

## Remaining

- The Inspect screen's "unlabelled" line is translated on the server (`getString()`), so it is
  always English.
- Other English messages from rarer, operator-facing failures still reach players through the
  translated "Refused: %s" wrapper.
