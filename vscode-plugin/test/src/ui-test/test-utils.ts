import { By, EditorView, Key, WebView, Workbench } from 'vscode-extension-tester';
import { error as webdriverError, Locator, WebDriver } from 'selenium-webdriver';
import * as fs from 'fs';

/**
 * Compare two existing paths by filesystem identity, following symbolic links.
 * Missing paths or unusable identity metadata throw; there is no path-casing fallback.
 */
export function isSameFile(actual: string, expected: string): boolean {
    // BigInt preserves file IDs that exceed Number.MAX_SAFE_INTEGER (including Windows file IDs).
    const actualStat = fs.statSync(actual, { bigint: true });
    const expectedStat = fs.statSync(expected, { bigint: true });
    if ([actualStat, expectedStat].some(stat => stat.dev === 0n || stat.ino === 0n)) {
        throw new Error('Filesystem did not provide a usable file identity');
    }
    return actualStat.dev === expectedStat.dev && actualStat.ino === expectedStat.ino;
}

/** Retry only palette interactions, not debug assertions or commands with non-idempotent effects. */
export async function retryCommandPaletteInteraction(
    command: string,
    execute: () => Promise<void>,
    reset: () => Promise<void>,
): Promise<void> {
    for (let attempt = 1; attempt <= 3; attempt++) {
        try {
            await execute();
            return;
        } catch (error) {
            const transient = error instanceof webdriverError.TimeoutError
                || error instanceof webdriverError.ElementNotInteractableError
                || error instanceof webdriverError.StaleElementReferenceError;
            if (!transient || attempt === 3) {
                throw error;
            }
            console.log(`[ui-test] Retrying palette command "${command}" (${attempt}/3 failed): ${error}`);
        }
        await reset();
    }
}

/** Only use for idempotent view commands: a lost palette may require replaying the command. */
export async function executeViewCommandFromPalette(driver: WebDriver, command: string): Promise<void> {
    await retryCommandPaletteInteraction(command, async () => {
        const prompt = await new Workbench().openCommandPrompt();
        await prompt.setText(`>${command}`);
        // Never confirm an empty/stale result list: require the exact command first.
        await driver.wait(async () => {
            for (const pick of await prompt.getQuickPicks()) {
                if (await pick.getLabel() === command) {
                    await pick.select();
                    return true;
                }
            }
            return false;
        }, 5_000, `Command palette did not offer "${command}"`);
        await driver.wait(async () => !(await prompt.isDisplayed()), 5_000,
            `Command palette did not close after "${command}"`);
    }, async () => {
        await driver.switchTo().defaultContent();
        await driver.actions().clear();
        await driver.actions().sendKeys(Key.ESCAPE).perform();
        await driver.sleep(250);
    });
}

/**
 * Types a file path into the VS Code quick-input dialog by pasting from
 * the clipboard instead of simulating individual keystrokes.
 *
 * Typing character-by-character can trigger VS Code's "Folder path"
 * tooltip/suggestion overlay which intercepts Enter.  Pasting the full
 * path at once avoids the overlay entirely.
 *
 * After pasting, the function first tries pressing Enter.  If the dialog
 * is still visible (e.g. because the input lost focus), it falls back to
 * clicking the OK (✓) button.
 *
 * **Precondition:** the quick-input dialog must already be open.
 */
export async function submitQuickInputPath(driver: WebDriver, filePath: string): Promise<void> {
    const input = await driver.findElement(By.css('.quick-input-widget input.input'));
    await input.clear();

    // Set the input value via JavaScript (clipboard-style paste) to avoid
    // character-by-character typing that triggers the folder-path tooltip.
    await driver.executeScript(
        `var el = arguments[0], value = arguments[1];
         var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
         setter.call(el, value);
         el.dispatchEvent(new Event('input', { bubbles: true }));`,
        input,
        filePath,
    );

    // Try Enter first — it's the fastest path when the input has focus.
    await input.sendKeys(Key.ENTER);
    await driver.sleep(500);

    // If the dialog is still open, Enter didn't work — click the OK button.
    let stillOpen = false;
    try {
        const el = await driver.findElement(By.css('.quick-input-widget input.input'));
        stillOpen = await el.isDisplayed();
    } catch {
        stillOpen = false;
    }

    if (stillOpen) {
        const okButton = await driver.findElement(
            By.css('.quick-input-action .codicon-check, .quick-input-widget .quick-input-action a.action-label'),
        );
        await okButton.click();
    }
}

export async function waitForLaunchOpenedFixture(
    waitForWebviewEditor: () => Promise<void>,
    openFixture: () => Promise<void>,
    isExpectedReadinessTimeout: (error: unknown) => boolean,
): Promise<void> {
    try {
        await waitForWebviewEditor();
    } catch (error) {
        if (!isExpectedReadinessTimeout(error)) {
            throw error;
        }
        await openFixture();
        await waitForWebviewEditor();
    }
}

/**
 * Returns the WebDriver context to the VS Code workbench after a test has
 * switched into a webview iframe.
 *
 * `WebView.switchBack()` restores the context by switching to the window
 * handle it captured in `switchToFrame()`.  That call can fail on its own,
 * and ignoring the failure leaves the driver *inside* the iframe.  Every
 * later workbench lookup then fails too, including the `new EditorView()`
 * constructor, which eagerly resolves `.monaco-workbench` -- an element
 * that only exists in the top-level document.
 *
 * Falling back to `switchTo().defaultContent()` puts the driver back at the
 * top-level document, which is all the workbench page objects need.
 */
export async function returnToWorkbench(driver: WebDriver, webview: WebView): Promise<void> {
    try {
        await webview.switchBack();
        return;
    } catch {
        // The captured window handle was unusable -- reset the frame instead.
    }

    try {
        await driver.switchTo().defaultContent();
    } catch {
        // Nothing further can be done; callers treat this as best-effort.
    }
}

/**
 * Closes every open editor without clicking any tab close button.
 *
 * `EditorView.closeAllEditors()` closes tabs by clicking each tab's
 * `.codicon-close` action.  VS Code lays that button out on top of the
 * tab's `.label-name` element, so whenever the label covers the button's
 * click point Selenium refuses the click with
 * `ElementClickInterceptedError`:
 *
 *     element click intercepted: Element <a class="action-label codicon
 *     codicon-close" ...> is not clickable at point (208, 51). Other
 *     element would receive the click: <a class="label-name">...</a>
 *
 * Tab and label widths depend on the platform's font metrics, which is why
 * the same tab is clickable on one OS and intercepted on another.  Worse,
 * `EditorGroup.closeAllEditors()` retries in an unbounded loop, so a tab it
 * cannot click either throws or eats the caller's whole timeout.
 *
 * The `View: Close All Editors` command reaches the same result through the
 * command palette, which does no hit testing at all.  The click-based path
 * is kept as a fallback, and neither path is allowed to throw: callers use
 * this for teardown, where failing the suite after every assertion has
 * already passed adds no signal.
 */
export async function closeAllEditorsQuietly(driver: WebDriver, timeoutMs = 15_000): Promise<void> {
    try {
        await new Workbench().executeCommand('View: Close All Editors');
        await driver.wait(
            async () => (await new EditorView().getOpenEditorTitles()).length === 0,
            timeoutMs,
        );
        return;
    } catch {
        // The palette route did not settle -- fall back to clicking tabs.
    }

    try {
        await new EditorView().closeAllEditors();
    } catch {
        // Best effort: teardown must never fail the suite.
    }
}

/** VS Code's overlay for custom-style modal dialogs. */
const modalBlock = By.css('.monaco-dialog-modal-block');

/**
 * Dismisses a modal dialog that is covering the workbench, if one is open.
 *
 * With `"window.dialogStyle": "custom"` (which these tests set) VS Code
 * renders modal dialogs inside a `.monaco-dialog-modal-block` overlay that
 * spans the entire window.  While it is up, every click on the editor area
 * is refused -- including clicks on elements inside the webview, which
 * Selenium reports against the iframe:
 *
 *     Element <iframe class="webview ready" ...> is not clickable at point
 *     (454, 155). Other element would receive the click:
 *     <div class="monaco-dialog-modal-block dimmed">...</div>
 *
 * Hexana never raises these dialogs.  A bundled VS Code feature can put one
 * up at any moment -- in practice the GitHub Copilot sign-in prompt, which
 * appears once a reused `test-resources` user-data directory has enough
 * state -- and it then fails whichever test clicks next.  Escape closes VS
 * Code dialogs and involves no hit testing.
 *
 * Every dialog is logged before it is dismissed, so an unexpected one shows
 * up in the test output rather than vanishing silently.
 *
 * **Precondition:** the driver must be at the top-level document rather than
 * inside a webview iframe -- see {@link returnToWorkbench}.
 *
 * @returns whether a dialog was dismissed
 */
export async function dismissBlockingModal(driver: WebDriver, attempts = 3): Promise<boolean> {
    let dismissed = false;

    for (let attempt = 0; attempt < attempts; attempt++) {
        const summary = await blockingModalSummary(driver);
        if (summary === undefined) {
            return dismissed;
        }

        console.log(`[ui-test] Dismissing modal dialog blocking the workbench: ${summary}`);
        if (await cancelBlockingDialog(driver)) {
            dismissed = true;
        }
        await driver.sleep(500);
    }

    if ((await blockingModalSummary(driver)) !== undefined) {
        console.warn('[ui-test] A modal dialog is still blocking the workbench; later clicks will fail');
    }
    return dismissed;
}

/**
 * Button labels that are safe to click: each one cancels the dialog rather
 * than carrying out what it asked.
 *
 * Committing labels are deliberately absent.  A save prompt over a fixture
 * file must never be answered with `Save`, and a sign-in prompt must never
 * be answered with `Continue`, so the guard only ever declines.
 */
const cancelDialogButtonLabels = [
    'cancel',
    "don't save",
    'do not save',
    'no',
    'close',
    'not now',
    'later',
    'skip',
    'dismiss',
];

/**
 * Cancels the dialog currently covering the workbench.
 *
 * The dialog box itself renders above the blocking overlay, so its own
 * controls stay clickable while everything behind them is intercepted.
 * Preference order:
 *
 *   1. the dialog's close (✕) control, which always just cancels;
 *   2. a button whose label declines -- see {@link cancelDialogButtonLabels};
 *   3. Escape, for overlays carrying neither.
 *
 * Escape is last because VS Code's message dialogs ignore synthesised
 * Escape from the Actions API; the ✕ control does close them.
 *
 * @returns whether an action was performed
 */
async function cancelBlockingDialog(driver: WebDriver): Promise<boolean> {
    const closeControls = [
        '.monaco-dialog-modal-block a[aria-label="Close Dialog"]',
        '.monaco-dialog-modal-block .codicon-dialog-close',
        '.monaco-dialog-modal-block .codicon-widget-close',
        '.monaco-dialog-modal-block .codicon-close',
    ];
    for (const selector of closeControls) {
        if (await clickIfDisplayed(driver, By.css(selector))) {
            return true;
        }
    }

    try {
        const buttons = await driver.findElements(
            By.css('.monaco-dialog-modal-block .dialog-buttons a.monaco-button'),
        );
        for (const button of buttons) {
            const label = (await button.getText()).trim().toLowerCase();
            if (cancelDialogButtonLabels.includes(label) && (await button.isDisplayed())) {
                await button.click();
                return true;
            }
        }
    } catch {
        // Fall through to Escape.
    }

    try {
        await driver.actions().sendKeys(Key.ESCAPE).perform();
        return true;
    } catch {
        return false;
    }
}

/** Clicks the first displayed match, reporting whether anything was clicked. */
async function clickIfDisplayed(driver: WebDriver, locator: Locator): Promise<boolean> {
    try {
        for (const element of await driver.findElements(locator)) {
            if (await element.isDisplayed()) {
                await element.click();
                return true;
            }
        }
    } catch {
        // Stale or unclickable -- let the caller try the next strategy.
    }
    return false;
}

/**
 * Returns a one-line summary of the modal dialog currently covering the
 * workbench, or `undefined` when nothing is blocking it.
 */
async function blockingModalSummary(driver: WebDriver): Promise<string | undefined> {
    try {
        for (const block of await driver.findElements(modalBlock)) {
            if (await block.isDisplayed()) {
                const text = (await block.getText()).replace(/\s+/g, ' ').trim();
                return text.length > 160 ? `${text.slice(0, 160)}...` : text || '(no text)';
            }
        }
    } catch {
        // A stale or detached overlay means there is nothing left to dismiss.
    }
    return undefined;
}

/**
 * Re-enters the webview iframe for the next interaction, clearing any
 * workbench dialog that appeared since the last one.
 *
 * Call this from a `beforeEach`: a dialog that pops up between two tests
 * would otherwise fail the next test's first click.
 */
export async function reenterWebview(driver: WebDriver, webview: WebView): Promise<void> {
    await returnToWorkbench(driver, webview);
    await dismissBlockingModal(driver);
    await webview.switchToFrame();
}
