import {expect} from 'chai';
import {installVscodeMock, VscodeMock} from './vscode-mock';

/**
 * A file `.hexana/file_associations.json` names a parser for opens in Hexana even when nothing
 * else would route it there — no claimed extension, no magic Hexana recognizes, as for a format
 * only a provider script understands. The detector is handed the association check; the
 * check itself reads the workspace's JSON and lives in `WasmEditorProvider.ts`.
 */
describe('file association routing', function () {
    let vscode: VscodeMock;
    let fire: (event: unknown) => void;
    let closed: unknown[];
    let opened: unknown[][];
    let configUpdates: unknown[];
    let fileBytes: Uint8Array;

    class TabInputText {
        constructor(public readonly uri: unknown) {}
    }

    beforeEach(function () {
        vscode = installVscodeMock();
        clearModuleCache();
        closed = [];
        opened = [];
        configUpdates = [];
        // Bytes no format claims: the case the association has to carry on its own.
        fileBytes = new Uint8Array(Buffer.from('FWIM\x02\x00\x00\x00 an image only a script reads'));
        const module = vscode.module as any;
        const listeners: Array<(event: unknown) => unknown> = [];
        fire = (event) => listeners.forEach((listener) => listener(event));
        module.TabInputText = TabInputText;
        module.window.tabGroups = {
            onDidChangeTabs: (listener: (event: unknown) => unknown) => {
                listeners.push(listener);
                return {dispose: () => undefined};
            },
            close: async (tab: unknown) => { closed.push(tab); },
        };
        module.workspace.fs = {readFile: async () => fileBytes};
        const baseGetConfiguration = module.workspace.getConfiguration;
        module.workspace.getConfiguration = (section: string) => ({
            ...baseGetConfiguration(section),
            inspect: () => ({globalValue: {}}),
            update: (...args: unknown[]) => { configUpdates.push(args); },
        });
        vscode.commands.set('vscode.openWith', (...args: unknown[]) => { opened.push(args); });
    });

    afterEach(function () {
        vscode.restore();
        clearModuleCache();
    });

    function tabFor(fsPath: string) {
        return new TabInputText({scheme: 'file', fsPath, path: fsPath, toString: () => fsPath});
    }

    function register(associated: boolean): void {
        const {registerNativeBinaryDetector} = require('../../../src/NativeBinaryFileTypeDetector');
        registerNativeBinaryDetector({subscriptions: []}, async () => associated);
    }

    async function fireTextTab(fsPath: string): Promise<void> {
        fire({opened: [{input: tabFor(fsPath)}]});
        // The listener awaits the association check and the reopen; let them settle.
        await new Promise((resolve) => setTimeout(resolve, 0));
    }

    async function openTextTab(fsPath: string, associated: boolean): Promise<void> {
        register(associated);
        await fireTextTab(fsPath);
    }

    it('reopens an associated file in Hexana whatever its bytes', async function () {
        await openTextTab('/w/firmware-image.bin', true);
        expect(closed).to.have.length(1);
        expect(opened).to.have.length(1);
        expect(opened[0][1]).to.equal('hexana.wasmEditor');
        // No `*.bin` glob: that would send every .bin on the machine to Hexana.
        expect(configUpdates).to.have.length(0);
    });

    it('reroutes the file every time it is opened, not only the first', async function () {
        // Nothing is persisted for an associated file, so each open starts as a text tab.
        register(true);
        await fireTextTab('/w/firmware-image.bin');
        await fireTextTab('/w/firmware-image.bin');
        await fireTextTab('/w/firmware-image.bin');
        expect(opened).to.have.length(3);
    });

    it('leaves an unassociated file with unknown bytes in the text editor', async function () {
        await openTextTab('/w/notes.bin', false);
        expect(closed).to.have.length(0);
        expect(opened).to.have.length(0);
    });
});

function clearModuleCache(): void {
    for (const key of Object.keys(require.cache)) {
        if (key.includes('NativeBinaryFileTypeDetector')) {
            delete require.cache[key];
        }
    }
}
