# Q50 — Guide hotkey and ? buttons (2026-10-31)

## Change

- **`GuideContent.pageFor(itemId)`:** returns the first page that pictures the item, page 0 for
  any other Factory Core item, and -1 otherwise.
- **`GuideScreen.open(page)`:** opens the guide over the current screen. Closing it returns to that
  screen, so a device menu stays open on the server meanwhile.
- **`FactoryCoreKeys.GUIDE` (G, GUI context, rebindable):** `GuideLinks` handles
  `ScreenEvent.KeyPressed.Pre` in any container screen and opens the page for the Factory Core
  item under the mouse. It is ignored while a text box has focus, so typing "g" in a search box
  still types.
- **? button:** `GuideLinks` adds it through `ScreenEvent.Init.Post` to 13 device screens, each
  mapped to its device's item. It goes in the first free spot: beside the panel's top-right corner,
  inside it, then beside the bottom-right corner. A spot counts as free only if no widget is there,
  including hidden ones such as the upgrade slot. The first graphical run caught the button on top
  of the Terminal's Patterns tab, which led to this rule.

## Tests

- `GuidePagesTest.itemsOpenTheFirstPageThatPicturesThem`.
- The presentation client's `guideFromImporter` step:
  - presses the Importer screen's **?**: the guide opens at page 6 ("Machines"), and closing it
    returns to the Importer screen;
  - in the survival inventory, G over an Importer item (the hovered slot set as a pointer would)
    opens page 6.
  - Evidence lines: `guide from importer button: page 6` and `guide from hovered importer: page 6`.
- Screenshots checked: `drive-scale-4.png` (beside the top-right corner),
  `terminal-fluids-buckets-scale-1.png` (beside the tab row, clear of Patterns), and
  `guide-from-importer-scale-2.png`.

## Tour fixes found on the way

- **`slotRemoval`:** after taking an upgrade out, the Exporter's tier change rebuilds the network.
  Before Q119 that closed the screen, and the next step silently depended on it. The tour now
  closes the screen itself.
- **Step origins:** the tour's timeout message now names the source line that created the stuck
  step (`upgrade:1828`), which is how this was found.

## Open

- One run timed out at `jobsTour:1401`, where a clicked pattern did not return to its slot. Four
  other runs passed that step. Not reproduced, so recorded here only.
