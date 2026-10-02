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
PRODUCTS = ("hexana-cli", "hexana-cli-script", "hexana-wasm-tools")
TARGETS = ("macos-arm64", "linux-x64")


def write_archive(path, launcher, launcher_mode=0o755, omitted=(), empty=(), extra=()):
    with zipfile.ZipFile(path, "w") as archive:
        for name in ("LICENSE.txt", "README.md", "THIRD-PARTY-NOTICES.md", launcher) + tuple(extra):
            if name in omitted:
                continue
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | (launcher_mode if name == launcher else 0o644)) << 16
            archive.writestr(entry, b"" if name in empty else name.encode())


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

    def add_fixture_product(self):
        self.descriptor["products"].append({
            "id": "fixture-cli", "executable": "fixture-runner",
            "artifactKind": "fixture-cli-native", "launcher": "bin/fixture-runner",
        })
        self.descriptor["artifacts"].extend({
            "target": target["id"], "product": "fixture-cli",
            "archiveNameTemplate": f'fixture-runner-native-{target["id"]}-{{version}}.zip',
        } for target in self.descriptor["targets"])

    def update_identity(self, target_id, mutate):
        path = self.input / f"{target_id}-identity.json"
        identity = json.loads(path.read_text())
        mutate(identity)
        path.write_text(json.dumps(identity))

    def refresh_archive_digest(self, target_id, archive_path):
        def update(identity):
            entry = next(item for item in identity["artifacts"] if item["name"] == archive_path.name)
            entry["sha256"] = sha256(archive_path)
        self.update_identity(target_id, update)

    def test_current_release_inventory_is_exact(self):
        artifacts = self.build_input()
        self.assertEqual(
            [artifact["name"] for artifact in artifacts],
            [f"{product}-native-{target}-{VERSION}.zip" for target in TARGETS for product in PRODUCTS],
        )
        matrix = validate_input(self.descriptor_file, self.input, VERSION)
        self.assertEqual(len(matrix["artifacts"]), 6)
        self.assertEqual([item["id"] for item in matrix["products"]], list(PRODUCTS))
        self.assertEqual([item["id"] for item in matrix["targets"]], list(TARGETS))
        self.assertEqual({item["kind"] for item in matrix["artifacts"]},
                         {"standard-cli-native", "script-cli-native", "hexana-wasm-tools-native"})
        for item in matrix["artifacts"]:
            with self.subTest(product=item["product"], target=item["target"]):
                self.assertEqual(item["launcher"], f'bin/{item["product"]}')
                self.assertEqual(item["sha256"], sha256(self.input / item["name"]))
                self.assertEqual(item["producerBuildId"], "501")
                self.assertEqual(item["licenseSha256"], hashlib.sha256(b"LICENSE.txt").hexdigest())
                self.assertEqual(item["noticesSha256"], hashlib.sha256(b"THIRD-PARTY-NOTICES.md").hexdigest())
                self.assertEqual(item["launcherSha256"], hashlib.sha256(item["launcher"].encode()).hexdigest())

    def test_old_four_archive_release_is_rejected(self):
        artifacts = self.build_input()
        for item in artifacts:
            if item["product"] == "hexana-wasm-tools":
                (self.input / item["name"]).unlink()
        for target in TARGETS:
            self.update_identity(target, lambda identity: identity.update(
                artifacts=[item for item in identity["artifacts"] if not item["name"].startswith("hexana-wasm-tools-")]))
        with self.assertRaisesRegex(ValueError, "missing=.*hexana-wasm-tools-native-linux-x64"):
            validate_input(self.descriptor_file, self.input, VERSION)

    def test_additional_product_is_accepted_without_changing_validator(self):
        self.add_fixture_product()
        self.build_input()
        matrix = validate_input(self.descriptor_file, self.input, VERSION)
        self.assertEqual(len(matrix["artifacts"]), 8)
        self.assertEqual({item["kind"] for item in matrix["artifacts"]},
                         {"standard-cli-native", "script-cli-native", "hexana-wasm-tools-native", "fixture-cli-native"})
        fixture_artifacts = [item for item in matrix["artifacts"] if item["product"] == "fixture-cli"]
        self.assertEqual([item["launcher"] for item in fixture_artifacts], ["bin/fixture-runner"] * 2)

    def test_missing_extra_and_wrong_identity_archives_are_rejected(self):
        artifacts = self.build_input()
        for target in TARGETS:
            with self.subTest(target=target):
                wasm = next(item for item in artifacts if item["target"] == target and item["product"] == "hexana-wasm-tools")
                archive_path = self.input / wasm["name"]
                original = archive_path.read_bytes()
                archive_path.unlink()
                with self.assertRaisesRegex(ValueError, "missing="):
                    validate_input(self.descriptor_file, self.input, VERSION)
                archive_path.write_bytes(original)

                def wrong_identity(identity):
                    entry = next(item for item in identity["artifacts"] if item["name"] == wasm["name"])
                    entry["name"] = "wrong-product.zip"
                self.update_identity(target, wrong_identity)
                with self.assertRaisesRegex(ValueError, "Unknown archive"):
                    validate_input(self.descriptor_file, self.input, VERSION)
                self.build_input()

        extra = self.input / "unknown-product.zip"
        extra.write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "extra="):
            validate_input(self.descriptor_file, self.input, VERSION)
        extra.unlink()

    def test_wrong_target_duplicate_identity_and_digest_are_rejected(self):
        self.build_input()
        self.update_identity("linux-x64", lambda identity: identity.update(platform="macos-arm64"))
        with self.assertRaisesRegex(ValueError, "Wrong platform"):
            validate_input(self.descriptor_file, self.input, VERSION)
        self.build_input()
        self.update_identity("linux-x64", lambda identity: identity["artifacts"].append(copy.deepcopy(identity["artifacts"][-1])))
        with self.assertRaisesRegex(ValueError, "duplicates"):
            validate_input(self.descriptor_file, self.input, VERSION)
        self.build_input()
        self.update_identity("linux-x64", lambda identity: identity["artifacts"][-1].update(sha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "does not match identity"):
            validate_input(self.descriptor_file, self.input, VERSION)

    def test_wrong_launcher_layout_and_executable_bit_are_rejected(self):
        artifacts = self.build_input()
        for target in TARGETS:
            archive = next(item for item in artifacts if item["target"] == target and item["product"] == "hexana-wasm-tools")
            archive_path = self.input / archive["name"]
            write_archive(archive_path, "bin/hexana-cli")
            self.refresh_archive_digest(target, archive_path)
            with self.assertRaisesRegex(ValueError, "wrong layout"):
                validate_input(self.descriptor_file, self.input, VERSION)
            for launcher_mode in (0o644, 0o654, 0o645):
                with self.subTest(target=target, launcher_mode=oct(launcher_mode)):
                    write_archive(archive_path, archive["launcher"], launcher_mode=launcher_mode)
                    self.refresh_archive_digest(target, archive_path)
                    with self.assertRaisesRegex(ValueError, "not executable"):
                        validate_input(self.descriptor_file, self.input, VERSION)
            self.build_input()

    def test_wasm_identity_cannot_omit_or_substitute_a_target_archive(self):
        artifacts = self.build_input()
        for target in TARGETS:
            other = next(item["name"] for item in artifacts if item["product"] == "hexana-wasm-tools" and item["target"] != target)
            self.update_identity(target, lambda identity: identity["artifacts"].pop())
            with self.assertRaisesRegex(ValueError, "missing archives"):
                validate_input(self.descriptor_file, self.input, VERSION)
            self.build_input()
            self.update_identity(target, lambda identity: identity["artifacts"][-1].update(name=other))
            with self.assertRaisesRegex(ValueError, "Unknown archive"):
                validate_input(self.descriptor_file, self.input, VERSION)
            self.build_input()

    def test_wasm_license_notices_and_safe_archive_layout_are_required(self):
        artifacts = self.build_input()
        for target in TARGETS:
            artifact = next(item for item in artifacts if item["target"] == target and item["product"] == "hexana-wasm-tools")
            archive_path = self.input / artifact["name"]
            for name in ("LICENSE.txt", "README.md", "THIRD-PARTY-NOTICES.md"):
                for mutation, symptom in (("omitted", "wrong layout"), ("empty", "contains empty")):
                    with self.subTest(target=target, entry=name, mutation=mutation):
                        write_archive(archive_path, artifact["launcher"], **{mutation: (name,)})
                        self.refresh_archive_digest(target, archive_path)
                        with self.assertRaisesRegex(ValueError, symptom):
                            validate_input(self.descriptor_file, self.input, VERSION)
            for extra in ("../escape", "bin/foreign-launcher", "lib/unexpected.jar", artifact["launcher"]):
                with self.subTest(target=target, extra=extra):
                    with patch("warnings.warn"):
                        write_archive(archive_path, artifact["launcher"], extra=(extra,))
                    self.refresh_archive_digest(target, archive_path)
                    with self.assertRaisesRegex(ValueError, "wrong layout|duplicates"):
                        validate_input(self.descriptor_file, self.input, VERSION)
            self.build_input()

    def test_wasm_archive_name_and_producer_revision_must_match(self):
        artifacts = self.build_input()
        wasm = next(item for item in artifacts if item["target"] == "linux-x64" and item["product"] == "hexana-wasm-tools")
        (self.input / wasm["name"]).rename(self.input / "hexana-wasm-tools-native-linux-x64-9.9.9.zip")
        with self.assertRaisesRegex(ValueError, "missing=.*extra="):
            validate_input(self.descriptor_file, self.input, VERSION)
        (self.input / "hexana-wasm-tools-native-linux-x64-9.9.9.zip").rename(self.input / wasm["name"])
        self.update_identity("linux-x64", lambda identity: identity.update(sourceRevision="b" * 40))
        with self.assertRaisesRegex(ValueError, "different source revisions"):
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
