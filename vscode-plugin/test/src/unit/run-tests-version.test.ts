import { expect } from 'chai';
import {
    assertDownloadedVsCodeVersion,
    downloadedVsCodeProductMetadataPath,
    parseDownloadedVsCodeVersion,
} from '../vscodeVersion';

describe('run-tests VS Code version verification', () => {
    it('accepts the exact requested downloaded version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.131.0')).not.to.throw();
    });

    it('rejects a cache or downloader fallback version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.133.0'))
            .to.throw('Requested VS Code 1.131.0, but downloaded executable reports 1.133.0');
    });

    it('uses the Linux and Windows product metadata location', () => {
        expect(downloadedVsCodeProductMetadataPath('/download/VSCode', 'linux'))
            .to.equal('/download/VSCode/resources/app/product.json');
        expect(downloadedVsCodeProductMetadataPath('/download/VSCode', 'win32'))
            .to.equal('/download/VSCode/resources/app/product.json');
    });

    it('uses the macOS product metadata location', () => {
        expect(downloadedVsCodeProductMetadataPath('/download/Visual Studio Code.app', 'darwin'))
            .to.equal('/download/Visual Studio Code.app/Contents/Resources/app/product.json');
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
