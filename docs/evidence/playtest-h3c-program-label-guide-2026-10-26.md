# H3c — Program Controller, Label Tool and Guide on the panel kit

Scope: playtest plan H3 group (c), findings K1 and K3. Decisions are in the
[ADR 0064 H3c amendment](../decisions/0064-panel-kit.md).

Implemented:

- **Program Controller** (`ProgramScreen`):
  - Kit panel and status strip.
  - Kit insets for the log area and the rule preview.
  - Side buttons in three groups: Save draft, Check, Deploy; Pause/Resume, Stop; Templates…,
    Insert / complete, Preview next rule, Upload Wasm.
  - **Templates…** opens the chooser on a new Templates category (the fifth category, after
    items, fluids, local labels and keywords). Templates are listed by name in `ProgramTemplates`
    order. Choosing one replaces the draft as an undoable edit.
- **Label Tool** (`LabelToolScreen`): a kit panel with a captioned label field. The hint, or
  the copied-settings notice, wraps to two lines. Forget copy and Save are in the footer.
- **Guide** (`GuideScreen`): the picture column and the recipe sit on kit panels with kit
  slots.

Checks:

- `runPresentationClient` has new captures, which I inspected:
  - `program-templates-scale-4` (the chooser listing templates by name);
  - `label-tool-scale-1`/`-4`;
  - the guide pages.
- `runTerminalClient` runs the program-authoring stages (Insert / complete, Preview next rule).
- `python3 scripts/verify.py --full`: passed (414 GameTests, release check); `runTerminalClient` and `runPresentationClient` passed.
