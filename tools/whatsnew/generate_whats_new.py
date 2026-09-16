#!/usr/bin/env python3
"""Generate static Hexana What's New resources from the plugin changelog."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


RELEASE_HEADING = re.compile(r"^##\s+(?P<version>[^\s#]+)(?:\s+-\s+(?P<date>.+))?\s*$")
SECTION_HEADING = re.compile(r"^###\s+(?P<section>.+?)\s*$")
INLINE_MARKDOWN = re.compile(r"`([^`\n]+)`|\*\*([^*\n]+?)\*\*|(?<!\*)\*([^*\n]+?)\*(?!\*)")
REPO_ROOT = Path(__file__).resolve().parents[2]


DEFAULT_TOKENS = {
    "background": "#f7f8fa",
    "surface": "#ffffff",
    "surface_soft": "#eef2f7",
    "text": "#1f2328",
    "muted": "#5f6673",
    "accent": "#1f6feb",
    "accent_strong": "#1557b0",
    "border": "#d8dee8",
    "dark_background": "#1e1f22",
    "dark_surface": "#2b2d31",
    "dark_surface_soft": "#25272b",
    "dark_text": "#f2f3f5",
    "dark_muted": "#b6bcc7",
    "dark_accent": "#72a7ff",
    "dark_accent_strong": "#9fc2ff",
    "dark_border": "#44484f",
}


@dataclass(frozen=True)
class ChangelogEntry:
    section: str
    text: str


@dataclass(frozen=True)
class ReleaseNotes:
    version: str
    date: str | None
    entries: tuple[ChangelogEntry, ...]


@dataclass(frozen=True)
class SelectedAsset:
    source: Path
    target: str
    recipe: str


@dataclass(frozen=True)
class FeatureCluster:
    cluster_id: str
    eyebrow: str
    title: str
    summary: str
    recipe: str
    entries: tuple[ChangelogEntry, ...]
    asset: SelectedAsset | None


@dataclass(frozen=True)
class NoteExcerpt:
    preview: str
    full: str
    has_more: bool


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Print the planned output without writing files.")
    parser.add_argument("--version", help="Release version to generate. Defaults to the latest non-Unreleased changelog version.")
    parser.add_argument("--source-version", help="Changelog heading to read. Defaults to --version; use Unreleased to render a pending release as a concrete version.")
    parser.add_argument("--changelog", type=Path, default=REPO_ROOT / "idea-plugin/CHANGELOG.md")
    parser.add_argument("--output", type=Path, help="Output resource folder. Defaults to idea-plugin/src/main/resources/whatsnew/<version>.")
    parser.add_argument("--design", type=Path, default=REPO_ROOT / "docs/whatsnew/DESIGN.md")
    parser.add_argument("--assets", type=Path, default=REPO_ROOT / "docs/whatsnew/assets.yml")
    return parser.parse_args(argv)


def parse_changelog(path: Path) -> dict[str, ReleaseNotes]:
    releases: dict[str, ReleaseNotes] = {}
    current_version: str | None = None
    current_date: str | None = None
    current_section: str | None = None
    entries: list[ChangelogEntry] = []
    bullet_lines: list[str] = []

    def flush_bullet() -> None:
        nonlocal bullet_lines
        if current_version and current_section and bullet_lines:
            text = " ".join(line.strip() for line in bullet_lines if line.strip())
            if text:
                entries.append(ChangelogEntry(current_section, text))
        bullet_lines = []

    def flush_release() -> None:
        if current_version:
            releases[current_version] = ReleaseNotes(current_version, current_date, tuple(entries))

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.rstrip()
        release_match = RELEASE_HEADING.match(line)
        if release_match:
            flush_bullet()
            flush_release()
            current_version = release_match.group("version")
            current_date = release_match.group("date")
            current_section = None
            entries = []
            continue

        section_match = SECTION_HEADING.match(line)
        if section_match:
            flush_bullet()
            current_section = section_match.group("section")
            continue

        if line.startswith("- "):
            flush_bullet()
            bullet_lines = [line[2:]]
        elif bullet_lines and (line.startswith("  ") or line.strip()):
            bullet_lines.append(line)
        elif not line.strip():
            flush_bullet()

    flush_bullet()
    flush_release()
    return releases


def latest_release_version(releases: dict[str, ReleaseNotes]) -> str:
    for version in releases:
        if version.lower() != "unreleased":
            return version
    raise ValueError("No released version found in changelog.")


def load_assets(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path} must be JSON-compatible YAML: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("clusters"), dict):
        raise ValueError(f"{path} must contain a top-level clusters mapping.")
    return data


def parse_design_tokens(path: Path) -> dict[str, str]:
    if not path.exists():
        return dict(DEFAULT_TOKENS)

    tokens = dict(DEFAULT_TOKENS)
    in_front_matter = False
    in_css_tokens = False
    seen_front_matter = False

    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() == "---":
            if not seen_front_matter:
                in_front_matter = True
                seen_front_matter = True
                continue
            break
        if not in_front_matter:
            continue

        if line == "css_tokens:":
            in_css_tokens = True
            continue
        if in_css_tokens and line and not line.startswith("  "):
            in_css_tokens = False
        if in_css_tokens and line.startswith("  ") and ":" in line:
            key, value = line.strip().split(":", 1)
            tokens[key] = value.strip().strip("\"'")

    return tokens


def resolve_candidate(candidate: str, assets_path: Path) -> Path | None:
    path = Path(candidate)
    candidates = [path] if path.is_absolute() else [REPO_ROOT / path, assets_path.parent / path]
    for item in candidates:
        if item.exists() and item.is_file():
            return item
    return None


def select_asset(cluster_id: str, cluster_config: dict, assets_path: Path) -> SelectedAsset | None:
    recipe = str(cluster_config.get("recipe", cluster_id))
    for candidate in cluster_config.get("image_candidates", []):
        source = resolve_candidate(str(candidate), assets_path)
        if source:
            suffix = source.suffix.lower()
            target = f"screenshots/{cluster_id}{suffix}"
            return SelectedAsset(source=source, target=target, recipe=recipe)
    return None


def requires_screenshot(cluster_config: dict, assets: dict) -> bool:
    if str(cluster_config.get("presentation", "screenshot")) == "text":
        return False
    fallback_source = str(assets.get("capture_policy", {}).get("fallback_source", "none"))
    return fallback_source == "none"


def normalize_match_text(value: str) -> str:
    text = value.lower().replace("…", "...").replace("▸", " ")
    text = re.sub(r"[*`_]+", "", text)
    return re.sub(r"\s+", " ", text)


def match_score(entry: ChangelogEntry, keywords: Iterable[str]) -> int:
    text = normalize_match_text(entry.text)
    return sum(1 for keyword in keywords if normalize_match_text(keyword) in text)


def build_clusters(release: ReleaseNotes, assets: dict, assets_path: Path, max_clusters: int = 6) -> tuple[FeatureCluster, ...]:
    clusters: list[FeatureCluster] = []
    cluster_configs = assets["clusters"]
    eligible_configs = [
        (cluster_id, config)
        for cluster_id, config in cluster_configs.items()
        if str(config.get("release", release.version)) == release.version
    ]
    matched_entries: dict[str, list[ChangelogEntry]] = {cluster_id: [] for cluster_id, _ in eligible_configs}

    for entry in release.entries:
        best_cluster_id: str | None = None
        best_score = 0
        for cluster_id, config in eligible_configs:
            score = match_score(entry, [str(keyword) for keyword in config.get("keywords", [])])
            if score > best_score:
                best_cluster_id = cluster_id
                best_score = score
        if best_cluster_id:
            matched_entries[best_cluster_id].append(entry)

    for cluster_id, config in eligible_configs:
        matched = matched_entries[cluster_id]
        if not matched:
            continue

        clusters.append(
            FeatureCluster(
                cluster_id=cluster_id,
                eyebrow=str(config.get("eyebrow", cluster_id.replace("-", " ").title())),
                title=str(config.get("title", cluster_id.replace("-", " ").title())),
                summary=str(config.get("summary", matched[0].text)),
                recipe=str(config.get("recipe", cluster_id)),
                entries=tuple(matched),
                asset=select_asset(cluster_id, config, assets_path),
            )
        )

    missing_required_assets = [
        cluster.cluster_id
        for cluster in clusters
        if cluster.asset is None and requires_screenshot(cluster_configs[cluster.cluster_id], assets)
    ]
    if missing_required_assets:
        joined = ", ".join(missing_required_assets)
        raise ValueError(
            f"Missing screenshot assets for {joined}. Add UI-driver captures to image_candidates "
            "or mark the cluster with presentation='text'."
        )

    return tuple(clusters[:max_clusters])


def normalized_note_text(entry: ChangelogEntry) -> str:
    text = re.sub(r"\s+", " ", entry.text).strip()
    return text if text.endswith((".", "!", "?")) else f"{text}."


def note_excerpt(entry: ChangelogEntry, limit: int = 210) -> NoteExcerpt:
    text = normalized_note_text(entry)
    if len(text) <= limit:
        return NoteExcerpt(preview=text, full=text, has_more=False)
    first_sentence = re.split(r"(?<=[.!?])\s+", text, maxsplit=1)[0].strip()
    preview = first_sentence or text[: limit - 1].rsplit(" ", 1)[0].strip()
    if preview and not preview.endswith((".", "!", "?")):
        preview = f"{preview}."
    return NoteExcerpt(preview=preview, full=text, has_more=preview != text)


def note_continuation(excerpt: NoteExcerpt, limit: int = 180) -> str:
    tail = excerpt.full.removeprefix(excerpt.preview).strip()
    if not tail:
        return ""
    sentence = re.split(r"(?<=[.!?])\s+", tail, maxsplit=1)[0].strip()
    detail = sentence or tail
    if len(detail) > limit:
        detail = compact_clause(detail, limit)
    if detail and not detail.endswith((".", "!", "?")):
        detail = f"{detail}."
    return detail


def compact_clause(text: str, limit: int) -> str:
    candidates = []
    for separator in ("; ", ": ", ", because ", ", so ", ", and ", " — "):
        index = text.rfind(separator, 0, limit)
        if index >= 40:
            candidates.append(text[:index].strip())
    if candidates:
        return max(candidates, key=len)
    return text[: limit - 1].rsplit(" ", 1)[0].strip()


def render_inline_markdown(text: str) -> str:
    rendered: list[str] = []
    position = 0

    for match in INLINE_MARKDOWN.finditer(text):
        rendered.append(html.escape(text[position : match.start()]).replace("**", "").replace("`", ""))
        code, strong, emphasis = match.group(1), match.group(2), match.group(3)
        if code is not None:
            rendered.append(f"<code>{html.escape(code)}</code>")
        elif strong is not None:
            rendered.append(f"<strong>{html.escape(strong)}</strong>")
        elif emphasis is not None:
            rendered.append(f"<em>{html.escape(emphasis)}</em>")
        position = match.end()

    rendered.append(html.escape(text[position:]).replace("**", "").replace("`", ""))
    return "".join(rendered)


def render_expandable_notes(cluster: FeatureCluster, preview_limit: int, list_indent: str = "      ") -> str:
    excerpts = [note_excerpt(entry, preview_limit) for entry in cluster.entries]
    has_more = any(excerpt.has_more for excerpt in excerpts)
    items = []
    for excerpt in excerpts:
        tail = note_continuation(excerpt)
        continuation = f' <span class="more-continuation">{render_inline_markdown(tail)}</span>' if tail else ""
        items.append(f"{list_indent}    <li>{render_inline_markdown(excerpt.preview)}{continuation}</li>")
    list_html = "\n".join(items)
    if not has_more:
        return f"""{list_indent}  <ul class="note-list">
{list_html}
{list_indent}  </ul>"""

    control_id = f"{cluster.cluster_id}-more"
    return f"""{list_indent}  <input class="more-toggle" type="checkbox" id="{html.escape(control_id)}">
{list_indent}  <ul class="note-list">
{list_html}
{list_indent}  </ul>
{list_indent}  <label class="show-more-link" for="{html.escape(control_id)}">Show more</label>
{list_indent}  <label class="show-less-link" for="{html.escape(control_id)}">Show less</label>"""


def release_summary(release: ReleaseNotes, clusters: Sequence[FeatureCluster]) -> str:
    featured = [cluster for cluster in clusters if cluster.asset]
    focus_clusters = featured or list(clusters)
    if focus_clusters:
        titles = list(dict.fromkeys(cluster.eyebrow for cluster in focus_clusters))[:3]
        if len(titles) == 1:
            focus = titles[0]
        elif len(titles) == 2:
            focus = f"{titles[0]} and {titles[1]}"
        else:
            focus = f"{titles[0]}, {titles[1]}, and {titles[2]}"
        return f"Hexana {release.version} focuses on {focus} while refining binary inspection inside JetBrains IDEs."
    return f"Hexana {release.version} bundles the latest binary inspection improvements for JetBrains IDEs."


def render_html(release: ReleaseNotes, clusters: Sequence[FeatureCluster]) -> str:
    sections = []
    featured_clusters = [cluster for cluster in clusters if cluster.asset]
    improvement_clusters = [cluster for cluster in clusters if not cluster.asset]
    for index, cluster in enumerate(featured_clusters):
        asset_html = ""
        if cluster.asset:
            asset_html = (
                f'\n      <img src="{html.escape(cluster.asset.target)}" '
                f'alt="{html.escape(cluster.title)} screenshot">'
            )
        details = render_expandable_notes(cluster, preview_limit=210)
        reversed_class = " reversed" if index % 2 else ""
        sections.append(
            f"""  <section class="feature{reversed_class}">
    <div>
      <p class="eyebrow">{html.escape(cluster.eyebrow)}</p>
      <h2>{html.escape(cluster.title)}</h2>
      <p>{html.escape(cluster.summary)}</p>
{details}
    </div>{asset_html}
  </section>"""
        )

    improvements_html = ""
    if improvement_clusters:
        improvement_items = []
        for cluster in improvement_clusters:
            improvement_items.append(
                f"""      <li>
        <strong>{html.escape(cluster.title)}</strong>
        <span>{html.escape(cluster.summary)}</span>
      </li>"""
            )
        improvements_html = f"""
  <section class="notes">
    <h2>Also in {html.escape(release.version)}</h2>
    <ul class="improvements">
{chr(10).join(improvement_items)}
    </ul>
  </section>
"""

    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>What's New in Hexana {html.escape(release.version)}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
<main class="page">
  <section class="hero">
    <p class="eyebrow">Hexana update</p>
    <h1>What's New in Hexana {html.escape(release.version)}</h1>
    <p class="lead">{html.escape(release_summary(release, clusters))}</p>
    <div class="actions">
      <a class="button primary" href="https://plugins.jetbrains.com/plugin/29090-hexana--webassembly--binary-inspector">Open release notes</a>
      <a class="button" href="https://github.com/JetBrains/hexana">Open GitHub</a>
    </div>
  </section>

{chr(10).join(sections)}
{improvements_html}
</main>
</body>
</html>
"""


def render_css(tokens: dict[str, str]) -> str:
    token = {**DEFAULT_TOKENS, **tokens}
    return f""":root {{
  color-scheme: light dark;
  --background: {token["background"]};
  --surface: {token["surface"]};
  --surface-soft: {token["surface_soft"]};
  --text: {token["text"]};
  --muted: {token["muted"]};
  --accent: {token["accent"]};
  --accent-strong: {token["accent_strong"]};
  --border: {token["border"]};
  --shadow: 0 16px 42px rgba(31, 35, 40, 0.12);
}}

@media (prefers-color-scheme: dark) {{
  :root {{
    --background: {token["dark_background"]};
    --surface: {token["dark_surface"]};
    --surface-soft: {token["dark_surface_soft"]};
    --text: {token["dark_text"]};
    --muted: {token["dark_muted"]};
    --accent: {token["dark_accent"]};
    --accent-strong: {token["dark_accent_strong"]};
    --border: {token["dark_border"]};
    --shadow: 0 16px 42px rgba(0, 0, 0, 0.32);
  }}
}}

* {{
  box-sizing: border-box;
}}

body {{
  margin: 0;
  background: var(--background);
  color: var(--text);
  font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}}

.page {{
  width: min(1080px, calc(100% - 56px));
  margin: 0 auto;
  padding: 48px 0 64px;
}}

.hero,
.feature,
.notes {{
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--shadow);
}}

.hero {{
  padding: 48px;
}}

.eyebrow {{
  margin: 0 0 8px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}}

h1,
h2,
p {{
  margin-top: 0;
}}

h1 {{
  max-width: 760px;
  margin-bottom: 16px;
  font-size: 44px;
  line-height: 1.1;
}}

h2 {{
  margin-bottom: 12px;
  font-size: 25px;
  line-height: 1.25;
}}

.lead {{
  max-width: 760px;
  color: var(--muted);
  font-size: 18px;
}}

.actions {{
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 28px;
}}

.button {{
  display: inline-flex;
  align-items: center;
  min-height: 38px;
  padding: 0 16px;
  border: 1px solid var(--border);
  border-radius: 7px;
  color: var(--text);
  font-weight: 650;
  text-decoration: none;
}}

.button.primary {{
  border-color: var(--accent);
  background: var(--accent);
  color: #ffffff;
}}

.button:hover {{
  border-color: var(--accent-strong);
}}

.feature {{
  display: grid;
  grid-template-columns: minmax(260px, 0.85fr) minmax(360px, 1.15fr);
  gap: 32px;
  align-items: center;
  margin-top: 28px;
  padding: 32px;
}}

.feature.reversed {{
  grid-template-columns: minmax(360px, 1.15fr) minmax(260px, 0.85fr);
}}

.feature.reversed div {{
  order: 2;
}}

.feature.reversed img {{
  order: 1;
}}

.feature p,
.notes p,
.feature li {{
  color: var(--muted);
}}

.note-list {{
  margin: 16px 0 0;
  padding-left: 20px;
}}

.feature img {{
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-soft);
}}

.notes {{
  margin-top: 28px;
  padding: 32px;
}}

.improvements {{
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  margin: 0;
  padding: 0;
  list-style: none;
}}

.improvements > li {{
  display: grid;
  gap: 6px;
  min-width: 0;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}}

.improvements > li > span {{
  color: var(--muted);
}}

.more-toggle {{
  position: absolute;
  opacity: 0;
  pointer-events: none;
}}

.show-more-link,
.show-less-link {{
  display: inline-flex;
  margin-top: 8px;
  color: var(--accent);
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
}}

.show-more-link:hover,
.show-less-link:hover {{
  color: var(--accent-strong);
  text-decoration: underline;
}}

.more-continuation,
.show-less-link {{
  display: none;
}}

.more-toggle:checked ~ .note-list .more-continuation {{
  display: inline;
}}

.more-toggle:checked ~ .show-more-link {{
  display: none;
}}

.more-toggle:checked ~ .show-less-link {{
  display: inline-flex;
}}

code {{
  padding: 2px 5px;
  border-radius: 5px;
  background: var(--surface-soft);
  color: var(--text);
  font-family: "JetBrains Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 0.92em;
}}

@media (max-width: 760px) {{
  .page {{
    width: min(100% - 28px, 1080px);
    padding-top: 24px;
  }}

  .hero,
  .feature,
  .notes {{
    padding: 24px;
  }}

  h1 {{
    font-size: 34px;
  }}

  .feature,
  .feature.reversed {{
    grid-template-columns: 1fr;
  }}

  .improvements {{
    grid-template-columns: 1fr;
  }}

  .feature.reversed div,
  .feature.reversed img {{
    order: initial;
  }}
}}
"""


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stable_path(path: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return resolved.as_posix()


def build_source_json(
    release: ReleaseNotes,
    clusters: Sequence[FeatureCluster],
    changelog: Path,
    design: Path,
    assets: Path,
) -> str:
    payload = {
        "schema": 1,
        "version": release.version,
        "date": release.date,
        "sources": {
            "changelog": stable_path(changelog),
            "changelog_sha256": checksum(changelog),
            "design": stable_path(design),
            "design_sha256": checksum(design) if design.exists() else None,
            "assets": stable_path(assets),
            "assets_sha256": checksum(assets),
        },
        "clusters": [
            {
                "id": cluster.cluster_id,
                "eyebrow": cluster.eyebrow,
                "title": cluster.title,
                "summary": cluster.summary,
                "recipe": cluster.recipe,
                "asset": cluster.asset.target if cluster.asset else None,
                "entries": [{"section": entry.section, "text": entry.text} for entry in cluster.entries],
            }
            for cluster in clusters
        ],
    }
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def write_manifest(output: Path) -> None:
    files = sorted(path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file() and path.name != "manifest.txt")
    (output / "manifest.txt").write_text("\n".join(files) + "\n", encoding="utf-8")


def write_output(
    output: Path,
    release: ReleaseNotes,
    clusters: Sequence[FeatureCluster],
    changelog: Path,
    design: Path,
    assets: Path,
) -> None:
    if output.exists():
        if not output.is_dir():
            raise ValueError(f"Output path exists and is not a directory: {output}")
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "index.html").write_text(render_html(release, clusters), encoding="utf-8")
    (output / "style.css").write_text(render_css(parse_design_tokens(design)), encoding="utf-8")
    (output / "source.json").write_text(build_source_json(release, clusters, changelog, design, assets), encoding="utf-8")

    for cluster in clusters:
        if not cluster.asset:
            continue
        target = output / cluster.asset.target
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(cluster.asset.source, target)

    write_manifest(output)


def plan_text(output: Path, release: ReleaseNotes, clusters: Sequence[FeatureCluster]) -> str:
    rows = [
        f"version: {release.version}",
        f"output: {output}",
        f"clusters: {len(clusters)}",
    ]
    for cluster in clusters:
        asset = cluster.asset.target if cluster.asset else "none"
        rows.append(f"- {cluster.cluster_id}: {len(cluster.entries)} entries, asset={asset}, recipe={cluster.recipe}")
    return "\n".join(rows)


def generate(args: argparse.Namespace) -> tuple[Path, ReleaseNotes, tuple[FeatureCluster, ...]]:
    changelog = args.changelog.resolve()
    design = args.design.resolve()
    assets_path = args.assets.resolve()
    releases = parse_changelog(changelog)
    version = args.version or latest_release_version(releases)
    source_version = args.source_version or version
    if source_version not in releases:
        raise ValueError(f"Version {source_version!r} was not found in {changelog}.")

    source_release = releases[source_version]
    release = ReleaseNotes(version, source_release.date, source_release.entries)
    assets = load_assets(assets_path)
    clusters = build_clusters(release, assets, assets_path)
    output = (args.output.resolve() if args.output else REPO_ROOT / f"idea-plugin/src/main/resources/whatsnew/{version}")

    if args.dry_run:
        print(plan_text(output, release, clusters))
    else:
        write_output(output, release, clusters, changelog, design, assets_path)
        print(plan_text(output, release, clusters))

    return output, release, clusters


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        generate(args)
    except Exception as exc:
        raise SystemExit(f"error: {exc}") from exc
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
