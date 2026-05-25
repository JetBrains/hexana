import * as path from 'path';
import { Workbench, EditorView, WebView, By, VSBrowser, WebDriver } from 'vscode-extension-tester';
import { expect } from 'chai';
import * as fs from "fs";
import { waitForFakePostHogEvent } from '../fakePostHogAssertions';
import { submitQuickInputPath } from './test-utils';

/**
 * Basic acceptance integration tests for the Hexana VS Code extension.
 *
 * These tests rely on the extension `.vsix` being pre-installed by the
 * `extest install-vsix` CLI step and the `.wasm` fixture being opened at
 * launch via the `--open_resource` flag in the test runner script.
 *
 * They verify:
 *   1. The custom hex editor opens successfully.
 *   2. The hex viewer is rendered with byte content.
 *   3. Clicking a byte in the hex view updates the selection status bar.
 *   4. The Exports tab can be selected and shows export rows.
 */
describe('Hexana Wasm Editor – Acceptance Tests', function () {
    this.timeout(180_000);

    let dir = process.cwd();
    // in teamcity the workdir is different
    const WASM_FIXTURE =
        dir.indexOf("ui-test") >= 0
            ? path.resolve(dir, '..', '..', 'fixtures', 'test-exports.wasm')
            : path.resolve(dir, 'fixtures', 'test-exports.wasm');
    let driver: WebDriver;

    before(async function () {
        this.timeout(90_000);

        const browser = VSBrowser.instance;
        if (!browser) {
            this.skip();
            return;
        }
        driver = browser.driver;

        // Allow the workbench and extension to fully activate
        await driver.sleep(5000);
    });

    describe('Opening a .wasm file', function () {
        let webview: WebView;

        before(async function () {
            this.timeout(120_000);

            // The test runner opens the .wasm fixture via --open_resource.
            // Check whether the file is already open as a tab; if not, open
            // it through the command palette.
            const editorView = new EditorView();
            const titles = await editorView.getOpenEditorTitles();
            const wasmOpen = titles.some(t => t.includes('test-exports.wasm'));

            if (!wasmOpen) {
                // Fallback: open the fixture via the command palette.
                // The test settings set "window.dialogStyle": "custom" so the
                // "File: Open File…" command shows a VS Code quick-input
                // dialog instead of a native OS dialog.
                const workbench = new Workbench();
                await workbench.executeCommand('File: Open File...');
                await driver.sleep(1000);

                await submitQuickInputPath(driver, WASM_FIXTURE);

                // Wait for the custom editor to appear
                await driver.sleep(8000);
            } else {
                // Make sure the wasm tab is active
                await editorView.openEditor('test-exports.wasm');
                await driver.sleep(2000);
            }

            // capture a screenshot here
            await driver.takeScreenshot().then((png) => {
                fs.mkdirSync('collected-screenshots', { recursive: true });
                fs.writeFileSync('collected-screenshots/screenshot.png', png, 'base64');
            });

            // Wait for the webview editor instance to be present before switching
            await driver.wait(async () => {
                const frames = await driver.findElements(By.css('.editor-instance'));
                return frames.length > 0;
            }, 30_000, 'Webview editor instance did not appear within 30 seconds');

            // Switch into the webview iframe
            webview = new WebView();
            await webview.switchToFrame();

            // Wait for the Compose app to render (the .hexana-editor container)
            await driver.wait(async () => {
                const editors = await driver.findElements(By.css('.hexana-editor'));
                return editors.length > 0;
            }, 60_000, 'Hexana editor did not render within 60 seconds');
        });

        after(async function () {
            this.timeout(30_000);
            try {
                await webview.switchBack();
            } catch {
                // already switched back or webview gone
            }
            await new EditorView().closeAllEditors();
        });

        it('should render the hex viewer with byte content', async function () {
            // The hex viewer should contain rows of hex bytes
            const hexRows = await driver.findElements(By.css('.hex-row'));
            expect(hexRows.length).to.be.greaterThan(0, 'Expected at least one hex row');

            // Each row should have hex byte spans
            const hexBytes = await driver.findElements(By.css('.hex-byte'));
            expect(hexBytes.length).to.be.greaterThan(0, 'Expected hex byte elements');

            // The first byte of a valid .wasm file is 0x00 (the \0 of "\0asm")
            const firstByte = await hexBytes[0].getText();
            expect(firstByte.trim()).to.equal('00', 'First byte of .wasm should be 00');
        });

        it('should send binary_opened analytics event', async function () {
            const assertUrl = process.env.HEXANA_FAKE_POSTHOG_ASSERT_URL;
            if (!assertUrl) {
                this.skip();
                return;
            }

            const event = await waitForFakePostHogEvent(assertUrl, 'binary_opened');
            expect(event.distinctId, 'Expected analytics event to include distinct ID').to.be.a('string').and.not.empty;
            expect(event.properties.format).to.equal('wasm');
            expect(event.properties.surface).to.equal('vscode_extension');
            expect(Number(event.properties.file_size_bytes)).to.be.greaterThan(0);
            expect(event.properties.ide_type).to.be.a('string').and.not.empty;
            expect(event.properties.ide_build).to.be.a('string').and.not.empty;
        });

        it('should show the tab bar with expected tabs', async function () {
            const tabs = await driver.findElements(By.css('.hexana-tab'));
            expect(tabs.length).to.be.greaterThan(0, 'Expected at least one tab');

            // Collect tab labels
            const tabLabels: string[] = [];
            for (const tab of tabs) {
                tabLabels.push(await tab.getText());
            }

            // Verify essential tabs for this fixture are present (tab labels may include sort indicators).
            // Table tabs for empty sections are not rendered.
            const hasTab = (name: string) => tabLabels.some(l => l.startsWith(name));
            expect(hasTab('Sections'), 'Sections tab missing').to.be.true;
            expect(hasTab('Exports'), 'Exports tab missing').to.be.true;
        });

        it('should display the selection status bar', async function () {
            const statusBars = await driver.findElements(By.css('.hexana-status-bar'));
            expect(statusBars.length).to.equal(1, 'Expected one status bar');

            // Initially should show "Click a byte to begin" prompt
            const text = await statusBars[0].getText();
            expect(text.toLowerCase()).to.include('click');
        });

        it('should update status bar when clicking a hex byte', async function () {
            // Click the first hex byte
            const hexBytes = await driver.findElements(By.css('.hex-byte'));
            expect(hexBytes.length).to.be.greaterThan(0);
            await hexBytes[0].click();

            // Wait a moment for Compose recomposition
            await driver.sleep(500);

            // The status bar should now show offset and byte info
            // (e.g. "Offset: 0x00000000" and "Byte: 0 / 50")
            const statusBar = await driver.findElement(By.css('.hexana-status-bar'));
            const text = await statusBar.getText();
            expect(text.toLowerCase()).to.include('offset');
            expect(text.toLowerCase()).to.include('byte');
        });

        it('should switch to the Exports tab and show export data', async function () {
            // Find the Exports tab and click it
            const tabs = await driver.findElements(By.css('.hexana-tab'));
            let exportsTab = null;
            for (const tab of tabs) {
                const label = await tab.getText();
                if (label.startsWith('Exports')) {
                    exportsTab = tab;
                    break;
                }
            }
            expect(exportsTab, 'Exports tab not found').to.not.be.null;
            await exportsTab!.click();

            // Wait for the tab content to render
            await driver.sleep(1000);

            // Verify the Exports tab is now active
            const activeClass = await exportsTab!.getAttribute('class');
            expect(activeClass).to.include('hexana-tab-active');

            // The exports table should be visible with rows
            const tables = await driver.findElements(By.css('.hexana-panel-table'));
            expect(tables.length).to.be.greaterThan(0, 'Expected a panel table in Exports tab');

            // Check for table rows (our test .wasm has 2 exports: memory + add)
            const rows = await driver.findElements(By.css('.hexana-panel-row'));
            expect(rows.length).to.be.greaterThanOrEqual(2,
                'Expected at least 2 export rows (memory and add)');

            // Verify export names are present in the table text
            const tableText = await tables[0].getText();
            expect(tableText).to.include('memory');
            expect(tableText).to.include('add');
        });

        it('should highlight hex bytes when clicking an export row', async function () {
            // Ensure we're on the Exports tab
            const tabs = await driver.findElements(By.css('.hexana-tab'));
            for (const tab of tabs) {
                if ((await tab.getText()).startsWith('Exports')) {
                    await tab.click();
                    break;
                }
            }
            await driver.sleep(500);

            // Click the first export row
            const rows = await driver.findElements(By.css('.hexana-panel-row'));
            expect(rows.length).to.be.greaterThan(0);
            await rows[0].click();

            // Wait for selection to update
            await driver.sleep(500);

            // The status bar should now show a selection range
            // (e.g. "Selected: N bytes" and "Range: 0xNN – 0xNN")
            const statusBar = await driver.findElement(By.css('.hexana-status-bar'));
            const text = await statusBar.getText();
            expect(text.toLowerCase()).to.include('selected');
        });
    });
});
