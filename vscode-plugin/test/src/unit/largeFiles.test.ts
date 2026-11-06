import {expect} from 'chai';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {installVscodeMock, VscodeMock} from './vscode-mock';

/**
 * A file larger than the editor can hold opens as its head instead of failing: Node's
 * `readFile` (and so `workspace.fs.readFile`) rejects anything over 2 GiB outright.
 */
describe('large files', function () {
    let vscode: VscodeMock;
    let dir: string;

    beforeEach(function () {
        vscode = installVscodeMock();
        clearModuleCache();
        dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-large-'));
    });

    afterEach(function () {
        vscode.restore();
        clearModuleCache();
        fs.rmSync(dir, {recursive: true, force: true});
    });

    const fileUri = (fsPath: string): any => ({fsPath, path: fsPath, scheme: 'file', toString: () => fsPath});

    /** A sparse file: [head] written at offset 0, the length set to [size] without writing it. */
    function sparseFile(head: Uint8Array, size: number): string {
        const file = path.join(dir, 'big.gguf');
        fs.writeFileSync(file, head);
        fs.truncateSync(file, size);
        return file;
    }

    it('reads only the head of a file over 2 GiB and reports its real size', async function () {
        const {readHead} = requireLargeFiles();
        const size = 3 * 1024 * 1024 * 1024;
        const file = sparseFile(Buffer.from('GGUF\x03\x00\x00\x00', 'binary'), size);

        const head = await readHead(fileUri(file), 1024 * 1024);

        expect(head.fileSize).to.equal(size);
        expect(head.data.byteLength).to.equal(1024 * 1024);
        expect(Buffer.from(head.data.subarray(0, 4)).toString('binary')).to.equal('GGUF');
    });

    it('reads a file within the limit whole', async function () {
        const {readHead, isPartial} = requireLargeFiles();
        const file = sparseFile(new Uint8Array([1, 2, 3]), 10);

        const head = await readHead(fileUri(file), 1024);

        expect(head.fileSize).to.equal(10);
        expect(Array.from(head.data)).to.deep.equal([1, 2, 3, 0, 0, 0, 0, 0, 0, 0]);
        expect(isPartial(head.data.byteLength, head.fileSize)).to.equal(false);
    });

    it('refuses a too-large file on a file system without ranged reads', async function () {
        const {readHead, FileTooLargeError} = requireLargeFiles();
        let readWhole = false;
        (vscode.module as any).workspace.fs = {
            stat: async () => ({size: 3 * 1024 * 1024 * 1024}),
            readFile: async () => { readWhole = true; return new Uint8Array(0); },
        };
        const remote: any = {fsPath: '/big.gguf', path: '/big.gguf', scheme: 'vscode-remote', toString: () => 'remote'};

        let error: unknown;
        try {
            await readHead(remote, 512 * 1024 * 1024);
        } catch (e) {
            error = e;
        }
        expect(error).to.be.instanceOf(FileTooLargeError);
        expect((error as Error).message).to.equal(
            'This file is 3.0 GB; Hexana can open at most 512.0 MB of a file that is not on the local disk.',
        );
        expect(readWhole).to.equal(false);
    });

    it('sniffs a prefix without reading the file', async function () {
        const {readPrefix} = requireLargeFiles();
        const file = sparseFile(new Uint8Array([0x7F, 0x45, 0x4C, 0x46]), 3 * 1024 * 1024 * 1024);

        const prefix = await readPrefix(fileUri(file), 64);

        expect(prefix.byteLength).to.equal(64);
        expect(Array.from(prefix.subarray(0, 4))).to.deep.equal([0x7F, 0x45, 0x4C, 0x46]);
    });

    it('clamps the configured limit', function () {
        const {clampLoadedBytes, maxLoadedBytes, MAX_LOADABLE_BYTES} = requireLargeFiles();

        expect(maxLoadedBytes()).to.equal(512 * 1024 * 1024);
        vscode.settings['hexana.maxLoadedFileSizeMB'] = 64;
        expect(maxLoadedBytes()).to.equal(64 * 1024 * 1024);
        expect(clampLoadedBytes(0)).to.equal(1024 * 1024);
        expect(clampLoadedBytes(100_000)).to.equal(MAX_LOADABLE_BYTES);
        expect(clampLoadedBytes(Number.NaN)).to.equal(512 * 1024 * 1024);
    });

    it('matches the Kotlin window limit', function () {
        const {MAX_LOADABLE_BYTES} = requireLargeFiles();
        const kotlin = fs.readFileSync(
            path.join(path.dirname(extensionDir()), 'binaryProvider', 'src', 'commonMain', 'kotlin',
                'org', 'jetbrains', 'hexana', 'providers', 'LargeFiles.kt'),
            'utf8',
        );
        const value = /MAX_BINARY_WINDOW_BYTES: Int = ([\d_]+)/.exec(kotlin)?.[1]?.replace(/_/g, '');
        expect(Number(value)).to.equal(MAX_LOADABLE_BYTES);
    });

    it('declares the setting with the same default', function () {
        const {DEFAULT_MAX_LOADED_MB} = requireLargeFiles();
        const manifest = JSON.parse(fs.readFileSync(path.join(extensionDir(), 'package.json'), 'utf8'));
        const setting = manifest.contributes.configuration.properties['hexana.maxLoadedFileSizeMB'];
        expect(setting.default).to.equal(DEFAULT_MAX_LOADED_MB);
    });
});

/** `vscode-plugin/`: the first directory up from here whose package.json contributes the editors. */
function extensionDir(): string {
    for (let dir = __dirname; ; dir = path.dirname(dir)) {
        const candidate = path.join(dir, 'package.json');
        if (fs.existsSync(candidate) && JSON.parse(fs.readFileSync(candidate, 'utf8')).contributes?.customEditors) return dir;
        if (path.dirname(dir) === dir) throw new Error('vscode-plugin/package.json not found');
    }
}

function requireLargeFiles(): typeof import('../../../src/largeFiles') {
    return require('../../../src/largeFiles');
}

function clearModuleCache(): void {
    for (const key of Object.keys(require.cache)) {
        if (key.includes('largeFiles')) delete require.cache[key];
    }
}

describe('large file ranges and search', function () {
    let vscode: VscodeMock;
    let dir: string;

    beforeEach(function () {
        vscode = installVscodeMock();
        clearModuleCache();
        dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-range-'));
    });

    afterEach(function () {
        vscode.restore();
        clearModuleCache();
        fs.rmSync(dir, {recursive: true, force: true});
    });

    const fileUri = (fsPath: string): any => ({fsPath, path: fsPath, scheme: 'file', toString: () => fsPath});

    function sparse(marks: Array<[number, string]>, size: number): string {
        const file = path.join(dir, 'big.bin');
        fs.writeFileSync(file, Buffer.alloc(0));
        fs.truncateSync(file, size);
        const fd = fs.openSync(file, 'r+');
        for (const [offset, text] of marks) fs.writeSync(fd, Buffer.from(text, 'binary'), 0, text.length, offset);
        fs.closeSync(fd);
        return file;
    }

    it('reads a range past 2 GiB and stops at the end of the file', async function () {
        const {readRange} = requireLargeFiles();
        const far = 2.5 * 1024 * 1024 * 1024;
        const file = sparse([[far, 'PAST-2GB']], far + 8);

        expect(Buffer.from(await readRange(fileUri(file), far, 8)).toString('binary')).to.equal('PAST-2GB');
        expect((await readRange(fileUri(file), far + 4, 100)).byteLength).to.equal(4);
        expect((await readRange(fileUri(file), far + 100, 10)).byteLength).to.equal(0);
    });

    it('finds matches forward and backward, across search chunks and past 2 GiB', async function () {
        const {searchFile} = requireLargeFiles();
        const far = 2.5 * 1024 * 1024 * 1024;
        const chunkEdge = 8 * 1024 * 1024 - 3; // straddles the first search chunk boundary
        const file = sparse([[chunkEdge, 'NEEDLE'], [far, 'NEEDLE']], far + 4096);
        const needle = new Uint8Array(Buffer.from('NEEDLE'));

        expect(await searchFile(fileUri(file), needle, 0, false)).to.equal(chunkEdge);
        expect(await searchFile(fileUri(file), needle, chunkEdge + 1, false)).to.equal(far);
        expect(await searchFile(fileUri(file), needle, far + 4096, true)).to.equal(far);
        expect(await searchFile(fileUri(file), needle, far - 1, true)).to.equal(chunkEdge);
        expect(await searchFile(fileUri(file), needle, far + 1, false)).to.equal(-1);
    });
});
