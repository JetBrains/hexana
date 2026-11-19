import {expect} from 'chai';
import {createHash} from 'crypto';
import {
    DirectoryFileSystem,
    directoryListing,
    directoryListingFailure,
    listDirectory,
    MAX_CONCURRENT_OPERATIONS,
    MAX_HASHED_FILE_BYTES,
} from '../../../src/directoryListing';

/**
 * The extension-host half of `list-directory` / `directory-listing`. Its webview counterpart decodes
 * the reply from untyped JS (`directoryListings` in `VscodeMessageBridge.kt`), so the field names here
 * are the contract: `name`, `isFile`, `isDirectory`, `size`, `sha256`.
 */
describe('directory listing', function () {
    const files: Record<string, Uint8Array> = {
        '/fw/app.abc.wasm': new Uint8Array([0, 0x61, 0x73, 0x6d]),
        '/fw/app.abc.wasm.br': new Uint8Array([1, 2]),
        '/fw/huge.dat': new Uint8Array(0),
    };
    const fs: DirectoryFileSystem = {
        readDirectory: async () => [['app.abc.wasm', 1], ['app.abc.wasm.br', 1], ['sub', 2], ['huge.dat', 1], ['gone.js', 1]],
        stat: async (path) => {
            if (path === '/fw/sub') return {type: 2, size: 0};
            if (path === '/fw/huge.dat') return {type: 1, size: MAX_HASHED_FILE_BYTES + 1};
            const bytes = files[path];
            if (!bytes) throw new Error('ENOENT');
            return {type: 1, size: bytes.length};
        },
        readFile: async (path) => {
            const bytes = files[path];
            if (!bytes) throw new Error('ENOENT');
            return bytes;
        },
    };

    it('lists names, kinds and sizes', async function () {
        const entries = await listDirectory(fs, '/fw');
        expect(entries.map((e) => e.name)).to.deep.equal(['app.abc.wasm', 'app.abc.wasm.br', 'sub', 'huge.dat', 'gone.js']);
        expect(entries[0]).to.include({isFile: true, isDirectory: false, size: 4});
        expect(entries[2]).to.include({isFile: false, isDirectory: true});
        expect(entries[2].size).to.equal(undefined);
        // Unstattable: kept, with what readDirectory said and no size.
        expect(entries[4]).to.include({isFile: true});
        expect(entries[4].size).to.equal(undefined);
        expect(entries.every((e) => e.sha256 === undefined)).to.equal(true);
    });

    it('hashes only the requested files that fit', async function () {
        const entries = await listDirectory(fs, '/fw/', ['app.abc.wasm', 'huge.dat', 'gone.js']);
        const expected = createHash('sha256').update(files['/fw/app.abc.wasm']).digest('base64');
        expect(entries.find((e) => e.name === 'app.abc.wasm')?.sha256).to.equal(expected);
        expect(entries.find((e) => e.name === 'app.abc.wasm.br')?.sha256).to.equal(undefined);
        expect(entries.find((e) => e.name === 'huge.dat')?.sha256).to.equal(undefined);
        expect(entries.find((e) => e.name === 'gone.js')?.sha256).to.equal(undefined);
    });

    it('runs a bounded number of file system operations at once', async function () {
        let inFlight = 0;
        let peak = 0;
        const track = async <T>(value: T): Promise<T> => {
            inFlight++;
            peak = Math.max(peak, inFlight);
            await new Promise((resolve) => setTimeout(resolve, 1));
            inFlight--;
            return value;
        };
        const names = Array.from({length: 100}, (_, i) => `f${i}.wasm`);
        const many: DirectoryFileSystem = {
            readDirectory: async () => names.map((n): [string, number] => [n, 1]),
            stat: (path) => track({type: 1, size: 4}),
            readFile: (path) => track(new Uint8Array(4)),
        };
        const entries = await listDirectory(many, '/fw', names);
        expect(entries.length).to.equal(100);
        expect(entries.every((e) => e.sha256 !== undefined)).to.equal(true);
        expect(peak).to.be.at.most(MAX_CONCURRENT_OPERATIONS);
    });

    it('stops hashing at the total budget without reading the files past it', async function () {
        const limits = {maxHashedFileBytes: 100, maxHashedTotalBytes: 250, maxConcurrentOperations: 2};
        const names = ['a.wasm', 'b.wasm', 'c.wasm', 'd.wasm'];
        const reads: string[] = [];
        const sized: DirectoryFileSystem = {
            readDirectory: async () => names.map((n): [string, number] => [n, 1]),
            stat: async () => ({type: 1, size: 100}),
            readFile: async (path) => {
                reads.push(path);
                return new Uint8Array(100);
            },
        };
        const entries = await listDirectory(sized, '/fw', names, limits);
        expect(entries.filter((e) => e.sha256 !== undefined).map((e) => e.name)).to.deep.equal(['a.wasm', 'b.wasm']);
        expect(reads).to.deep.equal(['/fw/a.wasm', '/fw/b.wasm']);
    });

    it('does not hash a file whose size changed between the stat and the read', async function () {
        const grown: DirectoryFileSystem = {
            readDirectory: async () => [['grew.wasm', 1]],
            stat: async () => ({type: 1, size: 4}),
            readFile: async () => new Uint8Array(5),
        };
        const entries = await listDirectory(grown, '/fw', ['grew.wasm']);
        expect(entries[0].size).to.equal(4);
        expect(entries[0].sha256).to.equal(undefined);
    });

    it('replies with one message shape for success and failure', function () {
        expect(directoryListing('r1', '/fw', [])).to.deep.equal({type: 'directory-listing', requestId: 'r1', path: '/fw', entries: []});
        expect(directoryListingFailure('r2', '/fw', '')).to.deep.equal({
            type: 'directory-listing', requestId: 'r2', path: '/fw', error: 'The directory could not be listed.',
        });
    });
});
