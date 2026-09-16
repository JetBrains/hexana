import {expect} from 'chai';
import {
    binaryFileContent,
    binaryFileReadFailure,
    fileReadFailureReason,
    toWebviewBytes,
} from '../../../src/scriptBinaryRead';

/**
 * The extension-host half of the `read-binary-file` / `binary-file-content` pair
 * that answers `open(path)` in a script tab.
 *
 * The webview decodes this reply from untyped JS, so nothing but these tests and
 * their counterpart in
 * `vscode-plugin/webview/src/jsTest/.../BinaryFileContentDecodeTest.kt` says the
 * two halves agree. They exist because posting the `Buffer` that
 * `vscode.workspace.fs.readFile` returns, without converting it, reached the
 * webview in a shape it could not decode — and a reply the webview cannot
 * decode is a script that never stops running.
 */
describe('script binary read', function () {
    describe('toWebviewBytes', function () {
        it('converts a Node Buffer to a plain Uint8Array', function () {
            const buffer = Buffer.from([0x00, 0x61, 0x73, 0x6d]);

            const posted = toWebviewBytes(buffer);

            expect(posted instanceof Buffer).to.equal(false);
            expect(Object.getPrototypeOf(posted)).to.equal(Uint8Array.prototype);
            expect(posted.length).to.equal(4);
            expect(Array.from(posted)).to.deep.equal([0x00, 0x61, 0x73, 0x6d]);
        });

        it('keeps the bytes of a Buffer that is a view into a larger pool', function () {
            // Buffer.from(array) allocates out of a shared pool, so byteOffset is
            // usually non-zero: the conversion has to respect it or the webview
            // reads someone else's bytes.
            const pool = Buffer.from([1, 2, 3, 4, 5, 6]);
            const slice = pool.subarray(2, 5);

            expect(Array.from(toWebviewBytes(slice))).to.deep.equal([3, 4, 5]);
        });

        it('passes a plain Uint8Array through', function () {
            const bytes = new Uint8Array([7, 8]);

            expect(toWebviewBytes(bytes)).to.equal(bytes);
        });
    });

    describe('binaryFileContent', function () {
        it('posts the field names the webview reads, with decodable bytes', function () {
            const message = binaryFileContent('script-open-3', '/tmp/other.wasm', Buffer.from([1, 2]));

            expect(message.type).to.equal('binary-file-content');
            expect(message.requestId).to.equal('script-open-3');
            expect(message.path).to.equal('/tmp/other.wasm');
            expect(message.error).to.equal(undefined);
            // The webview decodes a payload by its numeric length and index, so
            // this is the property that has to hold, not the exact class.
            expect(typeof message.data?.length).to.equal('number');
            expect(Array.from(message.data ?? [])).to.deep.equal([1, 2]);
        });

        it('carries an empty file as an empty payload, not as a failure', function () {
            const message = binaryFileContent('script-open-4', '/tmp/empty.wasm', new Uint8Array(0));

            expect(message.data?.length).to.equal(0);
            expect(message.error).to.equal(undefined);
        });
    });

    describe('binaryFileReadFailure', function () {
        it('phrases the failure the way a script reads it in every other host', function () {
            const message = binaryFileReadFailure('script-open-5', '/tmp/missing.wasm', 'File not found');

            expect(message.type).to.equal('binary-file-content');
            expect(message.requestId).to.equal('script-open-5');
            expect(message.data).to.equal(undefined);
            expect(message.error).to.equal('open("/tmp/missing.wasm") — File not found');
        });
    });

    describe('fileReadFailureReason', function () {
        it('translates the VS Code file system error codes a script can hit', function () {
            expect(fileReadFailureReason({code: 'FileNotFound'})).to.equal('File not found');
            expect(fileReadFailureReason({code: 'FileIsADirectory'})).to.equal('File not found');
            expect(fileReadFailureReason({code: 'NoPermissions'})).to.equal('File is not readable');
        });

        it('falls back to the error message for anything else', function () {
            expect(fileReadFailureReason(new Error('disk on fire'))).to.equal('disk on fire');
        });
    });
});
