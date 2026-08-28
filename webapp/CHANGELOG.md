# Changelog

All notable changes to the Hexana Webapp are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Navigable nodes in the component "Dependencies" graph: double-clicking a core module or a nested component opens it in its own tab, and double-clicking an import or an export selects that entity's bytes in the hex view. The byte ranges travel with the graph the parser Worker already produces, so no reparse happens on the Window. Core and component instances stay inert — the parser records no per-entry offset for them.

## [0.1.1] - 2026-08-28

### Changed

- Replaced the About popup with an accessible Release notes drawer that shows the bundled release history.
- Expanded custom scripts with a shared binary API and structured table output.

## [0.1.0] - 2026-08-14

### Added

- Initial public Webapp release.
