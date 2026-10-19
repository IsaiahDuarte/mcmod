"""The committed textures must be exactly what tools/art/generate_art.py produces."""

import importlib.util
from pathlib import Path
import struct
import unittest
import zlib

SPEC = importlib.util.spec_from_file_location(
    "generate_art", Path(__file__).resolve().parents[2] / "tools/art/generate_art.py"
)
art = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(art)


class ArtGeneratorTests(unittest.TestCase):
    def test_committed_assets_match_the_generator(self):
        self.assertEqual(art.main(["--check"]), 0)

    def test_generation_is_deterministic(self):
        first = art.outputs()
        self.assertEqual(first, art.outputs())
        self.assertGreater(len(first), 40)

    def test_textures_are_16px_rgba_pngs(self):
        for path, data in art.outputs().items():
            if path == art.SHEET:
                continue
            self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n", path)
            width, height, depth, kind = struct.unpack(">IIBB", data[16:26])
            self.assertEqual((width, height, depth, kind), (16, 16, 8, 6), path)
            length = struct.unpack(">I", data[33:37])[0]
            raw = zlib.decompress(data[41 : 41 + length])
            self.assertEqual(len(raw), 16 * (1 + 16 * 4), path)

    def test_tier_markings_differ_in_shape_not_only_color(self):
        """Grayscale renderings of the cell tiers must still differ."""

        def gray(name):
            canvas = art.item_textures()[name]
            return [
                [(r * 299 + g * 587 + b * 114) // 1000 > 128 and a > 0 for r, g, b, a in row]
                for row in canvas.pixels
            ]

        cells = ["starter_item_cell", "expanded_item_cell", "advanced_item_cell", "infinite_item_cell"]
        shapes = [gray(name) for name in cells]
        for i in range(len(shapes)):
            for j in range(i + 1, len(shapes)):
                self.assertNotEqual(shapes[i], shapes[j], (cells[i], cells[j]))
        self.assertNotEqual(gray("starter_item_cell"), gray("starter_fluid_cell"))

    def test_throughput_upgrades_differ_from_each_other_and_modules_in_shape(self):
        """ADR 0054: speed upgrades must read differently from capacity modules without color."""

        def opaque(name):
            canvas = art.item_textures()[name]
            return [[a > 0 and (r * 299 + g * 587 + b * 114) // 1000 > 128 for r, g, b, a in row]
                    for row in canvas.pixels]

        names = ["throughput_upgrade", "overdrive_upgrade", "advanced_module", "infinite_module"]
        shapes = {name: opaque(name) for name in names}
        self.assertNotEqual(shapes["throughput_upgrade"], shapes["overdrive_upgrade"])
        for upgrade in ("throughput_upgrade", "overdrive_upgrade"):
            for module in ("advanced_module", "infinite_module"):
                self.assertNotEqual(shapes[upgrade], shapes[module], (upgrade, module))


if __name__ == "__main__":
    unittest.main()
