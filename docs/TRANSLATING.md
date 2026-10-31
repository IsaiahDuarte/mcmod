# Adding a language

Factory Core's player text lives in `src/main/resources/assets/factorycore/lang/en_us.json`. Every
other language is a file next to it, named by the Minecraft locale code (`fr_fr.json`,
`de_de.json`, `pt_br.json`, …).

1. Copy the keys you want to translate from `en_us.json` into the new file. Missing keys fall back
   to English, so a partial translation is fine.
2. Keep every `%s` (or `%1$s`) placeholder. A translation must have the same number of
   placeholders as the English entry, because the game fills them with names, counts and reasons.
3. Do not add keys that `en_us.json` does not have. They are never used.
4. Run `python3 scripts/verify.py`. `LangKeysTest.otherLanguagesUseOnlyKnownKeysWithMatchingPlaceholders`
   fails on an unknown key or a placeholder mismatch, and names the file and key.

What is not translated:
- Program source and the friendly language's keywords (`move`, `every`, `from storage`), which
  include the lines of the Inspect screen.
- Names players give to devices and labels.
- Operator command diagnostics.
- Compiler messages.

For code: show refusals to players with `PlayerMessages.refusal(failure)`. Throw player-facing
refusals with `Refusal.state(key, english, args…)` or `Refusal.argument(...)`, which keep the usual
exception types and English for logs. Never put an exception's English message straight into a
`Component.literal`.
