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


def module(*sections: tuple[int, bytes]) -> bytes:
    """A Wasm binary from (section id, body) pairs; bodies are short enough for 1-byte sizes."""
    return b"\0asm\x01\0\0\0" + b"".join(bytes([sid, len(body)]) + body for sid, body in sections)


def function_import(module_name: str, name: str) -> bytes:
    encode = lambda text: bytes([len(text)]) + text.encode()
    return encode(module_name) + encode(name) + b"\x00\x00"


class GuestArtifactTests(unittest.TestCase):
    def test_factory_imports_and_memory_imports_are_parsed(self):
        memory = bytes([3]) + b"env" + bytes([6]) + b"memory" + b"\x02\x01\x01\x20"
        body = bytes([2]) + function_import("factory_v1", "log") + memory
        artifact = module((1, b"\x01\x60\x00\x00"), (2, body))
        self.assertEqual(guest.wasm_import_modules(artifact), ["factory_v1", "env"])
        self.assertIn("env", guest.guest_artifact_problem(artifact))

    def test_abi_only_artifact_passes(self):
        artifact = module((2, bytes([1]) + function_import("factory_v1", "panic")))
        self.assertIsNone(guest.guest_artifact_problem(artifact))

    def test_missing_oversized_and_malformed_artifacts_fail(self):
        self.assertIn("no Wasm", guest.guest_artifact_problem(b""))
        oversized = module() + bytes(guest.MAX_ARTIFACT)
        self.assertIn("artifact limit", guest.guest_artifact_problem(oversized))
        self.assertIn("WebAssembly", guest.guest_artifact_problem(b"\0asm\x02\0\0\0"))
        truncated = module((2, bytes([1]) + function_import("factory_v1", "log")))[:-3]
        self.assertIn("truncated", guest.guest_artifact_problem(truncated))


if __name__ == "__main__":
    unittest.main()
