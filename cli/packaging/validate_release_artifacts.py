#!/usr/bin/env python3
"""Validate a CLI native release input against the generated product descriptor."""

import hashlib
import json
import re
import stat
import sys
import zipfile
from pathlib import Path


NAME = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
REVISION = re.compile(r"[0-9a-f]{40}\Z")
SEGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+{}-]*\Z")


def fail(message):
    raise ValueError(message)


def object_with_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        fail(f"{label} must contain exactly {sorted(keys)}")
    return value


def nonempty_list(value, label):
    if not isinstance(value, list) or not value:
        fail(f"{label} must be a non-empty array")
    return value


def named(value, label):
    if not isinstance(value, str) or not NAME.fullmatch(value):
        fail(f"{label} must be strict kebab-case: {value!r}")
    return value


def safe_file_name(value, label):
    if not isinstance(value, str) or not SEGMENT.fullmatch(value) or ".." in value:
        fail(f"{label} must be a simple file name: {value!r}")
    return value


def unique(values, label):
    if len(values) != len(set(values)):
        fail(f"{label} contains duplicates")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def read_json(path):
    def no_duplicate_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail(f"Duplicate JSON key in {path}: {key}")
            result[key] = value
        return result

    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"Invalid JSON {path}: {error}")


def load_descriptor(path, version):
    descriptor = object_with_keys(
        read_json(path), {"schemaVersion", "kind", "targets", "products", "artifacts"}, "CLI release descriptor"
    )
    if type(descriptor["schemaVersion"]) is not int or descriptor["schemaVersion"] != 1:
        fail("Unsupported CLI release descriptor schemaVersion")
    if descriptor["kind"] != "hexana-cli-native-release-artifacts":
        fail("Unexpected CLI release descriptor kind")

    targets = {}
    for target in nonempty_list(descriptor["targets"], "Descriptor targets"):
        target = object_with_keys(target, {"id", "platform", "architecture", "displayName", "identityFile"}, "Target")
        target_id = named(target["id"], "Target id")
        named(target["platform"], "Target platform")
        named(target["architecture"], "Target architecture")
        if not isinstance(target["displayName"], str) or not target["displayName"] or any(c in target["displayName"] for c in "|\r\n"):
            fail(f"Target {target_id} has an invalid display name")
        if target_id != f'{target["platform"]}-{target["architecture"]}':
            fail(f"Target {target_id} disagrees with platform/architecture")
        if target["identityFile"] != f"{target_id}-identity.json":
            fail(f"Target {target_id} has an unexpected identity file")
        if target_id in targets:
            fail(f"Duplicate target: {target_id}")
        targets[target_id] = target

    products = {}
    for product in nonempty_list(descriptor["products"], "Descriptor products"):
        product = object_with_keys(product, {"id", "executable", "artifactKind", "launcher"}, "Product")
        product_id = named(product["id"], "Product id")
        named(product["executable"], "Product executable")
        named(product["artifactKind"], "Product artifact kind")
        if product["launcher"] != f'bin/{product["executable"]}':
            fail(f"Product {product_id} has an unexpected launcher")
        if product_id in products:
            fail(f"Duplicate product: {product_id}")
        products[product_id] = product
    unique([p["executable"] for p in products.values()], "Product executables")
    unique([p["artifactKind"] for p in products.values()], "Product artifact kinds")

    artifacts = []
    pairs = set()
    names = []
    for artifact in nonempty_list(descriptor["artifacts"], "Descriptor artifacts"):
        artifact = object_with_keys(artifact, {"target", "product", "archiveNameTemplate"}, "Artifact")
        target_id = named(artifact["target"], "Artifact target")
        product_id = named(artifact["product"], "Artifact product")
        if target_id not in targets or product_id not in products:
            fail(f"Artifact references unknown product or target: {product_id}/{target_id}")
        pair = (target_id, product_id)
        if pair in pairs:
            fail(f"Duplicate artifact target/product: {target_id}/{product_id}")
        pairs.add(pair)
        template = safe_file_name(artifact["archiveNameTemplate"], "Archive name template")
        if template.count("{version}") != 1 or any(c in template.replace("{version}", "") for c in "{}"):
            fail(f"Archive name template must contain one {{version}}: {template}")
        name = safe_file_name(template.replace("{version}", version), "Archive name")
        if not name.endswith(".zip"):
            fail(f"Archive name must end in .zip: {name}")
        names.append(name)
        artifacts.append({
            "target": target_id, "product": product_id,
            "kind": products[product_id]["artifactKind"], "name": name,
            "launcher": products[product_id]["launcher"],
            "identity": targets[target_id]["identityFile"],
        })
    expected_pairs = {(target, product) for target in targets for product in products}
    if pairs != expected_pairs:
        fail(f"Descriptor artifact matrix is incomplete: {sorted(expected_pairs - pairs)}")
    unique(names, "Archive names")
    return targets, products, artifacts


def validate_archive(path, launcher):
    expected = {"LICENSE.txt", "README.md", "THIRD-PARTY-NOTICES.md", launcher}
    try:
        with zipfile.ZipFile(path) as archive:
            hashes = {}
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            unique(names, f"Archive {path.name} entries")
            files = {entry.filename for entry in entries if not entry.is_dir()}
            directories = {entry.filename for entry in entries if entry.is_dir()}
            if files != expected or not directories.issubset({"bin/"}):
                fail(f"Archive {path.name} has wrong layout: files={sorted(files)}, directories={sorted(directories)}")
            for entry in entries:
                mode = entry.external_attr >> 16
                kind = stat.S_IFMT(mode)
                if kind not in (0, stat.S_IFDIR if entry.is_dir() else stat.S_IFREG):
                    fail(f"Archive {path.name} contains a non-regular entry: {entry.filename}")
                if not entry.is_dir():
                    entry_hash = hashlib.sha256()
                    entry_size = 0
                    with archive.open(entry) as source:
                        for block in iter(lambda: source.read(1024 * 1024), b""):
                            entry_hash.update(block)
                            entry_size += len(block)
                    if entry_size == 0:
                        fail(f"Archive {path.name} contains empty {entry.filename}")
                    hashes[entry.filename] = entry_hash.hexdigest()
            launcher_mode = archive.getinfo(launcher).external_attr >> 16
            if not launcher_mode & stat.S_IXUSR:
                fail(f"Archive {path.name} launcher is not executable: {launcher}")
            return {
                "launcherSha256": hashes[launcher],
                "licenseSha256": hashes["LICENSE.txt"],
                "noticesSha256": hashes["THIRD-PARTY-NOTICES.md"],
            }
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        fail(f"Invalid archive {path.name}: {error}")


def validate_input(descriptor_path, input_dir, version):
    targets, products, artifacts = load_descriptor(descriptor_path, version)
    expected = {artifact["name"] for artifact in artifacts} | {target["identityFile"] for target in targets.values()}
    if not input_dir.is_dir() or input_dir.is_symlink():
        fail(f"CLI artifact input directory does not exist: {input_dir}")
    actual = {entry.name for entry in input_dir.iterdir()}
    if actual != expected:
        fail(f"CLI input files differ from descriptor: missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")
    if any(not entry.is_file() or entry.is_symlink() for entry in input_dir.iterdir()):
        fail("CLI input must contain only regular files")

    revisions = set()
    for target_id, target in targets.items():
        identity = read_json(input_dir / target["identityFile"])
        object_with_keys(identity, {"sourceRevision", "teamCityBuildId", "platform", "version", "artifacts"}, "Identity")
        if not isinstance(identity["sourceRevision"], str) or not REVISION.fullmatch(identity["sourceRevision"]):
            fail(f"Invalid source revision in {target['identityFile']}")
        if not isinstance(identity["teamCityBuildId"], str) or not identity["teamCityBuildId"]:
            fail(f"Invalid producer build ID in {target['identityFile']}")
        if identity["platform"] != target_id or identity["version"] != version:
            fail(f"Wrong platform or version in {target['identityFile']}")
        revisions.add(identity["sourceRevision"])
        entries = nonempty_list(identity["artifacts"], "Identity artifacts")
        expected_names = {artifact["name"] for artifact in artifacts if artifact["target"] == target_id}
        observed_names = []
        archive_digests = {}
        for entry in entries:
            object_with_keys(entry, {"name", "sha256"}, "Identity artifact")
            safe_file_name(entry["name"], "Identity archive name")
            observed_names.append(entry["name"])
            if not isinstance(entry["sha256"], str) or not SHA256.fullmatch(entry["sha256"]):
                fail(f"Invalid archive digest in {target['identityFile']}")
            if entry["name"] not in expected_names:
                fail(f"Unknown archive in {target['identityFile']}: {entry['name']}")
            if digest(input_dir / entry["name"]) != entry["sha256"]:
                fail(f"Archive does not match identity: {entry['name']}")
            archive_digests[entry["name"]] = entry["sha256"]
        unique(observed_names, f"Identity {target_id} artifacts")
        if set(observed_names) != expected_names:
            fail(f"Identity {target_id} is missing archives: {sorted(expected_names - set(observed_names))}")
        for artifact in artifacts:
            if artifact["target"] == target_id:
                artifact.update(validate_archive(input_dir / artifact["name"], artifact["launcher"]))
                artifact["producerBuildId"] = identity["teamCityBuildId"]
                artifact["sha256"] = archive_digests[artifact["name"]]
    if len(revisions) != 1:
        fail("Platform identities have different source revisions")
    return {"sourceRevision": revisions.pop(), "targets": list(targets.values()), "products": list(products.values()), "artifacts": artifacts}


def main():
    if len(sys.argv) != 5:
        fail("Usage: validate_release_artifacts.py DESCRIPTOR INPUT_DIR VERSION OUTPUT_JSON")
    matrix = validate_input(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
    Path(sys.argv[4]).write_text(json.dumps(matrix, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(f"[cli-downloads-release] ERROR: {error}", file=sys.stderr)
        sys.exit(1)
