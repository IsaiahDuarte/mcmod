# U9a — In-game guide pictures

Scope: U9 (in-game guide part). Minecraft 1.21.1, NeoForge 21.1.252, Java 21, macOS arm64.

Implemented:

- `GuideScreen` now extends the vanilla `BookViewScreen`. It tracks the page through
  the protected page-turn hooks, then draws the page's blocks in a column left of
  the book and the first block's real crafting recipe right of it. The recipe is
  found in the client recipe manager, shown as a 3×3 grid with an arrow and the
  result; ingredients that accept several items cycle through them every second.
  Hovering any picture shows a tooltip. A side panel is skipped when the window
  leaves too little room beside the book.
- `GuideContent.PICTURES` lists the pictured registry ids for each page.
- Guide page 6 now describes the machine screen and one-step preset Apply (U6), and
  page 15 says deployed presets keep running after an upgrade (U6).

Checks:

- `GuideGameTests.everyGuidePictureIsAnItemWithARecipe` checks that there is one
  list per page, that every id is a registered item, and that each page's first
  picture has a crafting recipe on the server.
- `python3 scripts/verify.py` PASS; all 348 required GameTests pass.
- `runPresentationClient` PASS under the GUI lock and `caffeinate`. I inspected
  `guide-scale-4.png` (the Factory Guide recipe) and `guide-throughput-scale-4.png`
  (the Throughput/Overdrive pictures and the Throughput recipe). At the 427×240
  minimum GUI there is room on both sides; nothing overlaps the book or Done.

Moved: the PLAYER_GUIDE/ADMIN_GUIDE split (U9b) now runs after Wave B. U9's own note
says the guides should describe the final controls, and U11–U16 still change them.
