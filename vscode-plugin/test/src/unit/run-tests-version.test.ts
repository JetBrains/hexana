import { expect } from 'chai';
import { assertDownloadedVsCodeVersion } from '../vscodeVersion';

describe('run-tests VS Code version verification', () => {
    it('accepts the exact requested downloaded version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.131.0')).not.to.throw();
    });

    it('rejects a cache or downloader fallback version', () => {
        expect(() => assertDownloadedVsCodeVersion('1.131.0', '1.133.0'))
            .to.throw('Requested VS Code 1.131.0, but downloaded executable reports 1.133.0');
    });
});
