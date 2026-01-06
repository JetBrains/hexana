# Hexana – Issue Tracker

This repository is used to collect feedback, bug reports, and feature requests for **Hexana** — an experimental IDE plugin focused on WebAssembly and binary inspection.

Hexana is actively evolving, and your feedback directly influences what we build next.

---

## Reporting Bugs

If something doesn’t work as expected, please open a new issue and include as much detail as possible.

### Required Information

- Hexana version (for example: 0.4.0)
- IDE and version (for example: IntelliJ IDEA Ultimate 2024.3)
- Operating system (macOS, Linux, Windows)
- What you expected to happen
- What actually happened

### Strongly Recommended

Providing these details will greatly increase the chance of a quick fix:

- Minimal WASM or WAT sample (or a link to one)
- Screenshots or short screen recordings
- IDE logs or stack traces, if available

For very large binaries (Emscripten, wasm-bindgen, Skiko, etc.), please avoid pasting raw dumps directly. Use links or describe the structure instead.

---

## Requesting Enhancements or New Features

We welcome ideas. Hexana is still defining its scope, and real-world use cases are especially valuable.

When requesting a feature, please describe:

- The problem you’re trying to solve
- Your workflow or usage scenario
- Why existing tools are insufficient
- Optional: how you imagine this working in the IDE

Examples of useful requests include navigation workflows, binary-to-WAT or source mapping ideas, and inspection or debugging scenarios.

---

## Scope of This Issue Tracker

### This tracker is for:

- Hexana plugin behavior and UX
- WebAssembly inspection, navigation, and structure
- Performance issues with large WASM binaries
- Hex View, WAT view, and Structure View issues

### This tracker is not for:

- General IntelliJ IDEA issues
- WebAssembly specification discussions
- Support requests unrelated to Hexana

---

## Experimental Status

Hexana is currently an experimental MVP developed with limited resources.

Some features may be incomplete, change rapidly, or prioritize architectural exploration over polish. Your feedback helps decide what becomes production-ready.

---

## Code of Conduct

Please keep discussions respectful and constructive. Clear reports and concrete examples help everyone.

Thank you for helping shape Hexana.
