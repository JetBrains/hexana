import * as fs from 'fs';
import * as path from 'path';
import {expect} from 'chai';

interface CustomEditor {
    viewType: string;
    displayName: string;
    priority?: string;
    selector: Array<{ filenamePattern?: string }>;
}

/**
 * The `customEditors` manifest contract, which nothing else checks — it is data, so a
 * wrong `priority` or a collapsed entry compiles and ships silently while changing how
 * every binary in the workspace opens.
 *
 * Two entries, deliberately:
 *   - `hexana.wasmEditor`  — the formats Hexana claims, `default`, opens on its own.
 *   - `hexana.binaryEditor` — `*` at `option`, so Hexana appears under "Reopen Editor
 *     With…" for a name no pattern matches (an Arrow stream written `.stream`, a
 *     firmware image `.dat`) without ever being chosen automatically.
 *
 * The split is load-bearing in both directions: merging them into one `default` entry
 * on `*` would open every text file as hex, and giving the `*` entry `default`
 * priority would mean two default editors match a `.wasm`, which suppresses
 * auto-opening and drops the user into the picker instead.
 */
describe('customEditors contribution', function () {
    const editors: CustomEditor[] = JSON.parse(fs.readFileSync(extensionManifestPath(), 'utf8'))
        .contributes.customEditors;

    const byViewType = (viewType: string) => {
        const found = editors.find((e) => e.viewType === viewType);
        expect(found, `no customEditors entry for ${viewType}`).to.not.equal(undefined);
        return found!;
    };

    it('claims the formats Hexana supports by default', function () {
        const primary = byViewType('hexana.wasmEditor');
        expect(primary.priority).to.equal('default');

        const pattern = primary.selector[0].filenamePattern ?? '';
        // Each of these resolves to a provider with real tabs behind it, so the editor
        // has to be reached without the user asking for it.
        for (const ext of [
            'wasm', 'elf', 'exe', 'dll', 'dylib', 'so', 'o', 'a',
            'jar', 'zip', 'war', 'apk', 'class', 'dex', 'arsc',
            'desc', 'protoset', 'pcap', 'cap', 'pcapng',
            'parquet', 'arrow', 'arrows', 'pb', 'tar', 'tgz', 'tar.gz', 'gz',
        ]) {
            expect(pattern, `*.${ext} missing from the default selector`).to.contain(`*.${ext}`);
        }
    });

    it('offers itself for any other file, but only on request', function () {
        const anyFile = byViewType('hexana.binaryEditor');
        expect(anyFile.priority).to.equal('option');
        expect(anyFile.selector).to.deep.equal([{ filenamePattern: '*' }]);
    });

    it('has exactly one default-priority editor', function () {
        // Two matching `default` editors make VS Code stop auto-opening either.
        const defaults = editors.filter((e) => (e.priority ?? 'default') === 'default');
        expect(defaults.map((e) => e.viewType)).to.deep.equal(['hexana.wasmEditor']);
    });

    it('names every entry for the user and keeps view types distinct', function () {
        const viewTypes = editors.map((e) => e.viewType);
        expect(new Set(viewTypes).size).to.equal(viewTypes.length);
        for (const editor of editors) {
            expect(editor.displayName, editor.viewType).to.contain('Hexana');
        }
    });
});

/**
 * Walks up from the compiled test to the extension manifest.
 *
 * Searched for rather than reached with a fixed `..` chain: the unit build nests output
 * as `test/out-unit/test/src/unit`, and a depth hard-coded against that breaks silently
 * the next time `tsconfig.unit.json` moves its `outDir` or `rootDir`.
 */
function extensionManifestPath(): string {
    let dir = __dirname;
    for (;;) {
        const candidate = path.join(dir, 'package.json');
        if (fs.existsSync(candidate)) {
            const manifest = JSON.parse(fs.readFileSync(candidate, 'utf8'));
            if (manifest.name === 'hexana-wasm') {
                return candidate;
            }
        }
        const parent = path.dirname(dir);
        if (parent === dir) {
            throw new Error(`could not find the hexana-wasm package.json above ${__dirname}`);
        }
        dir = parent;
    }
}
