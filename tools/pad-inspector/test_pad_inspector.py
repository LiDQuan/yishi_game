import importlib.util
import stat
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("pad_inspector.py")
SPEC = importlib.util.spec_from_file_location("pad_inspector", MODULE_PATH)
assert SPEC and SPEC.loader
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class PadInspectorTest(unittest.TestCase):
    def test_requires_exactly_one_ready_device(self):
        INSPECTOR.require_one_ready_device("List of devices attached\nprivate\tdevice\n")
        for output in (
            "List of devices attached\n",
            "List of devices attached\na\tdevice\nb\tdevice\n",
            "List of devices attached\na\tunauthorized\n",
        ):
            with self.assertRaises(INSPECTOR.InspectorError):
                INSPECTOR.require_one_ready_device(output)

    def test_parses_foreground_without_assuming_package(self):
        dump = "mCurrentFocus=Window{1 u0 example.confirmed.game/example.Activity}"
        self.assertEqual("example.confirmed.game", INSPECTOR.parse_foreground(dump))
        self.assertIsNone(INSPECTOR.parse_foreground("no focused application"))

    def test_parses_valid_target_window_bounds(self):
        dump = """Window #0 Window{abc u0 example.confirmed.game/example.Activity}:
  mBounds=[20,40][1620,940]
"""
        self.assertEqual((20, 40, 1620, 940), INSPECTOR.parse_window_bounds(dump, "example.confirmed.game"))
        self.assertIsNone(INSPECTOR.parse_window_bounds(dump, "different.package"))

    def test_falls_back_to_confirmed_activity_task_bounds(self):
        dump = "Intent { cmp=example.confirmed.game/example.Activity bnds=[20,40][1620,940] }"
        self.assertEqual((20, 40, 1620, 940), INSPECTOR.parse_activity_bounds(dump, "example.confirmed.game"))

    def test_private_artifact_writers_enforce_owner_only_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, writer, value in (
                ("window.txt", INSPECTOR.write_private_text, "raw window"),
                ("activity.txt", INSPECTOR.write_private_text, "raw activity"),
                ("display.txt", INSPECTOR.write_private_text, "raw display"),
                ("window.xml", INSPECTOR.write_private_text, "raw ui"),
                ("report.json", INSPECTOR.write_private_text, "{}"),
                ("screen.png", INSPECTOR.write_private_bytes, b"raw image"),
            ):
                path = root / name
                writer(path, value)
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))


if __name__ == "__main__":
    unittest.main()
