import { By, Key } from 'vscode-extension-tester';
import { WebDriver } from 'selenium-webdriver';

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
