import importlib.util
import stat
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).with_name("vision_sampler.py")
SPEC = importlib.util.spec_from_file_location("vision_sampler", MODULE)
vision_sampler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vision_sampler)


class VisionSamplerTest(unittest.TestCase):
    def test_roi_validation_and_private_writer(self):
        self.assertEqual((0.1, 0.2, 0.8, 0.9), vision_sampler.parse_roi("0.1,0.2,0.8,0.9"))
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "private" / "value.json"
            vision_sampler.private_dir(path.parent)
            vision_sampler.write_private(path, "{}")
            self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))


if __name__ == "__main__":
    unittest.main()
