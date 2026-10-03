"""Behavioral checks for repository verification, including false-green cases."""

import importlib.util
import io
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
MODULE_SPEC = importlib.util.spec_from_file_location("verify", ROOT / "scripts/verify.py")
verifier = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(verifier)


class RepositoryVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in verifier.REQUIRED_FILES:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                "# Fixture\n" if target.suffix == ".md" else "fixture\n",
                encoding="utf-8",
            )
        for name in ("SPEC.md", "docs/IMPLEMENTATION.md"):
            (self.root / name).write_text("# Fixture\n\n| A01 | Example |\n", encoding="utf-8")

    def write_readme(self, content):
        (self.root / "README.md").write_text(content, encoding="utf-8")

    def test_valid_docs_with_relative_file_links_pass(self):
        self.write_readme("# Fixture\n\n[Rules](docs/ENGINEERING.md)\n")
        self.assertEqual([], verifier.verify(self.root))

    def test_missing_required_document_fails(self):
        (self.root / "AGENTS.md").unlink()
        self.assertIn("Missing required file: AGENTS.md", verifier.verify(self.root))

    def test_missing_local_file_fails(self):
        self.write_readme("# Fixture\n\n[Missing](missing.md)\n")
        self.assertTrue(any("broken local link" in error for error in verifier.verify(self.root)))

    def test_link_outside_repository_fails(self):
        self.write_readme("# Fixture\n\n[Outside](../outside.md)\n")
        self.assertTrue(any("escapes repository" in error for error in verifier.verify(self.root)))

    def test_examples_and_external_links_do_not_require_local_files(self):
        self.write_readme(
            "# Fixture\n\n[External](https://example.com/docs)\n"
            "~~~text\n[Example](not-a-real-file.md)\n~~~\n"
        )
        self.assertEqual([], verifier.verify(self.root))

    def test_source_cannot_pass_without_real_build_gates(self):
        source = self.root / "src/main/java/Storage.java"
        source.parent.mkdir(parents=True)
        source.write_text("class Storage {}\n", encoding="utf-8")
        self.assertTrue(any("implementation verification is not configured" in error
                            for error in verifier.verify(self.root)))

    def test_title_inside_example_does_not_satisfy_document_title(self):
        self.write_readme("~~~text\n# Example title\n~~~\n")
        self.assertTrue(any("missing level-one" in error for error in verifier.verify(self.root)))

    def test_conflict_marker_fails(self):
        self.write_readme("# Fixture\n\n<<<<<<< HEAD\nconflict\n")
        self.assertTrue(any("merge conflict marker" in error for error in verifier.verify(self.root)))

    def test_unclosed_fence_cannot_hide_broken_documentation(self):
        self.write_readme("# Fixture\n\n~~~text\n[Hidden](missing.md)\n")
        self.assertTrue(any("unclosed code fence" in error for error in verifier.verify(self.root)))

    def test_untracked_acceptance_criterion_fails(self):
        (self.root / "SPEC.md").write_text(
            "# Fixture\n\n| A01 | First |\n| A02 | Second |\n", encoding="utf-8"
        )
        self.assertIn("Acceptance A02: missing from implementation plan.", verifier.verify(self.root))

    def test_duplicate_acceptance_criterion_fails(self):
        (self.root / "SPEC.md").write_text(
            "# Fixture\n\n| A01 | First |\n| A01 | Duplicate |\n", encoding="utf-8"
        )
        self.assertTrue(any("duplicate acceptance IDs" in error for error in verifier.verify(self.root)))

    def test_empty_acceptance_tables_fail(self):
        for name in ("SPEC.md", "docs/IMPLEMENTATION.md"):
            (self.root / name).write_text("# Fixture\n", encoding="utf-8")
        self.assertEqual(2, sum("missing nonempty acceptance-ID" in error
                                for error in verifier.verify(self.root)))

    def test_empty_tooling_suite_is_not_a_pass(self):
        stream = io.StringIO()
        with patch.object(verifier.unittest.TestLoader, "discover", return_value=unittest.TestSuite()):
            self.assertFalse(verifier.run_tooling_tests(self.root, stream))
        self.assertIn("collection is empty", stream.getvalue())

    def test_failing_tooling_suite_is_not_a_pass(self):
        def fail():
            raise AssertionError("deliberate failing fixture")

        suite = unittest.TestSuite([unittest.FunctionTestCase(fail)])
        with patch.object(verifier.unittest.TestLoader, "discover", return_value=suite):
            self.assertFalse(verifier.run_tooling_tests(self.root, io.StringIO()))

    def test_skipped_required_tooling_suite_is_not_a_pass(self):
        @unittest.skip("deliberate skipped fixture")
        def skipped():
            pass

        suite = unittest.TestSuite([unittest.FunctionTestCase(skipped)])
        with patch.object(verifier.unittest.TestLoader, "discover", return_value=suite):
            self.assertFalse(verifier.run_tooling_tests(self.root, io.StringIO()))


if __name__ == "__main__":
    unittest.main()
