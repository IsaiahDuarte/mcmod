# Q143 — Interface screen (2026-11-01)

From playthrough note 20: the Interface screen "clashing with my sort mod, and the buttons are so
confusing … trying to figure out how to switch it and it just switching directions".

## Sort-mod clash

**Cause.** The user's pack has Inventory Tweaks Refoxed 1.3.2. I read its jar
(`invtweaks.events.ClientEvents`):

- For any group of 9 or more player slots, it adds a 14×16 sort button at the right-most (then
  lowest) slot's `x + 17, y`.
- It places the button on `ScreenEvent.Init.Post`, whether or not the slot is shown.
- The Buffer screen keeps the 36 player slots in its menu but hides them on its Settings page. So
  the button landed on the settings controls.

**Fix.** The new `ForeignWidgets` handler covers every Factory Core container screen:

- At `Init.Post`, it takes a snapshot of the screen's widgets at HIGHEST priority. At LOWEST, any
  widget that is new and whose class is not ours counts as foreign.
- Before each frame, a foreign widget is shown only while some slot is active and it overlaps
  none of our visible widgets. Otherwise it is hidden, and a hidden widget takes no clicks either.

This needs no dependency on, or configuration for, the sort mod.

## Fewer, clearer buttons (Buffer)

- **Set buttons removed:** Rate, Goal and the per-slot goal no longer have Set buttons.
- **Typed numbers:** a valid typed number goes to the draft 6 ticks after the last keystroke, or
  at once with Enter. Apply then saves it, because the draft is only applied by Apply.
- **Invalid input:** while typing, nothing is sent. Pressing Enter on an invalid number still
  says it is invalid.
- **Wider boxes:** the Rate and Goal boxes now fill their halves of the row.

## Explicit direction (Storage Interface)

- **Before:** the only way to change the side was the Wrench, which turns the Interface to the
  *next* face that touches a block.
- **Side row:** the screen now shows Down / Up / North / South / East / West, with the current
  face selected.
  - **Enabled faces:** only faces with a container, meaning a block with a block entity that is
    not a Factory Core device or cable.
  - **Tooltips:** each face says "Face the Chest to the north instead.", "Facing the …", or
    "Nothing to face to the …".
- **Server:** pressing a face sends menu button `ConnectorSettingsMenu.TURN + face`. The server
  checks it, calls the same guarded `ConnectorEntity.turn` that the Wrench uses (Edit, and not
  while a transfer is in flight), and then reopens the screen on the new facing.
- **Info line:** it no longer names the side, so the panel doesn't name the side twice in two
  different ways.
- **View packet:** it carries `facing` and the six face name keys. Its bounds are checked, and
  it can be empty for a pending view.
- **Wrench:** unchanged, and the guide points to the Side row.

## Tests

- **Unit:** `ConnectorSettingsPacketsTest` checks that the faces and facing round-trip, and that
  a facing outside 0–5, a face count other than 0 or 6, and an over-long key are all rejected.
- **GameTest:** `screenFaceButtonTurnsToTheChosenBlock` sets up a chest to the east (current), a
  barrel above, stone below and the controller to the west.
  - The view lists the chest and barrel only.
  - The controller, the stone and the current face are refused, and the facing stays unchanged.
  - The barrel's face turns the Interface, and the screen reopens.
- **Presentation tour:** a test listener adds a sort button using Inventory Tweaks' exact rule
  while the Buffer screen opens.
  - On Settings the button is hidden. On Inventory it is shown at 508,266, clear of every widget.
    Back on Settings it is hidden again.
  - The check throws if the shown button overlaps any of our widgets.
- **Screenshots looked at:**
  - `connector-scale-4.png`: the Side row with North selected and the faces without a container
    greyed out. In the first run Down was enabled for the floor, which led to the block-entity
    rule.
  - `buffer-scale-4.png`: the wider Rate and Goal boxes, without Set.
