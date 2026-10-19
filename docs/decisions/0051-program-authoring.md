# ADR 0051 — Native program authoring and read-only previews

Status: implemented; graphical and JEI checks passed 2026-10-19 after three editor fixes ([evidence](../evidence/program-authoring-client-v1-2026-10-19.md)).

## Decision

Retain the native friendly-language editor and vanilla multiline cursor/selection,
clipboard, wrapping and navigation semantics. A dedicated scroll widget owns the
vanilla text field rather than reflecting into private widget fields. Token colors
are display-only; the existing compiler remains authoritative. A source edit runs
the bounded compiler locally for positioned error underlines, while Save/Check and
Deploy retain server compilation. Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z navigate a per-open
editor history of at most 64 complete source snapshots (each at most 16,384 UTF-16
characters). A template, paste or resource insertion is one edit. A new edit after
undo discards the old redo branch. Resizing retains the history; closing discards it.
Undo restores text with the cursor at the end.

The native Insert picker searches registered items/fluids and language keywords
without JEI. Ctrl+Space opens the same picker for the cursor prefix, choosing the
item/fluid/local-label category from its enclosing call. Completion replaces the
whole identifier or quoted literal contents, preserving delimiters; manual insertion
adds a complete escaped expression. Local labels are a separately VIEW-authorized
server query of the published segment's sorted label index, 16 labels per page.
No parent, descendant or sibling machine catalog is included. Selecting a label
inserts `machines("label")`; the friendly language addresses configured Machine
Interface ports through these labels and has no separate port-expression syntax.
Registry choices are public registry identities, not a storage-stock listing.

The editor's Preview button saves the draft (requires EDIT), then requests one native
rule snapshot (requires current VIEW). Each repeated click selects the next rule.
The server compiles the saved draft and invokes the existing bounded evaluator using
the viewer's current read-only host. It never publishes commands, reserves resources,
creates jobs, invokes guest code or charges FE. Only a successfully evaluated
invocation is described, with exact machine IDs, resource filters and amounts;
a trap describes the failure without presenting partial actions. At most eight
192-character lines are sent, explicitly marking omitted actions. The client wraps
and pages the full received text. Conditions use current available digital-cell stock,
matching native runtime semantics; this advisory snapshot predicts neither eventual
execution nor external-machine behavior. Wasm validation/deployment is unchanged.

Authoring payloads bind menu ID and random session to the current controller and
recheck current VIEW/reach/loaded scope before each reply. Queries share a separate
five-tick session throttle and preview only one of at most 64 rules, with the ordinary
1,024-step/256-command/64-machine evaluator limits. Network/segment IDs accompany
the response and are exposed by the View scope control's tooltip. Scope changes
invalidate the query at the server; a displayed snapshot remains advisory.
There is no persistence schema or host ABI change.

## Alternatives and consequences

Reflecting into vanilla widget internals would be brittle and cannot provide a
stable completion/cursor contract. A general editor framework would add a dependency
and unnecessary scope. The native widget keeps the established field semantics and
adds only the authoring features required for V1.

Running a draft to preview it would mutate player resources and require execution
permissions. Evaluating through the read-only host preserves the same target and
condition behavior without those effects. A full-program preview could multiply
host work by 64 rules; explicit one-rule snapshots bound each request. Snapshots do
not promise future transfer success and do not bypass authorization on deployment
or execution. Label pages avoid unbounded network scans and remain inaccessible
while topology is rebuilding.
