import * as fs from 'fs';
import * as path from 'path';
import { expect } from 'chai';
import { EditorView, Key, VSBrowser, WebDriver, Workbench } from 'vscode-extension-tester';

describe('Hexana MCP Server Download Failure - Acceptance Tests', function () {
    this.timeout(180_000);

    const enabled = process.env.HEXANA_VSCODE_MCP_FAILURE_TEST === '1';
    const storageDir = process.env.HEXANA_MCP_STORAGE_DIR;
    const requestLogPath = process.env.HEXANA_MCP_MOCK_REQUEST_LOG;
    let driver: WebDriver;

    before(async function () {
        if (!enabled) {
            this.skip();
        }
        expect(storageDir, 'HEXANA_MCP_STORAGE_DIR must be set').to.be.a('string').and.not.empty;
        expect(requestLogPath, 'HEXANA_MCP_MOCK_REQUEST_LOG must be set').to.be.a('string').and.not.empty;

        const browser = VSBrowser.instance;
        if (!browser) {
            this.skip();
            return;
        }
        driver = browser.driver;

        await driver.sleep(5_000);
        const editorView = new EditorView();
        const titles = await editorView.getOpenEditorTitles();
        expect(
            titles.some((title) => title.includes('test-exports.wasm')),
            'test-exports.wasm should be opened by the UI test runner',
        ).to.equal(true);
        await editorView.openEditor('test-exports.wasm');
        await driver.sleep(2_000);
    });

    it('keeps the extension usable and leaves no partial MCP server after a failed startup download', async function () {
        const serverDir = path.join(storageDir!, 'server');
        const versionMarkerPath = path.join(storageDir!, 'server.version');
        const lockPath = path.join(storageDir!, '.download.lock');

        await executeCommandWithRetry('Hexana MCP: Update Server');
        await waitForFileContaining(requestLogPath!, 'DOWNLOAD_STATUS 500 /download/');
        await waitForNoFile(lockPath, 30_000);
        await delay(1_000);

        expect(fs.existsSync(serverDir), 'server directory should not be installed after failed download').to.equal(false);
        expect(fs.existsSync(versionMarkerPath), 'version marker should not be written after failed download')
            .to.equal(false);
    });
});

async function executeCommandWithRetry(command: string): Promise<void> {
    let lastError: unknown;
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            await new Workbench().executeCommand(command);
            return;
        } catch (error) {
            lastError = error;
            await VSBrowser.instance.driver.actions().sendKeys(Key.ESCAPE).perform();
            await delay(1_000);
        }
    }
    throw lastError;
}

async function waitForFileContaining(filePath: string, pattern: string): Promise<void> {
    const deadline = Date.now() + 30_000;
    while (Date.now() < deadline) {
        if (fs.existsSync(filePath) && fs.readFileSync(filePath, 'utf8').includes(pattern)) {
            return;
        }
        await delay(500);
    }
    throw new Error(`Timed out waiting for ${filePath} to contain ${pattern}`);
}

async function waitForNoFile(filePath: string, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (!fs.existsSync(filePath)) {
            return;
        }
        await delay(500);
    }
    throw new Error(`Timed out waiting for ${filePath} to disappear`);
}

function delay(milliseconds: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
