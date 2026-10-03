"""Reject Rust's successful-but-empty/skipped result summaries."""

import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "verify_guest", Path(__file__).resolve().parents[2] / "scripts/verify_guest.py"
)
guest = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guest)


class GuestVerificationTests(unittest.TestCase):
    def test_nonempty_native_suite_and_empty_doc_suite_pass(self):
        self.assertTrue(guest.nonempty_unskipped_tests(
            "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out\n"
            "test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out"
        ))

    def test_empty_missing_ignored_or_filtered_suite_fails(self):
        for output in (
            "", "test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out",
            "test result: ok. 1 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out",
            "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 1 filtered out",
        ):
            self.assertFalse(guest.nonempty_unskipped_tests(output))
