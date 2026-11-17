import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { expect } from 'chai';
import {
    assertDownloadedVsCodeVersion,
    downloadedVsCodeProductMetadataPath,
    parseDownloadedVsCodeVersion,
} from '../vscodeVersion';

describe('run-tests VS Code version verification', () => {
    let codeRoot: string;

    beforeEach(() => {
        codeRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-vscode-product-metadata-'));
    });

    afterEach(() => {
        fs.rmSync(codeRoot, { recursive: true, force: true });
    });

    it('accepts the exact requested downloaded version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.131.0')).not.to.throw();
    });

    it('rejects a cache or downloader fallback version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.133.0'))
            .to.throw('Requested VS Code 1.131.0, but downloaded executable reports 1.133.0');
    });

    it('uses the Linux product metadata location', () => {
        expect(downloadedVsCodeProductMetadataPath('/download/VSCode', 'linux'))
            .to.equal('/download/VSCode/resources/app/product.json');
    });

    it('uses the macOS product metadata location', () => {
        expect(downloadedVsCodeProductMetadataPath('/download/Visual Studio Code.app', 'darwin'))
            .to.equal('/download/Visual Studio Code.app/Contents/Resources/app/product.json');
    });

    it('prefers legacy Windows product metadata over a nested layout', () => {
        const legacyPath = writeProductMetadata(codeRoot);
        writeProductMetadata(path.join(codeRoot, 'commit-hash'));

        expect(downloadedVsCodeProductMetadataPath(codeRoot, 'win32')).to.equal(legacyPath);
    });

    it('finds Windows product metadata one level below the Code root', () => {
        const nestedPath = writeProductMetadata(path.join(codeRoot, 'commit-hash'));

        expect(downloadedVsCodeProductMetadataPath(codeRoot, 'win32')).to.equal(nestedPath);
    });

    it('rejects ambiguous nested Windows product metadata', () => {
        writeProductMetadata(path.join(codeRoot, 'first-commit'));
        writeProductMetadata(path.join(codeRoot, 'second-commit'));

        expect(() => downloadedVsCodeProductMetadataPath(codeRoot, 'win32'))
            .to.throw('Ambiguous Windows VS Code product metadata');
    });

    it('parses the downloaded product metadata version before strict validation', () => {
        const actualVersion = parseDownloadedVsCodeVersion('{"version":"1.131.0"}');
        expect(actualVersion).to.equal('1.131.0');
        expect(() => assertDownloadedVsCodeVersion('1.131.0', actualVersion)).not.to.throw();
    });

    it('rejects product metadata without a string version', () => {
        expect(() => parseDownloadedVsCodeVersion('{"version":131}'))
            .to.throw('Downloaded VS Code product metadata does not contain a string version');
    });
});

function writeProductMetadata(root: string): string {
    const metadataPath = path.join(root, 'resources', 'app', 'product.json');
    fs.mkdirSync(path.dirname(metadataPath), { recursive: true });
    fs.writeFileSync(metadataPath, '{"version":"1.131.0"}', 'utf8');
    return metadataPath;
}
