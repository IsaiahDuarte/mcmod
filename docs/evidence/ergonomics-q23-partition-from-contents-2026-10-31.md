# Q23 — Partition from contents (2026-10-31)

**From contents** on the Drive's partition panel replaces the bay's slots with the cell's stored
types (up to 16, in catalog order, skipping emptied rows) and sets **Put in: only listed**. A
plain resource becomes its ID; one with non-default components stays an exact match. An empty
cell is refused and the partition left as it was. The command goes through the existing drive
filter path (viewer, rate gate, EDIT check); `DrivePackets` protocol 3 → 4 for the new action.

## Checks

- `./gradlew -q runGameTestServer`: 471 passed, twice.
  `DrivePartitionGameTests.fromContentsPartitionsTheCellToWhatItHolds` stores dirt, stone and
  oak logs, presses From contents twice, and expects exactly those three IDs under INCLUDE (so it
  replaces rather than appends); pressing it on the empty cell first leaves no partition.
- Presentation client: `drive-partition-scale-1.png` and `-scale-4.png` show the button beside
  the mode button, readable at both scales with no overlap.
- `python3 scripts/verify.py`: PASS.
