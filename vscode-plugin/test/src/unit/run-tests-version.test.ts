import { expect } from 'chai';
import { assertDownloadedVsCodeVersion, downloadedVsCodeVersionArguments } from '../vscodeVersion';

describe('run-tests VS Code version verification', () => {
    it('accepts the exact requested downloaded version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.131.0')).not.to.throw();
    });

    it('rejects a cache or downloader fallback version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.133.0'))
            .to.throw('Requested VS Code 1.131.0, but downloaded executable reports 1.133.0');
    });

    it('runs the downloaded VS Code executable without a sandbox only as Linux root', () => {
        expect(downloadedVsCodeVersionArguments('linux', 0)).to.deep.equal(['--no-sandbox', '--version']);
    });

    it('keeps the default version command outside Linux root', () => {
        expect(downloadedVsCodeVersionArguments('darwin', 0)).to.deep.equal(['--version']);
        expect(downloadedVsCodeVersionArguments('linux', 501)).to.deep.equal(['--version']);
    });
});
