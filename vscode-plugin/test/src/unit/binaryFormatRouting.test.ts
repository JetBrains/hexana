import {expect} from 'chai';
import * as fs from 'fs';
import * as path from 'path';
import {installVscodeMock, VscodeMock} from './vscode-mock';

/**
 * The magic sniff that decides whether an extensionless file is reopened with the
 * Hexana editor instead of left in VS Code's "binary file not displayed" view.
 *
 * It exists in this shape because Parquet and Arrow files resolved in the IntelliJ
 * plugin and fell through in VS Code: neither the `customEditors` `filenamePattern`
 * nor this sniff knew the columnar formats, so the editor they have full tab support
 * for was never reached.
 *
 * Once the file does reach the webview, the provider it resolves to is the Kotlin
 * side's job — covered by
 * `binaryProvider/src/commonTest/.../BinaryProviderFactoryTest.kt`.
 */
describe('binary format routing', function () {
    let vscode: VscodeMock;

    beforeEach(function () {
        vscode = installVscodeMock();
        clearModuleCache();
    });

    afterEach(function () {
        vscode.restore();
        clearModuleCache();
    });

    describe('isNativeBinary', function () {
        it('recognizes Parquet and Arrow IPC files by their magic', function () {
            const {isNativeBinary} = requireDetector();

            // An extensionless export — the case the detector exists for: it closes the
            // text tab VS Code opened and reopens the file with the Hexana editor.
            expect(isNativeBinary(withMagic('PAR1'))).to.equal(true);
            expect(isNativeBinary(withMagic('ARROW1'))).to.equal(true);
        });

        it('leaves an Arrow stream alone', function () {
            // A stream's only marker is a 0xFFFFFFFF continuation word, which a padded
            // ROM image or a corrupted binary matches just as well. Streams reach the
            // editor through the .arrow / .arrows extension instead.
            const {isNativeBinary} = requireDetector();
            const stream = new Uint8Array(32);
            stream.fill(0xFF, 0, 4);

            expect(isNativeBinary(stream)).to.equal(false);
        });

        it('recognizes Windows and Portable PDBs, but not Protein Data Bank text', function () {
            const {isNativeBinary} = requireDetector();
            const read = (name: string) => new Uint8Array(fs.readFileSync(path.join(peFixturesDir(), name))).subarray(0, 64);

            expect(isNativeBinary(read('sample.pdb'))).to.equal(true);
            expect(isNativeBinary(read('sample-clr-portable.pdb'))).to.equal(true);
            expect(isNativeBinary(new Uint8Array(Buffer.from('HEADER    PLANT PROTEIN    30-APR-81   1CRN\n')))).to.equal(false);
        });

        it('still recognizes the formats it always did', function () {
            const {isNativeBinary} = requireDetector();

            expect(isNativeBinary(new Uint8Array([0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0]))).to.equal(true);
            expect(isNativeBinary(withMagic('\0asm'))).to.equal(true);
            expect(isNativeBinary(new Uint8Array([0x01, 0x02, 0x03, 0x04]))).to.equal(false);
        });
    });
});

/** `magic` followed by padding, plus a repeat of it — Parquet and Arrow check both ends. */
function withMagic(magic: string): Uint8Array {
    const head = Buffer.from(magic, 'binary');
    return new Uint8Array(Buffer.concat([head, Buffer.alloc(32), head]));
}

/** `parsing/native-fixtures/test-data/pe`, found by walking up to the repository root. */
function peFixturesDir(): string {
    let dir = __dirname;
    while (!fs.existsSync(path.join(dir, 'settings.gradle.kts'))) {
        const parent = path.dirname(dir);
        if (parent === dir) throw new Error('repository root not found');
        dir = parent;
    }
    return path.join(dir, 'parsing', 'native-fixtures', 'test-data', 'pe');
}

function requireDetector(): {isNativeBinary(bytes: Uint8Array): boolean} {
    return require('../../../src/NativeBinaryFileTypeDetector');
}

/**
 * The module imports `vscode` at load time, so each test has to re-require it
 * against its own freshly installed mock.
 */
function clearModuleCache(): void {
    for (const key of Object.keys(require.cache)) {
        if (key.includes('NativeBinaryFileTypeDetector')) {
            delete require.cache[key];
        }
    }
}
