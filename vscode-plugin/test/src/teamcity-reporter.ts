import type { Runner, Suite, Test } from 'mocha';

type MochaError = Error & {
    actual?: unknown;
    expected?: unknown;
    showDiff?: boolean;
};

const rootSuiteName = process.env.HEXANA_VSCODE_TEST_RUN || 'VSCode UI Tests';

class TeamCityReporter {
    private readonly openSuites: string[] = [];
    private readonly activeTests = new Set<string>();

    constructor(runner: Runner) {
        runner.on('suite', suite => this.startSuite(suite));
        runner.on('suite end', suite => this.finishSuite(suite));
        runner.on('test', test => this.startTest(test));
        runner.on('pass', test => this.finishTest(test));
        runner.on('fail', (test, error) => this.failTest(test, error as MochaError));
        runner.on('pending', test => this.ignoreTest(test));
        runner.once('end', () => this.closeOpenSuites());

        this.openSuite(rootSuiteName);
    }

    private startSuite(suite: Suite): void {
        if (suite.root || !suite.title) {
            return;
        }
        this.openSuite(suite.title);
    }

    private finishSuite(suite: Suite): void {
        if (suite.root || !suite.title) {
            return;
        }
        this.closeSuite(suite.title);
    }

    private startTest(test: Test): void {
        const testName = fullTestName(test);
        this.activeTests.add(testName);
        message('testStarted', {
            name: testName,
            captureStandardOutput: 'true',
        });
    }

    private finishTest(test: Test): void {
        const testName = fullTestName(test);
        const duration = typeof test.duration === 'number' ? Math.max(0, Math.round(test.duration)) : undefined;
        message('testFinished', {
            name: testName,
            duration: duration?.toString(),
        });
        this.activeTests.delete(testName);
    }

    private failTest(test: Test, error: MochaError): void {
        const testName = fullTestName(test);
        if (!this.activeTests.has(testName)) {
            this.startTest(test);
        }

        const properties: Record<string, string | undefined> = {
            name: testName,
            message: error.message || String(error),
            details: error.stack || error.message || String(error),
        };
        if (error.showDiff && error.actual !== undefined && error.expected !== undefined) {
            properties.type = 'comparisonFailure';
            properties.actual = stringifyValue(error.actual);
            properties.expected = stringifyValue(error.expected);
        }

        message('testFailed', properties);
        this.finishTest(test);
    }

    private ignoreTest(test: Test): void {
        message('testIgnored', {
            name: fullTestName(test),
            message: 'Test skipped by Mocha',
        });
    }

    private openSuite(name: string): void {
        this.openSuites.push(name);
        message('testSuiteStarted', { name });
    }

    private closeSuite(name: string): void {
        const openName = this.openSuites.pop();
        message('testSuiteFinished', { name: openName || name });
    }

    private closeOpenSuites(): void {
        while (this.openSuites.length > 0) {
            this.closeSuite(this.openSuites[this.openSuites.length - 1]);
        }
    }
}

function fullTestName(test: Test): string {
    const titlePath = typeof test.titlePath === 'function' ? test.titlePath() : [test.fullTitle()];
    return titlePath.filter(Boolean).join(' / ');
}

function message(name: string, properties: Record<string, string | undefined>): void {
    const attributes = Object.entries(properties)
        .filter((entry): entry is [string, string] => entry[1] !== undefined)
        .map(([key, value]) => `${key}='${escapeTeamCity(value)}'`)
        .join(' ');
    process.stdout.write(`##teamcity[${name}${attributes ? ` ${attributes}` : ''}]\n`);
}

function escapeTeamCity(value: string): string {
    return value
        .replace(/\|/g, '||')
        .replace(/'/g, "|'")
        .replace(/\n/g, '|n')
        .replace(/\r/g, '|r')
        .replace(/\[/g, '|[')
        .replace(/]/g, '|]');
}

function stringifyValue(value: unknown): string {
    return typeof value === 'string' ? value : JSON.stringify(value);
}

export = TeamCityReporter;
