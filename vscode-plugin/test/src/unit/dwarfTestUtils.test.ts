import { strict as assert } from 'assert';
import fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { isSameFile } from '../ui-test/test-utils';

describe('DWARF source file identity', () => {
    let tempRoot: string;
    let source: string;
    const contents = 'fn add(a: i32, b: i32) -> i32 { a + b }';

    beforeEach(() => {
        tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-dwarf-source-'));
        fs.mkdirSync(path.join(tempRoot, 'sources'));
        source = path.join(tempRoot, 'sources', 'debug_add.rs');
        fs.writeFileSync(source, contents);
    });

    afterEach(() => {
        fs.rmSync(tempRoot, { recursive: true, force: true });
    });

    it('accepts the same existing source', () => {
        assert.equal(isSameFile(source, source), true);
    });

    it('accepts a hard-link alias of the source', () => {
        const alias = path.join(tempRoot, 'alias.rs');
        fs.linkSync(source, alias);
        assert.equal(isSameFile(alias, source), true);
    });

    it('follows a directory link to the source', () => {
        const aliasDirectory = path.join(tempRoot, 'linked-sources');
        // A junction does not require Windows symlink privileges; on POSIX this is a symlink.
        fs.symlinkSync(path.dirname(source), aliasDirectory, 'junction');
        assert.equal(isSameFile(path.join(aliasDirectory, 'debug_add.rs'), source), true);
    });

    it('rejects another file even with the same basename and contents', () => {
        const other = path.join(tempRoot, 'debug_add.rs');
        fs.writeFileSync(other, contents);
        assert.equal(isSameFile(other, source), false);
    });

    for (const component of ['file', 'directory'] as const) {
        it(`respects the actual filesystem's ${component}-name case sensitivity`, () => {
            const alternate = component === 'file'
                ? path.join(path.dirname(source), 'DEBUG_ADD.rs')
                : path.join(tempRoot, 'SOURCES', 'debug_add.rs');
            let separateFile = false;
            try {
                if (component === 'directory') {
                    fs.mkdirSync(path.dirname(alternate));
                }
                fs.writeFileSync(alternate, contents, { flag: 'wx' });
                separateFile = true;
            } catch (error) {
                if ((error as NodeJS.ErrnoException).code !== 'EEXIST') {
                    throw error;
                }
            }
            // Exclusive creation distinguishes an alias from a separate file without an OS guess.
            assert.equal(isSameFile(alternate, source), !separateFile);
        });
    }

    it('accepts Windows drive-letter aliases of a real source', function () {
        if (process.platform !== 'win32' || !/^[A-Za-z]:/.test(source)) {
            this.skip();
        }
        const lowerDrive = source[0].toLowerCase() + source.slice(1);
        const upperDrive = source[0].toUpperCase() + source.slice(1);
        assert.equal(isSameFile(lowerDrive, upperDrive), true);
    });

    it('compares directories by identity too, without confusing them with a source file', () => {
        assert.equal(isSameFile(tempRoot, tempRoot), true);
        assert.equal(isSameFile(tempRoot, path.dirname(source)), false);
        assert.equal(isSameFile(tempRoot, source), false);
    });

    it('does not equate missing paths, even when their strings are identical', () => {
        const missing = path.join(tempRoot, 'missing.rs');
        assert.throws(() => isSameFile(missing, source), { code: 'ENOENT' });
        assert.throws(() => isSameFile(source, missing), { code: 'ENOENT' });
        assert.throws(() => isSameFile(missing, missing), { code: 'ENOENT' });
    });

    it('distinguishes devices and file IDs beyond Number precision, and rejects unavailable IDs', () => {
        const other = path.join(tempRoot, 'other.rs');
        fs.writeFileSync(other, contents);
        const originalStat = fs.statSync;
        const largeId = 2n ** 53n;
        try {
            for (const [actualDev, actualIno] of [[2n, largeId], [1n, largeId + 1n], [0n, largeId], [1n, 0n]]) {
                Object.defineProperty(fs, 'statSync', { value: ((file, options) => {
                    assert.deepEqual(options, { bigint: true });
                    const stat = originalStat(file, { bigint: true });
                    stat.dev = file === other ? actualDev : 1n;
                    stat.ino = file === other ? actualIno : largeId;
                    return stat;
                }) as typeof fs.statSync });
                if (actualDev === 0n || actualIno === 0n) {
                    assert.throws(() => isSameFile(other, source), /usable file identity/);
                } else {
                    assert.equal(isSameFile(other, source), false);
                }
            }
        } finally {
            Object.defineProperty(fs, 'statSync', { value: originalStat });
        }
    });
});
