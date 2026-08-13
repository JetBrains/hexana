import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {expect} from 'chai';
import {installVscodeMock, VscodeMock} from './vscode-mock';

describe('webview content', function () {
    let vscode: VscodeMock;
    let tempRoot: string;

    beforeEach(function () {
        tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-webview-content-test-'));
        vscode = installVscodeMock();
        clearWebviewContentModuleCache();
    });

    afterEach(function () {
        vscode.restore();
        fs.rmSync(tempRoot, { recursive: true, force: true });
        clearWebviewContentModuleCache();
    });

    it('embeds generated assets and reconstructs them through Blob URLs', function () {
        const outputPath = path.join(
            tempRoot,
            'webview',
            'build',
            'kotlin-webpack',
            'js',
            'productionExecutable',
        );
        fs.mkdirSync(outputPath, { recursive: true });
        const script = Buffer.from('window.testValue = "</script>";\n', 'utf8');
        const wasmPrinter = Buffer.from([0x00, 0x61, 0x73, 0x6d, 0xff]);
        fs.writeFileSync(path.join(outputPath, 'webview.js'), script);
        fs.writeFileSync(path.join(outputPath, 'wasmprinter_wrapper.wasm'), wasmPrinter);

        const {getWebviewContent} = require('../../../src/webviewContent') as {
            getWebviewContent(webview: unknown, extensionUri: unknown): string;
        };
        const vscodeModule = vscode.module as {
            Uri: { file(fsPath: string): unknown };
        };
        const webview = {
            cspSource: 'https://test.vscode-resource.invalid',
            asWebviewUri: (uri: { toString(): string }) => uri,
        };

        const html = getWebviewContent(webview, vscodeModule.Uri.file(tempRoot));

        expect(html).to.contain(script.toString('base64'));
        expect(html).to.contain(wasmPrinter.toString('base64'));
        expect(html).to.not.contain(script.toString('utf8'));
        expect(html).to.contain('script-src \'nonce-');
        expect(html).to.contain('blob:');
        expect(html).to.contain('new Blob([bytes], { type: mimeType })');
        expect(html).to.contain('window.__wasmPrinterUri = wasmPrinterUrl');
        expect(html).to.not.match(/<script[^>]+src=["'][^"']*webview\.js/);
    });
});

function clearWebviewContentModuleCache(): void {
    const modulePath = require.resolve('../../../src/webviewContent');
    delete require.cache[modulePath];
}
