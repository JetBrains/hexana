import {expect} from 'chai';
import {pairSidesForLabel} from '../../../src/diffTabPairing';

/**
 * VS Code reports a diff of two files the Hexana custom editor claims as a tab with no
 * sides, only its `original ↔ modified` label. The redirect to the Hexana diff pairs that
 * label with the sides the editor just resolved; a wrong pair would diff unrelated files.
 */
describe('diff tab pairing', function () {
    const nameOf = (path: string) => path.substring(path.lastIndexOf('/') + 1);

    it('pairs distinct names by the label order, not the resolution order', function () {
        const pair = pairSidesForLabel('wasmtime ↔ wasmtime-min', ['/b/wasmtime-min', '/a/wasmtime'], nameOf);
        expect(pair).to.deep.equal(['/a/wasmtime', '/b/wasmtime-min']);
    });

    it('takes same-named sides in resolution order', function () {
        const pair = pairSidesForLabel('app.wasm ↔ app.wasm', ['/old/app.wasm', '/new/app.wasm'], nameOf);
        expect(pair).to.deep.equal(['/old/app.wasm', '/new/app.wasm']);
    });

    it('refuses a label whose sides were not both resolved, or are ambiguous', function () {
        expect(pairSidesForLabel('a.wasm ↔ b.wasm', ['/x/a.wasm'], nameOf)).to.equal(undefined);
        expect(pairSidesForLabel('a.wasm ↔ b.wasm', ['/x/a.wasm', '/y/a.wasm', '/x/b.wasm'], nameOf)).to.equal(undefined);
        expect(pairSidesForLabel('a.wasm ↔ a.wasm', ['/x/a.wasm'], nameOf)).to.equal(undefined);
        expect(pairSidesForLabel('Settings', ['/x/a.wasm', '/x/b.wasm'], nameOf)).to.equal(undefined);
    });
});
