---
release_page:
  product: "Hexana"
  channel: "IntelliJ Platform plugin"
  audience: "JetBrains IDE users inspecting WebAssembly, native binaries, archives, and related debug artifacts"
  tone: "quiet, technical, in-product"
css_tokens:
  background: "#f7f8fa"
  surface: "#ffffff"
  surface_soft: "#eef2f7"
  text: "#1f2328"
  muted: "#5f6673"
  accent: "#1f6feb"
  accent_strong: "#1557b0"
  border: "#d8dee8"
  dark_background: "#1e1f22"
  dark_surface: "#2b2d31"
  dark_surface_soft: "#25272b"
  dark_text: "#f2f3f5"
  dark_muted: "#b6bcc7"
  dark_accent: "#72a7ff"
  dark_accent_strong: "#9fc2ff"
  dark_border: "#44484f"
layout_tokens:
  page_max_width: "1080px"
  page_margin: "56px"
  card_radius: "8px"
  feature_gap: "28px"
content_tokens:
  max_feature_cards: 6
  hero_eyebrow: "Hexana update"
  primary_link_label: "Open release notes"
  secondary_link_label: "Open GitHub"
---

# Hexana What's New Page Design

## Purpose

The generated What's New page is an in-IDE release summary for Hexana users. It should explain the most valuable release changes without reading like marketing copy. The page is bundled as static plugin resources and must work in JetBrains IDE embedded browser contexts with no JavaScript and no remote assets.

## Content Model

Each release page contains:

- A compact hero with the exact Hexana version, a release-focused summary, and stable links.
- Feature sections derived from the changelog and grouped by user-facing workflow.
- Screenshot sections for every big visible feature. Prefer real UI-driver captures; use existing repository screenshots only when the release capture policy explicitly allows fallback assets. When fallback is disabled, missing captures make the page incomplete rather than text-only complete.
- A short improvements section for fixed, changed, or lower-visual items that do not need a full card.

Release-specific lead clusters and their presentation mode belong in `assets.yml`. Do not repeat claims from an earlier release as new highlights; keep lower-visual additions in the compact improvements section.

## Visual Direction

Use JetBrains-IDE-appropriate density and restraint:

- Light and dark color tokens mirror IntelliJ Platform neutral surfaces and blue accents.
- Feature cards are bordered sections with 8px radius, not decorative nested cards.
- Typography uses the system UI stack with JetBrains Mono only for code tokens.
- Screenshots should be real product captures. For newly marketed visible features, prefer UI-driver capture flows that open the product into the intended semantic state before taking the screenshot.
- Avoid gradients, illustration-only visuals, and one-hue novelty palettes.

## Generator Contract

The generator reads `idea-plugin/CHANGELOG.md`, `docs/whatsnew/assets.yml`, and this design file. It writes a deterministic folder containing `index.html`, `style.css`, `manifest.txt`, `source.json`, and copied screenshot assets. The orchestrator may update `assets.yml` first with UI-driver screenshot outputs discovered by per-feature capture investigators.

Determinism requirements:

- No timestamps, random values, machine-local absolute paths, or unordered dictionary output.
- Changelog parsing preserves release-note order.
- Asset selection uses the first existing candidate in `assets.yml`.
- `manifest.txt` lists generated files in sorted order.

## Accessibility

Screenshots need descriptive alt text from the cluster title. Text must stay readable in both color schemes. Links and buttons must be real anchors so the embedded browser keeps expected navigation behavior.
