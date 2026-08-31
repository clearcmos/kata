"""Tests for the workstation CLI's pure logic.

Anything that shells out to adb or talks to the device is exercised against the real phone
instead; what is worth pinning here is the argument handling and the file loading, because
those decide what gets pushed and a mistake there is silent.
"""

from __future__ import annotations

import importlib.machinery
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def _load_cli():
    """Imports cli/kata, which has no .py extension, as a module."""
    spec = importlib.util.spec_from_loader(
        "kata_cli", importlib.machinery.SourceFileLoader("kata_cli", str(REPO / "cli" / "kata"))
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


kata = _load_cli()


class LoadDirTest(unittest.TestCase):
    def test_reads_every_automation_in_the_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "b.json").write_text(json.dumps({"id": "b", "name": "B"}))
            (path / "a.json").write_text(json.dumps({"id": "a", "name": "A"}))
            loaded = kata.load_dir(path)
            # Sorted so a push is deterministic and its diff is readable.
            self.assertEqual(["a", "b"], [a["id"] for a in loaded])

    def test_id_defaults_to_the_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "night-mode.json").write_text(json.dumps({"name": "Night"}))
            self.assertEqual("night-mode", kata.load_dir(path)[0]["id"])

    def test_an_explicit_id_wins_over_the_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "whatever.json").write_text(json.dumps({"id": "real-id", "name": "X"}))
            self.assertEqual("real-id", kata.load_dir(path)[0]["id"])

    def test_invalid_json_names_the_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "broken.json").write_text("{ not json")
            with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
                kata.load_dir(path)

    def test_a_bare_list_is_rejected(self):
        # One automation per file keeps ids traceable to a filename and diffs readable.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "list.json").write_text("[]")
            with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
                kata.load_dir(path)

    def test_an_empty_directory_is_an_error_not_an_empty_push(self):
        # Silently pushing nothing would wipe the device's rule set.
        with (
            tempfile.TemporaryDirectory() as tmp,
            self.assertRaises(SystemExit),
            redirect_stdout(io.StringIO()),
        ):
            kata.load_dir(Path(tmp))

    def test_a_missing_directory_is_an_error(self):
        with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
            kata.load_dir(REPO / "does-not-exist")

    def test_non_json_files_are_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "a.json").write_text(json.dumps({"id": "a"}))
            (path / "README.md").write_text("not an automation")
            self.assertEqual(["a"], [a["id"] for a in kata.load_dir(path)])


class ArgumentParsingTest(unittest.TestCase):
    def parse(self, argv):
        saved = sys.argv
        try:
            sys.argv = ["kata", *argv]
            return kata.build_parser().parse_args(argv)
        finally:
            sys.argv = saved

    def test_simulate_collects_key_value_facts(self):
        args = self.parse(["simulate", "wifi_connected", "ssid=home", "level=19"])
        self.assertEqual("wifi_connected", args.type)
        self.assertEqual(["ssid=home", "level=19"], args.facts)

    def test_fire_defaults_to_a_real_run(self):
        self.assertFalse(self.parse(["fire", "x"]).dry)
        self.assertTrue(self.parse(["fire", "x", "--dry"]).dry)

    def test_push_defaults_to_keeping_phone_edited_parameters(self):
        self.assertFalse(self.parse(["push"]).reset_params)
        self.assertTrue(self.parse(["push", "--reset-params"]).reset_params)

    def test_runs_has_sensible_defaults(self):
        args = self.parse(["runs"])
        self.assertEqual(10, args.limit)
        self.assertIsNone(args.id)

    def test_enable_and_disable_share_a_handler_with_opposite_intent(self):
        self.assertTrue(self.parse(["enable", "x"]).enable)
        self.assertFalse(self.parse(["disable", "x"]).enable)

    def test_a_subcommand_is_required(self):
        with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
            self.parse([])


class FactParsingTest(unittest.TestCase):
    def test_a_fact_without_an_equals_sign_is_rejected(self):
        with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
            kata.parse_facts(["notakeyvalue"])

    def test_values_may_contain_equals_signs(self):
        self.assertEqual({"url": "https://x/?a=b"}, kata.parse_facts(["url=https://x/?a=b"]))

    def test_no_facts_is_an_empty_mapping(self):
        self.assertEqual({}, kata.parse_facts([]))


if __name__ == "__main__":
    unittest.main()
