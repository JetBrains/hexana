import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_whats_new as gen


CHANGELOG = """# Changelog

## Unreleased

### Added

## 0.12 - 2026-06-24

### Added
- Compare WASM With... opens **Size Impact** and `Entities` tabs for semantic matching.
- Kotlin/Wasm modules get badges that navigate to Kotlin source and report *moved* functions.

### Fixed
- The virtualized WAT view no longer throws for duplicate function names.

## 0.11 - 2026-06-11

### Added
- Older entry.
"""


ASSETS = {
    "schema": 1,
    "clusters": {
        "kotlin-wasm": {
            "release": "0.12",
            "presentation": "text",
            "eyebrow": "Kotlin/Wasm",
            "title": "Navigate to Kotlin source",
            "summary": "Badges connect functions to Kotlin declarations.",
            "recipe": "kotlin-wasm-source-navigation",
            "keywords": ["Kotlin/Wasm", "Kotlin source"],
            "image_candidates": ["doc/images/idea/kotlin.png"],
        },
        "wasm-diff": {
            "release": "0.12",
            "eyebrow": "WASM diff",
            "title": "Compare modules structurally",
            "summary": "Semantic matching reduces false churn.",
            "recipe": "wasm-diff-size-impact",
            "keywords": ["Compare WASM", "Size Impact", "Entities"],
            "image_candidates": ["doc/images/idea/diff.png"],
        },
    },
    "capture_policy": {
        "fallback_source": "none",
    },
}


DESIGN = """---
css_tokens:
  accent: "#005cc5"
---

# Test Design
"""


class GenerateWhatsNewTest(unittest.TestCase):
    def test_parse_changelog_by_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            changelog = Path(tmp) / "CHANGELOG.md"
            changelog.write_text(CHANGELOG, encoding="utf-8")

            releases = gen.parse_changelog(changelog)

        self.assertEqual("0.12", gen.latest_release_version(releases))
        self.assertEqual("2026-06-24", releases["0.12"].date)
        self.assertEqual(3, len(releases["0.12"].entries))
        self.assertEqual("Fixed", releases["0.12"].entries[-1].section)

    def test_build_clusters_preserves_configured_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            changelog = root / "CHANGELOG.md"
            changelog.write_text(CHANGELOG, encoding="utf-8")
            assets_path = root / "assets.yml"
            assets_path.write_text(json.dumps(ASSETS), encoding="utf-8")
            image_dir = root / "doc/images/idea"
            image_dir.mkdir(parents=True)
            (image_dir / "diff.png").write_bytes(b"diff")

            releases = gen.parse_changelog(changelog)
            clusters = gen.build_clusters(releases["0.12"], gen.load_assets(assets_path), assets_path)

        self.assertEqual(["kotlin-wasm", "wasm-diff"], [cluster.cluster_id for cluster in clusters])
        self.assertEqual("kotlin-wasm-source-navigation", clusters[0].recipe)
        self.assertEqual("wasm-diff-size-impact", clusters[1].recipe)

    def test_build_clusters_rejects_missing_required_screenshot_when_fallback_is_disabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            changelog = root / "CHANGELOG.md"
            changelog.write_text(CHANGELOG, encoding="utf-8")
            assets_data = json.loads(json.dumps(ASSETS))
            assets_data["clusters"]["wasm-diff"]["image_candidates"] = ["missing/diff.png"]
            assets_path = root / "assets.yml"
            assets_path.write_text(json.dumps(assets_data), encoding="utf-8")

            releases = gen.parse_changelog(changelog)

            with self.assertRaisesRegex(ValueError, "Missing screenshot assets for wasm-diff"):
                gen.build_clusters(releases["0.12"], gen.load_assets(assets_path), assets_path)

    def test_build_clusters_allows_text_cluster_without_screenshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            changelog = root / "CHANGELOG.md"
            changelog.write_text(CHANGELOG, encoding="utf-8")
            assets_data = json.loads(json.dumps(ASSETS))
            assets_data["clusters"]["kotlin-wasm"]["image_candidates"] = ["missing/kotlin.png"]
            assets_path = root / "assets.yml"
            assets_path.write_text(json.dumps(assets_data), encoding="utf-8")
            image_dir = root / "doc/images/idea"
            image_dir.mkdir(parents=True)
            (image_dir / "diff.png").write_bytes(b"diff")

            releases = gen.parse_changelog(changelog)
            clusters = gen.build_clusters(releases["0.12"], gen.load_assets(assets_path), assets_path)

            kotlin_cluster = next(cluster for cluster in clusters if cluster.cluster_id == "kotlin-wasm")
            self.assertIsNone(kotlin_cluster.asset)

    def test_dry_run_does_not_write_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            changelog, assets, design, output = self._write_inputs(root)
            args = gen.parse_args(
                [
                    "--dry-run",
                    "--version",
                    "0.12",
                    "--changelog",
                    str(changelog),
                    "--assets",
                    str(assets),
                    "--design",
                    str(design),
                    "--output",
                    str(output),
                ]
            )

            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                gen.generate(args)

            self.assertIn("version: 0.12", stdout.getvalue())
            self.assertFalse(output.exists())

    def test_source_version_renders_unreleased_as_target_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            changelog = root / "CHANGELOG.md"
            changelog.write_text(
                """# Changelog

## Unreleased

### Added
- Parquet files open with a Data tab.
""",
                encoding="utf-8",
            )
            assets = root / "assets.yml"
            assets.write_text(
                json.dumps(
                    {
                        "schema": 1,
                        "capture_policy": {"fallback_source": "none"},
                        "clusters": {
                            "columnar": {
                                "release": "0.13",
                                "presentation": "text",
                                "eyebrow": "Data",
                                "title": "Inspect columnar files",
                                "summary": "Parquet data is visible in Hexana.",
                                "recipe": "parquet-data",
                                "keywords": ["Parquet", "Data tab"],
                                "image_candidates": [],
                            }
                        },
                    }
                ),
                encoding="utf-8",
            )
            design = root / "DESIGN.md"
            design.write_text(DESIGN, encoding="utf-8")
            output = root / "out"
            args = gen.parse_args(
                [
                    "--version",
                    "0.13",
                    "--source-version",
                    "Unreleased",
                    "--changelog",
                    str(changelog),
                    "--assets",
                    str(assets),
                    "--design",
                    str(design),
                    "--output",
                    str(output),
                ]
            )

            with contextlib.redirect_stdout(io.StringIO()):
                gen.generate(args)

            html = (output / "index.html").read_text(encoding="utf-8")
            source = json.loads((output / "source.json").read_text(encoding="utf-8"))
            self.assertIn("<h1>What's New in Hexana 0.13</h1>", html)
            self.assertEqual("0.13", source["version"])
            self.assertEqual("columnar", source["clusters"][0]["id"])

    def test_render_inline_markdown_escapes_input_and_allows_static_inline_tags(self):
        rendered = gen.render_inline_markdown("Use **Size Impact**, `Entities`, and *moved* <script>")

        self.assertEqual(
            "Use <strong>Size Impact</strong>, <code>Entities</code>, and <em>moved</em> &lt;script&gt;",
            rendered,
        )

    def test_note_excerpt_uses_complete_sentence_preview(self):
        entry = gen.ChangelogEntry(
            "Added",
            "First complete sentence. " + "Second sentence with many words " * 20,
        )

        excerpt = gen.note_excerpt(entry, limit=80)

        self.assertEqual("First complete sentence.", excerpt.preview)
        self.assertTrue(excerpt.has_more)
        self.assertIn("Second sentence", excerpt.full)

    def test_note_excerpt_finishes_short_fragment_without_show_more(self):
        entry = gen.ChangelogEntry("Added", "ZIP64 files support")

        excerpt = gen.note_excerpt(entry, limit=80)

        self.assertEqual("ZIP64 files support.", excerpt.preview)
        self.assertFalse(excerpt.has_more)

    def test_note_continuation_is_bounded(self):
        entry = gen.ChangelogEntry(
            "Added",
            "First complete sentence. " +
            "Second sentence with focused detail. " +
            "Third sentence should not be included in the expandable copy.",
        )
        excerpt = gen.note_excerpt(entry, limit=40)

        continuation = gen.note_continuation(excerpt, limit=80)

        self.assertEqual("Second sentence with focused detail.", continuation)
        self.assertNotIn("Third sentence", continuation)

    def test_note_continuation_prefers_clause_boundary(self):
        excerpt = gen.NoteExcerpt(
            preview="Preview.",
            full=(
                "Preview. The archive opens to a Members list showing each object file's name, "
                "its detected format, and size; the archive's own bookkeeping entries are hidden."
            ),
            has_more=True,
        )

        continuation = gen.note_continuation(excerpt, limit=95)

        self.assertEqual("The archive opens to a Members list showing each object file's name, its detected format.", continuation)

    def test_render_expandable_notes_adds_single_show_more_control_for_cluster(self):
        cluster = gen.FeatureCluster(
            cluster_id="test-cluster",
            eyebrow="Test",
            title="Test cluster",
            summary="Summary",
            recipe="test-recipe",
            entries=(
                gen.ChangelogEntry(
                    "Added",
                    "First complete sentence. Second sentence with **details**. Third sentence should stay out.",
                ),
                gen.ChangelogEntry("Added", "Short note"),
            ),
            asset=None,
        )

        rendered = gen.render_expandable_notes(cluster, preview_limit=80)

        self.assertIn("""<input class="more-toggle" type="checkbox" id="test-cluster-more">""", rendered)
        self.assertEqual(1, rendered.count("Show more"))
        self.assertEqual(1, rendered.count("Show less"))
        self.assertEqual(1, rendered.count("""<ul class="note-list">"""))
        self.assertIn("""<span class="more-continuation">""", rendered)
        self.assertIn("<strong>details</strong>", rendered)
        self.assertNotIn("Third sentence", rendered)

    def test_write_complete_static_resource_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            changelog, assets, design, output = self._write_inputs(root)
            args = gen.parse_args(
                [
                    "--version",
                    "0.12",
                    "--changelog",
                    str(changelog),
                    "--assets",
                    str(assets),
                    "--design",
                    str(design),
                    "--output",
                    str(output),
                ]
            )

            (output / "screenshots").mkdir(parents=True)
            (output / "screenshots/stale.png").write_bytes(b"stale")
            with contextlib.redirect_stdout(io.StringIO()):
                gen.generate(args)

            self.assertTrue((output / "index.html").is_file())
            self.assertTrue((output / "style.css").is_file())
            self.assertTrue((output / "manifest.txt").is_file())
            self.assertTrue((output / "source.json").is_file())
            self.assertTrue((output / "screenshots/wasm-diff.png").is_file())
            self.assertFalse((output / "screenshots/stale.png").exists())
            html = (output / "index.html").read_text(encoding="utf-8")
            self.assertIn("<strong>Size Impact</strong>", html)
            self.assertIn("<code>Entities</code>", html)
            self.assertIn("<em>moved</em>", html)
            self.assertNotIn("**", html)
            self.assertNotIn("`", html)
            self.assertIn("source.json", (output / "manifest.txt").read_text(encoding="utf-8"))
            self.assertNotIn("stale.png", (output / "manifest.txt").read_text(encoding="utf-8"))
            source = json.loads((output / "source.json").read_text(encoding="utf-8"))
            self.assertEqual("0.12", source["version"])
            self.assertEqual("kotlin-wasm", source["clusters"][0]["id"])

    def _write_inputs(self, root: Path):
        changelog = root / "CHANGELOG.md"
        changelog.write_text(CHANGELOG, encoding="utf-8")
        assets = root / "assets.yml"
        assets.write_text(json.dumps(ASSETS), encoding="utf-8")
        design = root / "DESIGN.md"
        design.write_text(DESIGN, encoding="utf-8")
        image_dir = root / "doc/images/idea"
        image_dir.mkdir(parents=True)
        (image_dir / "diff.png").write_bytes(b"diff")
        (image_dir / "kotlin.png").write_bytes(b"kotlin")
        output = root / "out"
        return changelog, assets, design, output


if __name__ == "__main__":
    unittest.main()
