"""Release version checks must follow the current ST build, not an old constant."""
import importlib.util
from pathlib import Path
import unittest
import tempfile

spec = importlib.util.spec_from_file_location(
    "st_verify_artifact", Path(__file__).with_name("verify-artifact.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VersionFieldsTest(unittest.TestCase):
    def test_build_code_is_read_from_current_variant_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.gradle"
            for code in (44, 45, 60):
                path.write_text(f'android {{\n defaultConfig {{\n  versionCode {code}\n }}\n}}')
                self.assertEqual(module.build_version_code(path), code)

    def test_missing_or_ambiguous_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.gradle"
            for invalid in ("", "versionCode 0", "versionCode 44\nversionCode 45", "versionCode -1"):
                path.write_text(invalid)
                with self.assertRaises(ValueError):
                    module.build_version_code(path)

    def test_current_build_numbers_are_accepted(self):
        for version, code in [("4.0.3", 43), ("4.0.4", 44), ("4.0.5", 45), ("4.1.0", 60)]:
            badging = f"package: name='com.nexuscast.player' versionCode='{code}' versionName='{version}'"
            module.verify_version_fields(badging, version, code)

    def test_old_build_code_is_rejected_even_when_version_name_matches(self):
        with self.assertRaises(ValueError):
            module.verify_version_fields("versionCode='43' versionName='4.0.5'", "4.0.5", 45)

    def test_wrong_version_name_is_rejected(self):
        with self.assertRaises(ValueError):
            module.verify_version_fields("versionCode='45' versionName='4.0.3'", "4.0.5", 45)

    def test_similar_code_is_not_accepted(self):
        with self.assertRaises(ValueError):
            module.verify_version_fields("versionCode='450' versionName='4.0.5'", "4.0.5", 45)


if __name__ == "__main__":
    unittest.main()
