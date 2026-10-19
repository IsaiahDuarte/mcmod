"""Generate Factory Core's original 16x16 block and item textures.

This script is the editable source of every PNG under
``src/main/resources/assets/factorycore/textures``. It uses only the Python
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
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = ROOT / "src/main/resources/assets/factorycore/textures"
SHEET = ROOT / "docs/art/reference-sheet.png"

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
PAPER_DARK = (150, 160, 178, 255)
PAPER = (205, 213, 226, 255)
PAPER_HI = (238, 242, 248, 255)
FIRE_DARK = (160, 40, 16, 255)
FIRE = (240, 110, 30, 255)
FIRE_HI = (255, 214, 90, 255)

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
}


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
    interface = Canvas("interface_panel")
    interface.rect(0, 0, 15, 15, SCREEN, grain=4)
    for i in range(0, 16, 3):
        interface.hline(0, 15, i, (36, 96, 152, 255))
    out["interface_panel"] = interface
    for tier in ("starter", "expanded", "advanced"):
        dark, base, hi = TIERS[tier]
        accent = Canvas(f"accent_{tier}")
        accent.rect(0, 0, 15, 15, base, grain=8)
        out[f"accent_{tier}"] = accent

    # Network Controller: hub glyph on the front, ports on every other face.
    front = casing("network_controller_front")
    inset(front, 2, 2, 12, 12)
    front.rect(6, 6, 9, 9, SIGNAL)
    front.rect(6, 6, 7, 7, SIGNAL_HI)
    front.hline(6, 9, 9, SIGNAL_DARK)
    front.vline(9, 6, 9, SIGNAL_DARK)
    for x, y in ((3, 7), (3, 8), (12, 7), (12, 8), (7, 3), (8, 3), (7, 12), (8, 12)):
        front.px(x, y, SIGNAL)
    front.hline(4, 5, 7, SIGNAL_DARK)
    front.hline(10, 11, 8, SIGNAL_DARK)
    front.vline(8, 4, 5, SIGNAL_DARK)
    front.vline(7, 10, 11, SIGNAL_DARK)
    for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
        front.px(x, y, SIGNAL_DARK)
    out["network_controller_front"] = front
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

    # Storage Drive: four stacked bays, each with a pull handle and a status lamp.
    drive = casing("storage_drive_front", rivets=False)
    for i, y in enumerate((2, 5, 8, 11)):
        drive.rect(2, y, 13, y + 2, PANEL)
        drive.hline(2, 13, y, PANEL_SHADOW)
        drive.hline(3, 9, y + 1, PANEL_LINE)
        drive.rect(4, y + 1, 5, y + 1, STEEL_DARK)
        drive.px(12, y + 1, SIGNAL if i % 2 == 0 else SIGNAL_DARK)
        drive.hline(2, 13, y + 3, CASE_LIGHT) if y + 3 < 14 else None
    out["storage_drive_front"] = drive
    top = casing("storage_drive_top")
    inset(top, 3, 3, 11, 11)
    for x in (4, 6, 8, 10):
        top.vline(x, 4, 10, PANEL_LINE)
    out["storage_drive_top"] = top

    # Gateway: dot = downstream (toward viewer), cross = upstream, arrow = flow.
    out_face = casing("gateway_downstream")
    inset(out_face, 3, 3, 11, 11)
    out_face.glyph(5, 5, GLYPHS["dot"], SIGNAL)
    out_face.px(7, 7, SIGNAL_HI)
    out["gateway_downstream"] = out_face
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
    prog = casing("program_controller_side")
    inset(prog, 2, 2, 13, 11, fill=(10, 20, 14, 255), grain=1)
    prog.glyph(3, 3, GLYPHS["prompt"], CODE)
    prog.hline(7, 11, 4, CODE_DARK)
    prog.hline(4, 9, 7, CODE_DARK)
    prog.hline(4, 7, 9, CODE)
    prog.px(9, 9, CODE)
    prog.hline(4, 11, 13, STEEL_DARK)
    out["program_controller_side"] = prog
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
    ap_top = casing("wireless_access_point_top")
    inset(ap_top, 2, 2, 12, 12)
    ap_top.rect(6, 6, 8, 8, SIGNAL)
    ap_top.px(7, 7, SIGNAL_HI)
    for (x0, y0, x1, y1) in ((4, 4, 10, 10),):
        ap_top.hline(x0 + 1, x1 - 1, y0, SIGNAL_DARK)
        ap_top.hline(x0 + 1, x1 - 1, y1, SIGNAL_DARK)
        ap_top.vline(x0, y0 + 1, y1 - 1, SIGNAL_DARK)
        ap_top.vline(x1, y0 + 1, y1 - 1, SIGNAL_DARK)
    out["wireless_access_point_top"] = ap_top
    ap_side = casing("wireless_access_point_side")
    inset(ap_side, 2, 2, 12, 12)
    ap_side.rect(6, 9, 8, 11, SIGNAL)
    ap_side.px(7, 9, SIGNAL_HI)
    arcs = [".#####.", "#.....#", "..###..", ".#...#."]
    ap_side.glyph(4, 3, arcs, SIGNAL)
    out["wireless_access_point_side"] = ap_side

    # Wireless Link Endpoint: emitter dish (top), arrow toward partner, wired back.
    lt = casing("wireless_link_endpoint_top")
    inset(lt, 2, 2, 12, 12)
    lt.glyph(4, 4, [".#####.", "#.....#", "#.###.#", "#.#.#.#", "#.###.#", "#.....#", ".#####."], AMBER)
    lt.px(7, 7, AMBER_HI)
    out["wireless_link_endpoint_top"] = lt
    ls = casing("wireless_link_endpoint_side")
    inset(ls, 4, 2, 10, 12)
    ls.glyph(4, 3, GLYPHS["arrow_up"], AMBER)
    ls.hline(5, 9, 11, COPPER)
    out["wireless_link_endpoint_side"] = ls
    lb = casing("wireless_link_endpoint_back")
    port(lb, 5, 5)
    out["wireless_link_endpoint_back"] = lb
    return out


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
    for kind in ("blank", "crafting", "processing"):
        out[f"{kind}_pattern"] = pattern(kind)
    out["wireless_terminal"] = wireless_terminal()
    out["factory_guide"] = guide()
    return out


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
    files[SHEET] = reference_sheet(blocks, items).png()
    return files


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail on drift instead of writing")
    args = parser.parse_args(argv)
    files = outputs()
    expected_dirs = {TEXTURES / "block", TEXTURES / "item"}
    stale = [
        path
        for directory in expected_dirs
        if directory.is_dir()
        for path in directory.glob("*.png")
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
