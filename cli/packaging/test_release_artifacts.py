import copy
import hashlib
import json
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from cli.packaging.validate_release_artifacts import load_descriptor, validate_archive, validate_input


DESCRIPTOR = Path(__file__).parents[1] / "metadata/src/main/gen/cli-release-artifacts.json"
VERSION = "1.2.3"


def write_archive(path, launcher, launcher_mode=0o755):
    with zipfile.ZipFile(path, "w") as archive:
        for name in ("LICENSE.txt", "README.md", "THIRD-PARTY-NOTICES.md", launcher):
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | (launcher_mode if name == launcher else 0o644)) << 16
            archive.writestr(entry, name.encode())


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ReleaseArtifactDescriptorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.input = self.root / "input"
        self.input.mkdir()
        self.descriptor = json.loads(DESCRIPTOR.read_text())
        self.descriptor_file = self.root / "descriptor.json"

    def build_input(self):
        self.descriptor_file.write_text(json.dumps(self.descriptor))
        targets, _, artifacts = load_descriptor(self.descriptor_file, VERSION)
        for artifact in artifacts:
            write_archive(self.input / artifact["name"], artifact["launcher"])
        for target in targets.values():
            entries = [
                {"name": artifact["name"], "sha256": sha256(self.input / artifact["name"])}
                for artifact in artifacts if artifact["target"] == target["id"]
            ]
            (self.input / target["identityFile"]).write_text(json.dumps({
                "sourceRevision": "a" * 40, "teamCityBuildId": "501", "platform": target["id"],
                "version": VERSION, "artifacts": entries,
            }))
        return artifacts

    def add_third_product(self):
        self.descriptor["products"].append({
            "id": "fixture-cli", "executable": "fixture-cli",
            "artifactKind": "fixture-cli-native", "launcher": "bin/fixture-cli",
        })
        self.descriptor["artifacts"].extend({
            "target": target["id"], "product": "fixture-cli",
            "archiveNameTemplate": f'fixture-cli-native-{target["id"]}-{{version}}.zip',
        } for target in self.descriptor["targets"])

    def update_identity(self, target_id, mutate):
        path = self.input / f"{target_id}-identity.json"
        identity = json.loads(path.read_text())
        mutate(identity)
        path.write_text(json.dumps(identity))

    def test_current_release_inventory_is_exact(self):
        artifacts = self.build_input()
        self.assertEqual(
            [artifact["name"] for artifact in artifacts],
            [f"{product}-native-{target}-{VERSION}.zip" for target in ("macos-arm64", "linux-x64")
             for product in ("hexana-cli", "hexana-cli-script")],
        )
        self.assertEqual(len(validate_input(self.descriptor_file, self.input, VERSION)["artifacts"]), 4)

    def test_third_product_is_accepted_without_changing_validator(self):
        self.add_third_product()
        self.build_input()
        matrix = validate_input(self.descriptor_file, self.input, VERSION)
        self.assertEqual(len(matrix["artifacts"]), 6)
        self.assertEqual({item["kind"] for item in matrix["artifacts"]},
                         {"standard-cli-native", "script-cli-native", "fixture-cli-native"})

    def test_missing_extra_and_wrong_identity_archives_are_rejected(self):
        self.add_third_product()
        artifacts = self.build_input()
        third = next(item for item in artifacts if item["target"] == "linux-x64" and item["product"] == "fixture-cli")
        archive_path = self.input / third["name"]
        original = archive_path.read_bytes()
        archive_path.unlink()
        with self.assertRaisesRegex(ValueError, "missing="):
            validate_input(self.descriptor_file, self.input, VERSION)
        archive_path.write_bytes(original)

        extra = self.input / "unknown-product.zip"
        extra.write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "extra="):
            validate_input(self.descriptor_file, self.input, VERSION)
        extra.unlink()

        self.update_identity("linux-x64", lambda identity: identity["artifacts"][-1].update(name="wrong-product.zip"))
        with self.assertRaisesRegex(ValueError, "Unknown archive"):
            validate_input(self.descriptor_file, self.input, VERSION)

    def test_wrong_target_duplicate_identity_and_digest_are_rejected(self):
        self.build_input()
        self.update_identity("linux-x64", lambda identity: identity.update(platform="macos-arm64"))
        with self.assertRaisesRegex(ValueError, "Wrong platform"):
            validate_input(self.descriptor_file, self.input, VERSION)
        self.build_input()
        self.update_identity("linux-x64", lambda identity: identity["artifacts"].append(copy.deepcopy(identity["artifacts"][0])))
        with self.assertRaisesRegex(ValueError, "duplicates"):
            validate_input(self.descriptor_file, self.input, VERSION)
        self.build_input()
        self.update_identity("linux-x64", lambda identity: identity["artifacts"][0].update(sha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "does not match identity"):
            validate_input(self.descriptor_file, self.input, VERSION)

    def test_wrong_launcher_layout_and_executable_bit_are_rejected(self):
        artifacts = self.build_input()
        archive = next(item for item in artifacts if item["target"] == "linux-x64")
        archive_path = self.input / archive["name"]
        write_archive(archive_path, "bin/wrong-launcher")
        self.update_identity("linux-x64", lambda identity: identity["artifacts"][0].update(sha256=sha256(archive_path)))
        with self.assertRaisesRegex(ValueError, "wrong layout"):
            validate_input(self.descriptor_file, self.input, VERSION)
        for launcher_mode in (0o644, 0o654, 0o645):
            with self.subTest(launcher_mode=oct(launcher_mode)):
                write_archive(archive_path, archive["launcher"], launcher_mode=launcher_mode)
                self.update_identity("linux-x64", lambda identity: identity["artifacts"][0].update(sha256=sha256(archive_path)))
                with self.assertRaisesRegex(ValueError, "not executable"):
                    validate_input(self.descriptor_file, self.input, VERSION)

    def test_archive_entries_are_hashed_without_bulk_zip_reads(self):
        artifact = self.build_input()[0]
        with patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("unbounded ZIP read")):
            hashes = validate_archive(self.input / artifact["name"], artifact["launcher"])
        self.assertEqual(len(hashes["launcherSha256"]), 64)

    def test_duplicate_and_incomplete_descriptor_matrix_is_rejected(self):
        original = copy.deepcopy(self.descriptor)
        for section, duplicate in (("products", original["products"][0]),
                                   ("targets", original["targets"][0]),
                                   ("artifacts", original["artifacts"][0])):
            with self.subTest(section=section):
                self.descriptor = copy.deepcopy(original)
                self.descriptor[section].append(duplicate)
                self.descriptor_file.write_text(json.dumps(self.descriptor))
                with self.assertRaisesRegex(ValueError, "Duplicate"):
                    load_descriptor(self.descriptor_file, VERSION)
        self.descriptor = copy.deepcopy(original)
        self.descriptor["artifacts"].pop()
        self.descriptor_file.write_text(json.dumps(self.descriptor))
        with self.assertRaisesRegex(ValueError, "incomplete"):
            load_descriptor(self.descriptor_file, VERSION)

    def test_unknown_product_and_target_references_are_rejected(self):
        for field, value in (("product", "unknown-cli"), ("target", "unknown-x64")):
            with self.subTest(field=field):
                altered = copy.deepcopy(self.descriptor)
                altered["artifacts"][0][field] = value
                self.descriptor_file.write_text(json.dumps(altered))
                with self.assertRaisesRegex(ValueError, "unknown product or target"):
                    load_descriptor(self.descriptor_file, VERSION)


if __name__ == "__main__":
    unittest.main()
