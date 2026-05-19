# Hexana Codex Plugin

This package is the Codex-facing wrapper for the standalone Hexana MCP runtime in `mcp/server`.

## What is bundled

- `.codex-plugin/plugin.json`: required Codex plugin manifest
- `.mcp.json`: distributable MCP config that launches `./bin/hexana-mcp` with `cwd` set to the plugin root
- `bin/hexana-mcp`: thin launcher that delegates to the bundled standalone server
- `skills/wasm-inspector/`: bundled Codex skill plus `agents/openai.yaml` metadata

## Repo-local install flow

Use the repo marketplace path documented by Codex:

```bash
./gradlew :mcp:installRepoLocalCodexPlugin
```

That task:

- installs the assembled plugin to `plugins/hexana/`
- keeps the committed repo marketplace at `.agents/plugins/marketplace.json`
- rewrites the installed `.mcp.json` to the absolute `plugins/hexana/bin/hexana-mcp` launcher path

After running the task, restart Codex and install `Hexana` from the `Hexana Local` marketplace.

## Notes from the current Codex docs

- Custom distribution is currently local or curated-marketplace based.
- Official public Plugin Directory self-serve publishing is not open yet.
- `agents/openai.yaml` applies to bundled skills, not to the plugin root.
- The documented install flow is explicit for the Codex app and CLI plugin directory surfaces.

This layout intentionally follows the same repo-local marketplace shape that `$plugin-creator` generates for local
testing.
