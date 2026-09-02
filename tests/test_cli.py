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


class BuildStateTest(unittest.TestCase):
    def test_captures_only_what_the_repo_cannot_rebuild(self):
        # Rule bodies are excluded on purpose: two sources of truth for the same rules would
        # eventually disagree, and the repo has to win.
        state = kata.build_state(
            [
                {
                    "id": "a",
                    "enabled": True,
                    "trigger": {"type": "manual"},
                    "actions": [{"type": "log", "message": "x"}],
                    "params": [{"key": "host", "value": "10.0.0.2"}],
                }
            ],
            {"counter": "7"},
        )
        self.assertEqual({"a": {"host": "10.0.0.2"}}, state["params"])
        self.assertEqual({"counter": "7"}, state["vars"])
        self.assertEqual({"a": True}, state["enabled"])
        self.assertNotIn("actions", json.dumps(state))

    def test_a_rule_without_parameters_is_omitted_from_params(self):
        state = kata.build_state([{"id": "plain", "enabled": True}], {})
        self.assertEqual({}, state["params"])
        self.assertEqual({"plain": True}, state["enabled"])

    def test_disabled_state_is_captured(self):
        state = kata.build_state([{"id": "off", "enabled": False}], {})
        self.assertEqual({"off": False}, state["enabled"])

    def test_enabled_defaults_to_true_when_absent(self):
        self.assertEqual({"x": True}, kata.build_state([{"id": "x"}], {})["enabled"])

    def test_the_serialized_form_is_stable_so_a_no_op_pull_makes_no_diff(self):
        rules = [{"id": "b", "params": [{"key": "z", "value": "1"}, {"key": "a", "value": "2"}]}]
        first = json.dumps(kata.build_state(rules, {}), indent=2, sort_keys=True)
        second = json.dumps(kata.build_state(list(rules), {}), indent=2, sort_keys=True)
        self.assertEqual(first, second)


class PullRestoreArgsTest(unittest.TestCase):
    def parse(self, argv):
        return kata.build_parser().parse_args(argv)

    def test_pull_defaults_into_the_private_arch_repo(self):
        self.assertTrue(str(self.parse(["pull"]).out).endswith("config/kata/device-state.json"))

    def test_pull_destination_is_overridable(self):
        self.assertEqual("/tmp/x.json", self.parse(["pull", "--out", "/tmp/x.json"]).out)

    def test_restore_reads_the_same_default(self):
        self.assertTrue(str(self.parse(["restore"]).file).endswith("config/kata/device-state.json"))
