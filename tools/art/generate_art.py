"""Generate Factory Core's original 16x16 block and item textures.

This script is the editable source of every PNG under
``src/main/resources/assets/factorycore/textures`` and of the status-lamp /
drive-bay overlay models and the blockstates that select them (see
``status_json``). Status overlays are the pixel difference between a device face
drawn in its lit/fault/paused/occupied state and the same face drawn idle. It uses only the Python
standard library, draws deterministic pixel art from the palette below and never
reads or traces Minecraft textures. Run it from the repository root:

    python3 tools/art/generate_art.py            # write textures + reference sheet
    python3 tools/art/generate_art.py --check    # fail if committed files drift

Meaning never depends on color alone: resource kinds use distinct symbols (cube,
droplet, lightning), tiers use one/two/three bars or a lemniscate for Infinite,
and gateway faces use dot / cross / arrow shapes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / "src/main/resources/assets/factorycore/textures"
SHEET = ROOT / "docs/art/reference-sheet.png"
GUI_SPRITES = TEXTURES / "gui/sprites"

# Palette (sRGB). Documented in docs/ASSETS.md; keep the two in sync.
CLEAR = (0, 0, 0, 0)
OUTLINE = (13, 14, 17, 255)
CASE_EDGE = (24, 25, 29, 255)
CASE_SHADOW = (33, 35, 40, 255)
CASE = (44, 47, 54, 255)
CASE_LIGHT = (61, 65, 74, 255)
CASE_HI = (84, 90, 102, 255)
PANEL_SHADOW = (8, 9, 11, 255)
PANEL = (18, 19, 23, 255)
PANEL_LINE = (28, 30, 35, 255)
COPPER_DARK = (122, 65, 38, 255)
COPPER = (181, 104, 60, 255)
COPPER_HI = (224, 145, 90, 255)
STEEL_DARK = (110, 116, 124, 255)
STEEL = (170, 176, 182, 255)
STEEL_HI = (228, 231, 234, 255)
SIGNAL_DARK = (24, 92, 128, 255)
SIGNAL = (54, 197, 240, 255)
SIGNAL_HI = (166, 236, 255, 255)
AMBER_DARK = (150, 88, 14, 255)
AMBER = (255, 181, 46, 255)
AMBER_HI = (255, 228, 150, 255)
FLUID_DARK = (30, 70, 140, 255)
FLUID = (58, 123, 213, 255)
FLUID_HI = (143, 193, 255, 255)
SCREEN_BG = (12, 36, 58, 255)
SCREEN = (42, 111, 176, 255)
SCREEN_HI = (127, 208, 255, 255)
CODE = (96, 214, 120, 255)
CODE_DARK = (40, 110, 60, 255)
CODE_HI = (190, 255, 200, 255)
PAPER_DARK = (150, 160, 178, 255)
PAPER = (205, 213, 226, 255)
PAPER_HI = (238, 242, 248, 255)
FIRE_DARK = (160, 40, 16, 255)
FIRE = (240, 110, 30, 255)
FIRE_HI = (255, 214, 90, 255)
FAULT_DARK = (120, 24, 20, 255)
FAULT = (236, 72, 54, 255)

# Tier ramps: (dark, base, highlight). Bars/lemniscate carry the meaning.
TIERS = {
    "starter": (STEEL_DARK, STEEL, STEEL_HI),
    "expanded": ((154, 106, 18, 255), (224, 169, 42, 255), (255, 226, 122, 255)),
    "advanced": ((28, 143, 138, 255), (63, 214, 207, 255), (184, 255, 249, 255)),
    "infinite": ((90, 42, 138, 255), (155, 93, 229, 255), (217, 184, 255, 255)),
}
TIER_MARK = {"starter": 1, "expanded": 2, "advanced": 3, "infinite": "inf"}

GLYPHS = {
    "drop": ["..#..", ".###.", ".###.", "#####", "#####", ".###."],
    "bolt": ["..##", ".##.", "####", ".##.", "##.."],
    "inf": [".##.##.", "#..#..#", ".##.##."],
    "chevron": ["..#..", ".#.#.", "#...#"],
    "arrow_up": ["...#...", "..###..", ".#####.", "...#...", "...#...", "...#..."],
    "dot": [".###.", "#...#", "#.#.#", "#...#", ".###."],
    "cross": [".###.", "##.##", "#.#.#", "##.##", ".###."],
    "prompt": ["#..", ".#.", "#.."],
    # Status shapes: fault is an exclamation mark, paused is two bars.
    "bang": ["##", "##", "##", "..", "##"],
    "pause": ["##.##", "##.##", "##.##", "##.##", "##.##"],
    "flow": ["#..", ".#.", "..#", ".#.", "#.."],
}

LAMPS = ("off", "active", "fault")
PROGRAM_LAMPS = ("off", "active", "paused", "fault")



def noise(name: str, x: int, y: int) -> int:
    """Deterministic per-pixel variation in {-1, 0, 0, +1}."""
    digest = hashlib.sha256(f"{name}:{x}:{y}".encode()).digest()[0]
    return (-1, 0, 0, 1)[digest & 3]


def tint(color, amount: int):
    r, g, b, a = color
    return (
        max(0, min(255, r + amount)),
        max(0, min(255, g + amount)),
        max(0, min(255, b + amount)),
        a,
    )


class Canvas:
    def __init__(self, name: str, width: int = 16, height: int = 16):
        self.name = name
        self.width = width
        self.height = height
        self.pixels = [[CLEAR] * width for _ in range(height)]

    def px(self, x, y, color):
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y][x] = color

    def rect(self, x0, y0, x1, y1, color, grain: int = 0):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, tint(color, grain * noise(self.name, x, y)) if grain else color)

    def hline(self, x0, x1, y, color):
        self.rect(x0, y, x1, y, color)

    def vline(self, x, y0, y1, color):
        self.rect(x, y0, x, y1, color)

    def glyph(self, x, y, rows, color, shadow=None):
        """Stamp a '#' bitmap; an optional shadow is offset one pixel down-right."""
        cells = [(dx, dy) for dy, row in enumerate(rows) for dx, ch in enumerate(row) if ch == "#"]
        if shadow is not None:
            for dx, dy in cells:
                self.px(x + dx + 1, y + dy + 1, shadow)
        for dx, dy in cells:
            self.px(x + dx, y + dy, color)

    def png(self) -> bytes:
        raw = b"".join(
            b"\x00" + b"".join(bytes(pixel) for pixel in row) for row in self.pixels
        )

        def chunk(kind: bytes, data: bytes) -> bytes:
            return (
                struct.pack(">I", len(data))
                + kind
                + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
            )

        header = struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0)
        return (
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b"")
        )


# ---------------------------------------------------------------- block parts


def casing(name, rivets=True):
    c = Canvas(name)
    c.rect(0, 0, 15, 15, CASE, grain=3)
    c.hline(1, 14, 1, CASE_LIGHT)
    c.vline(1, 1, 14, CASE_LIGHT)
    c.hline(1, 14, 14, CASE_SHADOW)
    c.vline(14, 2, 14, CASE_SHADOW)
    for i in range(16):
        for x, y in ((i, 0), (i, 15), (0, i), (15, i)):
            c.px(x, y, CASE_EDGE)
    if rivets:
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            c.px(x, y, CASE_HI)
            c.px(x + 1, y + 1, CASE_SHADOW) if x < 13 or y < 13 else None
    return c


def inset(c, x0, y0, x1, y1, fill=PANEL, grain=2):
    c.rect(x0, y0, x1, y1, fill, grain=grain)
    c.hline(x0, x1, y0, PANEL_SHADOW)
    c.vline(x0, y0, y1, PANEL_SHADOW)
    c.hline(x0 + 1, x1, y1 + 1, CASE_LIGHT) if y1 < 15 else None
    c.vline(x1 + 1, y0 + 1, y1 + 1, CASE_LIGHT) if x1 < 15 else None


def port(c, x0, y0, size=6):
    """A network port socket: copper ring around a black well with a signal pin."""
    x1, y1 = x0 + size - 1, y0 + size - 1
    c.rect(x0, y0, x1, y1, COPPER, grain=4)
    c.hline(x0, x1, y0, COPPER_HI)
    c.vline(x0, y0, y1, COPPER_HI)
    c.hline(x0, x1, y1, COPPER_DARK)
    c.vline(x1, y0, y1, COPPER_DARK)
    c.rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL_SHADOW)
    mid = x0 + size // 2 - 1
    c.rect(mid, y0 + size // 2 - 1, mid + 1, y0 + size // 2, SIGNAL)
    c.px(mid, y0 + size // 2 - 1, SIGNAL_HI)


def flat(name, base, grain=3, lines=None):
    c = Canvas(name)
    c.rect(0, 0, 15, 15, base, grain=grain)
    if lines:
        for y in range(0, 16, 4):
            c.hline(0, 15, y, lines)
    return c


# ---------------------------------------------------------------- blocks


def block_textures():
    out = {}
    out["casing"] = casing("casing")

    vent = casing("casing_vent")
    for y in (4, 7, 10):
        c = vent
        c.hline(4, 11, y, PANEL_SHADOW)
        c.hline(4, 11, y + 1, CASE_LIGHT)
    out["casing_vent"] = vent

    panel = Canvas("panel")
    panel.rect(0, 0, 15, 15, PANEL, grain=2)
    out["panel"] = panel

    out["copper"] = flat("copper", COPPER, grain=6)
    out["contact"] = flat("contact", STEEL, grain=5)
    slot = flat("slot", STEEL, grain=4)
    out["slot"] = slot
    # C1 Signal-mode Interface tray: a redstone-red slot face.
    out["signal_slot"] = flat("signal_slot", (178, 40, 34, 255), grain=4)
    # H6 Importer and Exporter: the Interface socket with a chevron on every casing face. The
    # Importer's points down ("V", into the network, cyan); the Exporter's points up ("Λ", out
    # to the machine, amber). The shape alone tells them apart.
    for name, rows, color, shadow in (
        ("importer_case", ("#.......#", ".#.....#.", "..#...#..", "...#.#...", "....#...."),
         SIGNAL, SIGNAL_DARK),
        ("exporter_case", ("....#....", "...#.#...", "..#...#..", ".#.....#.", "#.......#"),
         AMBER, AMBER_DARK),
    ):
        case = casing(name)
        inset(case, 3, 4, 12, 11)
        case.glyph(4, 5, rows, color, shadow)
        out[name] = case
    for tier in ("starter", "expanded", "advanced"):
        dark, base, hi = TIERS[tier]
        accent = Canvas(f"accent_{tier}")
        accent.rect(0, 0, 15, 15, base, grain=8)
        out[f"accent_{tier}"] = accent

    # Network Controller: hub glyph on the front (idle = dim), ports on every other face.
    out["network_controller_front"] = controller_front("off")
    side = casing("network_controller_side")
    port(side, 5, 5)
    out["network_controller_side"] = side

    # Cable: a 4-pixel band (v 6..9) whose length runs along u; junction at (0..3, 0..3).
    cable = Canvas("network_cable")
    cable.rect(0, 0, 15, 15, CASE, grain=3)
    for x in range(16):
        notch = x % 4 == 3
        cable.px(x, 6, CASE_SHADOW if notch else CASE_LIGHT)
        cable.px(x, 7, CASE_SHADOW if notch else CASE)
        cable.px(x, 8, SIGNAL_DARK if notch else SIGNAL)
        cable.px(x, 9, CASE_EDGE)
    cable.rect(0, 0, 3, 3, CASE_LIGHT)
    cable.hline(0, 3, 3, CASE_SHADOW)
    cable.vline(3, 0, 3, CASE_SHADOW)
    cable.rect(1, 1, 2, 2, SIGNAL)
    cable.px(1, 1, SIGNAL_HI)
    out["network_cable"] = cable

    # Storage Drive: four stacked bays, empty in the base face; cells are bay overlays.
    out["storage_drive_front"] = drive_front(())
    top = casing("storage_drive_top")
    inset(top, 3, 3, 11, 11)
    for x in (4, 6, 8, 10):
        top.vline(x, 4, 10, PANEL_LINE)
    out["storage_drive_top"] = top

    # Gateway: dot = downstream (toward viewer), cross = upstream, arrow = flow.
    out["gateway_downstream"] = gateway_downstream("off")
    in_face = casing("gateway_upstream")
    inset(in_face, 3, 3, 11, 11)
    in_face.glyph(5, 5, GLYPHS["cross"], AMBER)
    out["gateway_upstream"] = in_face
    flow = casing("gateway_side")
    inset(flow, 4, 2, 10, 12)
    flow.glyph(4, 3, GLYPHS["arrow_up"], SIGNAL)
    flow.hline(5, 9, 11, AMBER)
    flow.hline(6, 8, 10, AMBER_DARK)
    out["gateway_side"] = flow

    # Storage Terminal screen: the face samples uv (0,0)-(10,6).
    screen = Canvas("terminal_screen")
    screen.rect(0, 0, 15, 15, SCREEN_BG)
    screen.hline(1, 6, 0, SCREEN)
    screen.px(8, 0, SCREEN_HI)
    for i, color in enumerate((TIERS["starter"][1], TIERS["expanded"][1], TIERS["advanced"][1])):
        x = 1 + i * 3
        screen.rect(x, 2, x + 1, 3, color)
    screen.hline(1, 4, 5, SCREEN)
    screen.hline(6, 8, 5, SCREEN_HI)
    out["terminal_screen"] = screen

    # Crafting Unit: grid top, processor sides.
    grid = casing("crafting_unit_top")
    inset(grid, 2, 2, 12, 12)
    for gx in range(3):
        for gy in range(3):
            x, y = 3 + gx * 3, 3 + gy * 3
            grid.rect(x, y, x + 1, y + 1, COPPER)
            grid.px(x, y, COPPER_HI)
    out["crafting_unit_top"] = grid
    chip = casing("crafting_unit_side")
    inset(chip, 2, 2, 12, 12)
    chip.rect(5, 5, 9, 9, CASE_LIGHT)
    chip.rect(6, 6, 8, 8, COPPER)
    chip.px(6, 6, COPPER_HI)
    for i in (5, 7, 9):
        chip.px(i, 3, STEEL)
        chip.px(i, 11, STEEL)
        chip.px(3, i, STEEL)
        chip.px(11, i, STEEL)
        chip.px(i, 4, STEEL_DARK)
        chip.px(i, 10, STEEL_DARK)
        chip.px(4, i, STEEL_DARK)
        chip.px(10, i, STEEL_DARK)
    out["crafting_unit_side"] = chip

    # Crafting Interface: input arrow -> processing port -> output arrow.
    ci = casing("crafting_interface_front")
    inset(ci, 2, 3, 13, 11)
    port(ci, 6, 4, size=5)
    ci.hline(3, 5, 7, AMBER)
    ci.px(4, 6, AMBER)
    ci.px(4, 8, AMBER)
    ci.hline(11, 12, 7, SIGNAL)
    ci.px(12, 6, SIGNAL)
    ci.px(12, 8, SIGNAL)
    ci.px(13, 7, SIGNAL)
    out["crafting_interface_front"] = ci

    # Program Controller: code screen with a prompt; chip on top.
    out["program_controller_side"] = program_side("off")
    ptop = casing("program_controller_top")
    inset(ptop, 3, 3, 11, 11)
    ptop.rect(5, 5, 9, 9, CASE_LIGHT)
    ptop.glyph(6, 6, GLYPHS["prompt"], CODE)
    for i in (4, 6, 8, 10):
        ptop.px(i, 2, COPPER)
        ptop.px(i, 12, COPPER)
        ptop.px(2, i, COPPER)
        ptop.px(12, i, COPPER)
    out["program_controller_top"] = ptop

    # Fuel Generator.
    for lit in (False, True):
        name = "fuel_generator_front_on" if lit else "fuel_generator_front"
        gen = casing(name)
        inset(gen, 3, 4, 12, 12, fill=(14, 10, 8, 255) if not lit else FIRE_DARK)
        if lit:
            gen.rect(5, 8, 10, 11, FIRE)
            gen.rect(6, 6, 9, 9, FIRE)
            gen.rect(7, 9, 8, 11, FIRE_HI)
            gen.px(7, 5, FIRE)
        for x in (5, 7, 9, 11):
            gen.vline(x, 5, 12, STEEL_DARK)
        gen.hline(4, 11, 2, COPPER)
        gen.hline(4, 11, 13, COPPER_DARK)
        out[name] = gen
    gside = casing("fuel_generator_side")
    for y in (3, 5, 10, 12):
        gside.hline(2, 13, y, COPPER)
        gside.hline(2, 13, y + 1, COPPER_DARK)
    inset(gside, 5, 7, 10, 8, grain=0)
    gside.rect(5, 6, 10, 9, PANEL)
    gside.glyph(6, 5, GLYPHS["bolt"], AMBER, shadow=AMBER_DARK)
    out["fuel_generator_side"] = gside
    gtop = casing("fuel_generator_top")
    inset(gtop, 3, 3, 11, 11)
    for i in range(4, 11, 2):
        gtop.hline(4, 10, i, STEEL_DARK)
    gtop.rect(6, 6, 8, 8, CASE_LIGHT)
    gtop.px(7, 7, STEEL)
    out["fuel_generator_top"] = gtop

    # Wireless Access Point: broadcast arcs.
    out["wireless_access_point_top"] = access_point_top("off")
    out["wireless_access_point_side"] = access_point_side("off")

    out.update(status_overlays())
    return out


# ---------------------------------------------------------------- status faces
#
# Each face function draws the complete face for one state with the same canvas
# name (so the deterministic grain matches); overlays keep only changed pixels.


def bang(c, x, y):
    """Fault mark: a dark backing plate with a red exclamation mark."""
    c.rect(x - 1, y - 1, x + 2, y + 5, PANEL_SHADOW)
    c.glyph(x, y, GLYPHS["bang"], FAULT)


def controller_front(lamp):
    c = casing("network_controller_front")
    inset(c, 2, 2, 12, 12)
    lit = lamp == "active"
    hub, hub_hi, spoke = (SIGNAL, SIGNAL_HI, SIGNAL) if lit else (SIGNAL_DARK, SIGNAL, PANEL_LINE)
    c.rect(6, 6, 9, 9, hub)
    c.rect(6, 6, 7, 7, hub_hi)
    c.hline(6, 9, 9, SIGNAL_DARK)
    c.vline(9, 6, 9, SIGNAL_DARK)
    for x, y in ((3, 7), (3, 8), (12, 7), (12, 8), (7, 3), (8, 3), (7, 12), (8, 12)):
        c.px(x, y, spoke)
    c.hline(4, 5, 7, SIGNAL_DARK)
    c.hline(10, 11, 8, SIGNAL_DARK)
    c.vline(8, 4, 5, SIGNAL_DARK)
    c.vline(7, 10, 11, SIGNAL_DARK)
    for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
        c.px(x, y, SIGNAL_DARK)
    if lamp == "fault":
        bang(c, 7, 5)
    return c


def gateway_downstream(lamp):
    c = casing("gateway_downstream")
    inset(c, 3, 3, 11, 11)
    lit = lamp == "active"
    c.glyph(5, 5, GLYPHS["dot"], SIGNAL if lit else SIGNAL_DARK)
    c.px(7, 7, SIGNAL_HI if lit else SIGNAL)
    if lamp == "fault":
        bang(c, 9, 5)
    return c


def program_side(lamp):
    c = casing("program_controller_side")
    inset(c, 2, 2, 13, 11, fill=(10, 20, 14, 255), grain=1)
    lit = lamp == "active"
    code = CODE if lit else CODE_DARK
    c.glyph(3, 3, GLYPHS["prompt"], code)
    c.hline(7, 11, 4, CODE_DARK)
    c.hline(4, 9, 7, CODE_DARK)
    c.hline(4, 7, 9, code)
    c.px(9, 9, CODE_HI if lit else CODE_DARK)
    c.hline(4, 11, 13, STEEL_DARK)
    if lamp == "paused":
        c.rect(8, 5, 13, 10, (10, 20, 14, 255))
        c.glyph(8, 6, GLYPHS["pause"][:4], AMBER)
    if lamp == "fault":
        bang(c, 10, 5)
    return c


def access_point_top(lamp):
    c = casing("wireless_access_point_top")
    inset(c, 2, 2, 12, 12)
    lit = lamp == "active"
    c.rect(6, 6, 8, 8, SIGNAL if lit else SIGNAL_DARK)
    c.px(7, 7, SIGNAL_HI if lit else SIGNAL)
    ring = SIGNAL if lit else SIGNAL_DARK
    c.hline(5, 9, 4, ring)
    c.hline(5, 9, 10, ring)
    c.vline(4, 5, 9, ring)
    c.vline(10, 5, 9, ring)
    if lamp == "fault":
        c.rect(6, 5, 8, 9, PANEL_SHADOW)
        c.glyph(6, 5, GLYPHS["bang"], FAULT)
    return c


def access_point_side(lamp):
    c = casing("wireless_access_point_side")
    inset(c, 2, 2, 12, 12)
    lit = lamp == "active"
    c.rect(6, 9, 8, 11, SIGNAL if lit else SIGNAL_DARK)
    c.px(7, 9, SIGNAL_HI if lit else SIGNAL)
    c.glyph(4, 3, [".#####.", "#.....#", "..###..", ".#...#."], SIGNAL if lit else SIGNAL_DARK)
    if lamp == "fault":
        bang(c, 10, 6)
    return c


DRIVE_BAYS = (2, 5, 8, 11)


def drive_front(occupied):
    """Four bays; an occupied bay shows a cell face with a pull handle and lit status lamp."""
    c = casing("storage_drive_front", rivets=False)
    for i, y in enumerate(DRIVE_BAYS):
        c.rect(2, y, 13, y + 2, PANEL)
        c.hline(2, 13, y, PANEL_SHADOW)
        c.px(12, y + 1, PANEL_LINE)
        c.hline(2, 13, y + 3, CASE_LIGHT) if y + 3 < 14 else None
        if i in occupied:
            c.rect(3, y + 1, 11, y + 2, CASE_LIGHT)
            c.hline(3, 11, y + 2, CASE_SHADOW)
            c.rect(4, y + 1, 5, y + 1, STEEL)
            c.hline(7, 10, y + 1, CASE_HI)
            c.px(12, y + 1, SIGNAL)
    return c


def overlay(name, base, variant):
    """Transparent canvas holding only the pixels where ``variant`` differs from ``base``."""
    c = Canvas(name)
    for y in range(16):
        for x in range(16):
            if variant.pixels[y][x] != base.pixels[y][x]:
                c.px(x, y, variant.pixels[y][x])
    return c


# (texture prefix, face function, lamps with an overlay)
LAMP_FACES = (
    ("network_controller_lamp", controller_front, LAMPS[1:]),
    ("gateway_lamp", gateway_downstream, LAMPS[1:]),
    ("program_controller_lamp", program_side, PROGRAM_LAMPS[1:]),
    ("wireless_access_point_lamp", access_point_side, LAMPS[1:]),
    ("wireless_access_point_top_lamp", access_point_top, LAMPS[1:]),
)


def status_overlays():
    out = {}
    for prefix, face, lamps in LAMP_FACES:
        base = face("off")
        for lamp in lamps:
            out[f"{prefix}_{lamp}"] = overlay(f"{prefix}_{lamp}", base, face(lamp))
    empty = drive_front(())
    for i in range(4):
        out[f"storage_drive_bay_{i + 1}"] = overlay(f"bay_{i}", empty, drive_front((i,)))
    return out


# ---------------------------------------------------------------- status JSON

MODELS = ROOT / "src/main/resources/assets/factorycore/models/block"
BLOCKSTATES = ROOT / "src/main/resources/assets/factorycore/blockstates"
FACES = ("north", "south", "east", "west", "up", "down")
# Rotations of a north-facing model for each FACING value (network nodes).
NODE_ROTATION = {
    "north": {},
    "east": {"y": 90},
    "south": {"y": 180},
    "west": {"y": 270},
    "up": {"x": 270},
    "down": {"x": 90},
}
# Rotations of an up-facing model for each FACING value (link endpoints).
def overlay_template(faces, emissive, fallback):
    """A full-cube cutout overlay drawn coplanar over the base model on ``faces``."""
    element = {
        "from": [0, 0, 0],
        "to": [16, 16, 16],
        "shade": False,
        "faces": {face: {"texture": "#overlay", "cullface": face} for face in faces},
    }
    if emissive:
        element["neoforge_data"] = {"block_light": 15, "sky_light": 15, "ambient_occlusion": False}
    return {
        "render_type": "minecraft:cutout",
        "textures": {"particle": "#overlay", "overlay": f"factorycore:block/{fallback}"},
        "elements": [element],
    }


def child(parent, overlay_texture):
    return {"parent": f"factorycore:block/{parent}", "textures": {"overlay": f"factorycore:block/{overlay_texture}"}}


def part(model, rotation=None, when=None):
    entry = {"apply": {"model": f"factorycore:block/{model}", **(rotation or {})}}
    if when:
        entry = {"when": when, **entry}
    return entry


def status_json():
    models = {
        "status_overlay_north": overlay_template(("north",), True, "network_controller_lamp_active"),
        "status_overlay_sides": overlay_template(("north", "south", "east", "west"), True, "program_controller_lamp_active"),
        "status_overlay_up": overlay_template(("up",), True, "wireless_access_point_top_lamp_active"),
        "status_overlay_front_back": overlay_template(("north", "south"), False, "storage_drive_bay_1"),
    }
    states = {}

    def facing_device(block, base, prefix, rotations, template):
        parts = []
        for facing, rotation in rotations.items():
            parts.append(part(base, rotation, {"facing": facing}))
        for lamp in LAMPS[1:]:
            name = f"{prefix}_{lamp}"
            models[name] = child(template, name)
            for facing, rotation in rotations.items():
                parts.append(part(name, rotation, {"facing": facing, "lamp": lamp}))
        states[block] = {"multipart": parts}

    facing_device("network_controller", "network_controller", "network_controller_lamp", NODE_ROTATION, "status_overlay_north")
    facing_device("gateway", "gateway", "gateway_lamp", NODE_ROTATION, "status_overlay_north")

    parts = [part("program_controller")]
    for lamp in PROGRAM_LAMPS[1:]:
        name = f"program_controller_lamp_{lamp}"
        models[name] = child("status_overlay_sides", name)
        parts.append(part(name, when={"lamp": lamp}))
    states["program_controller"] = {"multipart": parts}

    parts = [part("wireless_access_point")]
    for lamp in LAMPS[1:]:
        side = f"wireless_access_point_lamp_{lamp}"
        top = f"wireless_access_point_top_lamp_{lamp}"
        models[side] = child("status_overlay_sides", side)
        models[top] = child("status_overlay_up", top)
        parts.append(part(side, when={"lamp": lamp}))
        parts.append(part(top, when={"lamp": lamp}))
    states["wireless_access_point"] = {"multipart": parts}

    parts = [part("storage_drive")]
    for i in range(1, 5):
        name = f"storage_drive_bay_{i}"
        models[name] = child("status_overlay_front_back", name)
        parts.append(part(name, when={f"bay_{i}": "true"}))
    states["storage_drive"] = {"multipart": parts}

    def encode(value):
        return (json.dumps(value, indent=2) + "\n").encode()

    files = {MODELS / f"{k}.json": encode(v) for k, v in models.items()}
    files.update({BLOCKSTATES / f"{k}.json": encode(v) for k, v in states.items()})
    return files


# ---------------------------------------------------------------- items


def tier_mark(c, tier, x, y):
    """Bars at (x, y) 2 px tall, or the lemniscate for Infinite, in the tier color."""
    dark, base, hi = TIERS[tier]
    mark = TIER_MARK[tier]
    if mark == "inf":
        c.glyph(x - 1, y, GLYPHS["inf"], hi)
        return
    for i in range(mark):
        c.vline(x + i * 2, y, y + 1, hi)


def cube(c, x, y):
    """6x6 three-shade isometric cube: the item (discrete resource) symbol."""
    shades = {"T": STEEL_HI, "L": STEEL, "R": CASE_HI}
    rows = [".TTTT.", "TTTTTT", "LLLRRR", "LLLRRR", "LLLRRR", ".LLRR."]
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch in shades:
                c.px(x + dx, y + dy, shades[ch])


def item_cell(tier):
    c = Canvas(f"{tier}_item_cell")
    dark, base, hi = TIERS[tier]
    # SD-card silhouette with a clipped top-right corner and contact strip on top.
    c.rect(3, 1, 12, 14, OUTLINE)
    c.rect(4, 2, 11, 13, CASE, grain=3)
    c.px(12, 1, CLEAR)
    c.px(11, 1, CLEAR)
    c.px(12, 2, CLEAR)
    c.px(11, 2, OUTLINE)
    c.px(10, 1, OUTLINE)
    for x in (5, 7, 9):
        c.px(x, 2, AMBER)
    c.hline(4, 10, 3, base)
    c.vline(4, 4, 13, CASE_LIGHT)
    c.rect(5, 4, 10, 9, PANEL)
    cube(c, 5, 4)
    c.hline(4, 11, 10, CASE_SHADOW)
    c.hline(4, 11, 13, dark)
    tier_mark(c, tier, 6, 11)
    return c


def fluid_cell(tier):
    c = Canvas(f"{tier}_fluid_cell")
    dark, base, hi = TIERS[tier]
    # Capsule tank with a valve cap.
    c.rect(6, 0, 9, 2, OUTLINE)
    c.rect(7, 1, 8, 1, base)
    c.rect(4, 2, 11, 14, OUTLINE)
    c.rect(3, 3, 12, 13, OUTLINE)
    c.rect(4, 3, 11, 13, CASE, grain=3)
    c.rect(5, 2, 10, 2, CASE_LIGHT)
    c.hline(4, 11, 3, base)
    c.rect(5, 4, 10, 10, PANEL)
    c.glyph(6, 4, GLYPHS["drop"], FLUID_HI)
    c.px(7, 6, (220, 236, 255, 255))
    c.hline(5, 10, 13, dark)
    tier_mark(c, tier, 6, 11)
    return c


def module(tier):
    c = Canvas(f"{tier}_module")
    dark, base, hi = TIERS[tier]
    # Upgrade board: dark PCB, copper traces, edge-connector teeth, up-chevron.
    c.rect(2, 2, 13, 12, OUTLINE)
    c.rect(3, 3, 12, 11, (24, 44, 36, 255), grain=2)
    c.hline(3, 12, 3, base)
    for x in range(3, 13, 2):
        c.px(x, 13, AMBER)
        c.px(x, 14, AMBER_DARK)
    c.hline(2, 13, 12, OUTLINE)
    c.vline(4, 5, 10, COPPER_DARK)
    c.vline(11, 5, 10, COPPER_DARK)
    c.glyph(5, 4, GLYPHS["chevron"], SIGNAL_HI)
    c.px(7, 5, SIGNAL)
    tier_mark(c, tier, 6, 8)
    return c


def throughput_upgrade(kind):
    """Speed card: heat-sink fins and right-pointing flow chevrons (">>", or ">>|" for Overdrive).

    The fins and sideways chevrons separate it from the capacity modules' upward chevron, and
    the end-stop bar separates the two tiers without relying on color.
    """
    tier = "advanced" if kind == "throughput" else "infinite"
    c = Canvas(f"{kind}_upgrade")
    dark, base, hi = TIERS[tier]
    for x in range(3, 13, 2):
        c.vline(x, 1, 3, COPPER)
        c.px(x, 1, COPPER_HI)
        c.px(x, 3, COPPER_DARK)
    c.rect(2, 4, 13, 13, OUTLINE)
    c.rect(3, 5, 12, 12, CASE, grain=3)
    c.hline(3, 12, 5, base)
    c.rect(4, 7, 11, 11, PANEL)
    if kind == "throughput":
        for x in (5, 8):
            c.glyph(x, 7, GLYPHS["flow"], hi)
    else:
        # Overdrive: fast-forward to the end stop (">>|").
        for x in (4, 7):
            c.glyph(x, 7, GLYPHS["flow"], hi)
        c.vline(10, 7, 11, AMBER_HI)
    c.hline(3, 12, 12, dark)
    for x in range(4, 12, 2):
        c.px(x, 14, AMBER)
    c.hline(2, 13, 13, OUTLINE)
    return c


def wireless_link():
    """Gateway link upgrade (ADR 0061): the upgrade card with the emitter dish of the old endpoint."""
    c = Canvas("wireless_link")
    c.rect(2, 2, 13, 13, OUTLINE)
    c.rect(3, 3, 12, 12, CASE, grain=3)
    c.rect(4, 4, 11, 11, PANEL)
    dish = [".#####.", "#.....#", "#.###.#", "#.#.#.#", "#.###.#", "#.....#", ".#####."]
    c.glyph(4, 4, dish, AMBER)
    c.px(7, 7, AMBER_HI)
    for x in range(4, 12, 2):
        c.px(x, 14, COPPER)
    return c


def label_tool():
    """D8 Label Tool: a paper tag on a copper-tipped handle."""
    c = Canvas("label_tool")
    for i in range(6):
        c.px(2 + i, 13 - i, OUTLINE)
        c.px(3 + i, 13 - i, COPPER if i > 2 else CASE_LIGHT)
    c.rect(7, 2, 13, 8, OUTLINE)
    c.rect(8, 3, 12, 7, PAPER, grain=2)
    c.hline(9, 11, 4, PAPER_DARK)
    c.hline(9, 11, 6, PAPER_DARK)
    c.px(8, 3, COPPER_HI)
    return c


def wrench():
    """Q22 Wrench: a steel open-ended spanner, diagonal, with a copper grip."""
    c = Canvas("wrench")
    # Handle from the bottom left toward the jaw; copper grip at the end.
    for i in range(9):
        x, y = 2 + i, 13 - i
        c.px(x - 1, y, OUTLINE)
        c.px(x, y, COPPER if i < 3 else STEEL)
        c.px(x, y - 1, COPPER_HI if i < 3 else STEEL_HI)
        c.px(x + 1, y, OUTLINE)
    # Open jaw: a steel ring around (12, 3) with its mouth to the top right.
    for x, y in ((10, 2), (10, 3), (10, 4), (11, 1), (12, 1), (11, 5), (12, 5), (13, 5), (14, 4)):
        c.px(x, y, STEEL)
    for x, y in ((9, 2), (9, 3), (9, 4), (11, 0), (12, 0), (10, 1), (10, 5), (11, 6), (12, 6),
                 (13, 6), (14, 5), (15, 4)):
        c.px(x, y, OUTLINE)
    c.px(11, 2, STEEL_DARK)
    c.px(13, 4, STEEL_DARK)
    return c


def pattern(kind):
    c = Canvas(f"{kind}_pattern")
    # Encoded card with a folded corner; corner tab marks the kind by shape.
    c.rect(2, 1, 13, 14, OUTLINE)
    c.rect(3, 2, 12, 13, PAPER, grain=3)
    c.rect(11, 1, 13, 3, CLEAR)
    c.px(11, 2, OUTLINE)
    c.px(12, 3, OUTLINE)
    c.px(13, 4, OUTLINE)
    c.rect(10, 2, 10, 4, PAPER_DARK)
    c.hline(11, 12, 4, PAPER_DARK)
    c.vline(3, 2, 13, PAPER_HI)
    c.hline(4, 12, 13, PAPER_DARK)
    if kind == "blank":
        for gx in range(3):
            for gy in range(3):
                c.px(5 + gx * 2, 5 + gy * 2, PAPER_DARK)
    elif kind == "crafting":
        for gx in range(3):
            for gy in range(3):
                c.px(4 + gx * 2, 5 + gy * 2, SIGNAL_DARK)
        c.px(6, 7, SIGNAL)
        c.px(9, 7, CASE)
        c.rect(10, 6, 11, 8, SIGNAL)
        c.px(10, 6, SIGNAL_HI)
    else:
        c.hline(4, 5, 8, AMBER_DARK)
        c.px(5, 7, AMBER_DARK)
        c.px(5, 9, AMBER_DARK)
        c.rect(6, 6, 8, 10, CASE)
        c.px(7, 8, AMBER)
        c.hline(9, 11, 8, AMBER_DARK)
        c.px(11, 7, AMBER_DARK)
        c.px(11, 9, AMBER_DARK)
    c.hline(4, 9, 11, CASE_LIGHT if kind != "blank" else PAPER_DARK)
    return c


def wireless_terminal():
    c = Canvas("wireless_terminal")
    c.vline(11, 0, 3, STEEL)
    c.px(11, 0, SIGNAL_HI)
    c.px(13, 1, SIGNAL)
    c.px(13, 2, SIGNAL)
    c.px(14, 0, SIGNAL_DARK)
    c.px(14, 3, SIGNAL_DARK)
    c.rect(3, 3, 12, 15, OUTLINE)
    c.rect(4, 4, 11, 14, CASE, grain=3)
    c.hline(4, 11, 4, CASE_LIGHT)
    c.rect(5, 5, 10, 9, SCREEN_BG)
    c.hline(5, 9, 5, SCREEN)
    c.rect(5, 7, 6, 8, TIERS["starter"][1])
    c.rect(8, 7, 9, 8, TIERS["expanded"][1])
    for x in (5, 7, 9):
        c.px(x, 11, STEEL)
        c.px(x, 13, STEEL)
    c.px(10, 11, AMBER)
    c.px(10, 13, STEEL_DARK)
    return c


def guide():
    c = Canvas("factory_guide")
    c.rect(2, 1, 13, 14, OUTLINE)
    c.rect(4, 2, 12, 13, CASE, grain=3)
    c.rect(3, 2, 3, 13, COPPER_DARK)
    c.vline(13, 3, 14, PAPER)
    c.hline(4, 13, 14, PAPER_DARK)
    c.vline(13, 2, 2, OUTLINE)
    for x, y in ((4, 2), (12, 2), (4, 13), (12, 13)):
        c.px(x, y, COPPER)
    c.rect(7, 6, 9, 8, SIGNAL)
    c.px(7, 6, SIGNAL_HI)
    for x, y in ((8, 4), (8, 10), (5, 7), (11, 7)):
        c.px(x, y, SIGNAL_DARK)
    c.hline(5, 11, 11, CASE_LIGHT)
    return c


def item_textures():
    out = {}
    for tier in ("starter", "expanded", "advanced", "infinite"):
        out[f"{tier}_item_cell"] = item_cell(tier)
    for tier in ("starter", "expanded", "advanced"):
        out[f"{tier}_fluid_cell"] = fluid_cell(tier)
    # Modules carry the marking of the tier they upgrade a cell to.
    out["expansion_module"] = module("expanded")
    out["advanced_module"] = module("advanced")
    out["infinite_module"] = module("infinite")
    out["throughput_upgrade"] = throughput_upgrade("throughput")
    out["overdrive_upgrade"] = throughput_upgrade("overdrive")
    out["wireless_link"] = wireless_link()
    out["label_tool"] = label_tool()
    out["wrench"] = wrench()
    for kind in ("blank", "crafting", "processing"):
        out[f"{kind}_pattern"] = pattern(kind)
    out["wireless_terminal"] = wireless_terminal()
    out["factory_guide"] = guide()
    return out


# ---------------------------------------------------------------- screen sprites

# Screen panels (ADR 0064) use a light, vanilla-scale palette so dark text and vanilla widgets
# read on them; the copper accent marks the selected choice.
GUI_OUTLINE = (24, 25, 29, 255)
GUI_FACE = (198, 198, 198, 255)
GUI_LIGHT = (255, 255, 255, 255)
GUI_SHADOW = (85, 85, 85, 255)
GUI_WELL = (139, 139, 139, 255)
GUI_WELL_SHADOW = (55, 55, 55, 255)


def bevel(c, x0, y0, x1, y1, light, shadow):
    """One-pixel raised edge: light on the top and left, shadow on the bottom and right."""
    c.hline(x0, x1 - 1, y0, light)
    c.vline(x0, y0, y1 - 1, light)
    c.hline(x0 + 1, x1, y1, shadow)
    c.vline(x1, y0 + 1, y1, shadow)


def gui_panel():
    """16x16 nine-slice (border 4): outlined, rounded panel with a two-pixel bevel."""
    c = Canvas("gui_panel")
    c.rect(1, 1, 14, 14, GUI_FACE)
    c.hline(2, 13, 0, GUI_OUTLINE)
    c.hline(2, 13, 15, GUI_OUTLINE)
    c.vline(0, 2, 13, GUI_OUTLINE)
    c.vline(15, 2, 13, GUI_OUTLINE)
    for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
        c.px(x, y, GUI_OUTLINE)
    bevel(c, 1, 1, 14, 14, GUI_LIGHT, GUI_SHADOW)
    c.px(1, 1, GUI_OUTLINE)
    c.px(14, 14, GUI_OUTLINE)
    bevel(c, 2, 2, 13, 13, GUI_LIGHT, GUI_SHADOW)
    c.px(2, 13, GUI_FACE)
    c.px(13, 2, GUI_FACE)
    return c


def gui_well(name, size):
    """Sunken area (a slot, list or nine-slice inset): shadow top-left, light bottom-right."""
    c = Canvas(name, size, size)
    c.rect(0, 0, size - 1, size - 1, GUI_WELL)
    c.hline(0, size - 2, 0, GUI_WELL_SHADOW)
    c.vline(0, 0, size - 2, GUI_WELL_SHADOW)
    c.hline(1, size - 1, size - 1, GUI_LIGHT)
    c.vline(size - 1, 1, size - 1, GUI_LIGHT)
    return c


def gui_choice_selected():
    """16x16 nine-slice (border 3): the chosen option of a choice row, pressed in copper."""
    c = Canvas("gui_choice_selected")
    c.rect(0, 0, 15, 15, GUI_OUTLINE)
    c.rect(1, 1, 14, 14, COPPER)
    c.hline(1, 14, 1, COPPER_DARK)
    c.vline(1, 1, 14, COPPER_DARK)
    c.hline(2, 14, 14, COPPER_HI)
    c.vline(14, 2, 14, COPPER_HI)
    return c


def gui_checkbox(checked):
    """9x9 box for a toggle; the tick is a shape, so the state never depends on color alone."""
    c = gui_well("gui_checkbox" + ("_checked" if checked else ""), 9)
    c.rect(1, 1, 7, 7, GUI_FACE)
    if checked:
        for x, y in ((2, 4), (3, 5), (4, 6), (5, 5), (6, 4), (6, 3), (7, 2), (3, 4), (4, 5), (5, 4)):
            c.px(x, y, GUI_OUTLINE)
    return c


GUI_TAB = (160, 160, 160, 255)


def gui_tab(selected):
    """16x16 nine-slice (border 4) tab on a panel's top edge. The selected tab has no bottom edge
    and is drawn over the panel's border so it joins the panel face; the others sit behind it."""
    c = Canvas("gui_tab" + ("_selected" if selected else ""))
    face = GUI_FACE if selected else GUI_TAB
    c.rect(1, 1, 14, 15, face)
    c.hline(2, 13, 0, GUI_OUTLINE)
    c.px(1, 1, GUI_OUTLINE)
    c.px(14, 1, GUI_OUTLINE)
    c.vline(0, 2, 15, GUI_OUTLINE)
    c.vline(15, 2, 15, GUI_OUTLINE)
    c.hline(2, 13, 1, GUI_LIGHT)
    c.vline(1, 2, 15, GUI_LIGHT)
    c.vline(14, 2, 15 if not selected else 12, GUI_SHADOW)
    if selected:
        # The last rows cover the panel's outline and bevel: face, with the bevel continuing.
        for y in (13, 14, 15):
            c.hline(0, 15, y, face)
        c.px(0, 13, GUI_OUTLINE)
        c.hline(0, 1, 14, GUI_LIGHT)
        c.hline(0, 1, 15, GUI_LIGHT)
    else:
        c.hline(0, 15, 15, GUI_OUTLINE)
    return c


def gui_sprites():
    """Sprite name -> (canvas, nine-slice border or None for a fixed-size sprite)."""
    return {
        "panel": (gui_panel(), 4),
        "inset": (gui_well("gui_inset", 8), 1),
        "slot": (gui_well("gui_slot", 18), None),
        "choice_selected": (gui_choice_selected(), 3),
        "checkbox": (gui_checkbox(False), None),
        "checkbox_checked": (gui_checkbox(True), None),
        "tab": (gui_tab(False), 4),
        "tab_selected": (gui_tab(True), 4),
    }


def sprite_meta(canvas, border):
    scaling = {"type": "nine_slice", "width": canvas.width, "height": canvas.height, "border": border}
    return (json.dumps({"gui": {"scaling": scaling}}, indent=2) + "\n").encode()


def reference_sheet(blocks, items, scale=4, columns=10):
    """Unlabeled contact sheet: blocks (sorted) then items (sorted), 4x, 2 px gutter."""
    entries = [blocks[k] for k in sorted(blocks)] + [items[k] for k in sorted(items)]
    cell = 16 * scale + 4
    rows = (len(entries) + columns - 1) // columns
    sheet = Canvas("sheet", columns * cell, rows * cell)
    sheet.rect(0, 0, sheet.width - 1, sheet.height - 1, (96, 100, 108, 255))
    for index, entry in enumerate(entries):
        ox = (index % columns) * cell + 2
        oy = (index // columns) * cell + 2
        for y in range(16):
            for x in range(16):
                color = entry.pixels[y][x]
                if color[3] == 0:
                    color = (120, 124, 132, 255) if (x + y) % 2 else (140, 144, 152, 255)
                for dy in range(scale):
                    for dx in range(scale):
                        sheet.px(ox + x * scale + dx, oy + y * scale + dy, color)
    return sheet


def outputs():
    blocks = block_textures()
    items = item_textures()
    files = {TEXTURES / "block" / f"{k}.png": v.png() for k, v in blocks.items()}
    files.update({TEXTURES / "item" / f"{k}.png": v.png() for k, v in items.items()})
    for name, (canvas, border) in gui_sprites().items():
        files[GUI_SPRITES / f"{name}.png"] = canvas.png()
        if border is not None:
            files[GUI_SPRITES / f"{name}.png.mcmeta"] = sprite_meta(canvas, border)
    files[SHEET] = reference_sheet(blocks, items).png()
    files.update(status_json())
    return files


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail on drift instead of writing")
    args = parser.parse_args(argv)
    files = outputs()
    expected_dirs = {TEXTURES / "block", TEXTURES / "item", GUI_SPRITES}
    stale = [
        path
        for directory in expected_dirs
        if directory.is_dir()
        for pattern in ("*.png", "*.png.mcmeta")
        for path in directory.glob(pattern)
        if path not in files
    ]
    if args.check:
        drift = [p for p, data in files.items() if not p.is_file() or p.read_bytes() != data]
        for path in drift + stale:
            print(f"FAIL: {path.relative_to(ROOT)} differs from tools/art/generate_art.py")
        return 1 if drift or stale else 0
    for path in stale:
        path.unlink()
    for path, data in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    print(f"Wrote {len(files)} files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
