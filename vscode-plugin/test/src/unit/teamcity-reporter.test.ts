import { expect } from 'chai';
import { Runner, Suite, Test } from 'mocha';
import TeamCityReporter = require('../teamcity-reporter');

class FailureRunner extends Runner {
    recordFailure(test: Test, error: Error): void {
        this.fail(test, error);
    }
}

describe('TeamCity reporter failure handling', () => {
    const originalIgnoreTestFailures = process.env.HEXANA_TEAMCITY_IGNORE_TEST_FAILURES;

    beforeEach(() => {
        delete process.env.HEXANA_TEAMCITY_IGNORE_TEST_FAILURES;
    });

    after(() => {
        if (originalIgnoreTestFailures === undefined) {
            delete process.env.HEXANA_TEAMCITY_IGNORE_TEST_FAILURES;
        } else {
            process.env.HEXANA_TEAMCITY_IGNORE_TEST_FAILURES = originalIgnoreTestFailures;
        }
    });

    it('keeps multiple failures counted until the run ends and reports each one', () => {
        process.env.HEXANA_TEAMCITY_IGNORE_TEST_FAILURES = 'true';
        const { runner, output } = recordFailures();

        expect(runner.failures).to.equal(2);
        expect(output.match(/##teamcity\[testFailed /g)).to.have.length(2);
        expect(output).to.include("message='first failure'");
        expect(output).to.include("message='second failure'");
    });

    it('clears the final Mocha failure count at end when explicitly requested', () => {
        process.env.HEXANA_TEAMCITY_IGNORE_TEST_FAILURES = 'true';
        const { runner } = recordFailures(true);

        expect(runner.failures).to.equal(0);
    });

    it('preserves the final Mocha failure count at end by default', () => {
        const { runner } = recordFailures(true);

        expect(runner.failures).to.equal(2);
    });
});

function recordFailures(endRun = false): { runner: FailureRunner; output: string } {
    const suite = new Suite('reporter suite');
    const firstTest = new Test('reports the first failure', () => undefined);
    const secondTest = new Test('reports the second failure', () => undefined);
    suite.addTest(firstTest);
    suite.addTest(secondTest);
    const runner = new FailureRunner(suite);

    const output = captureStdout(() => {
        new TeamCityReporter(runner);
        runner.recordFailure(firstTest, new Error('first failure'));
        runner.recordFailure(secondTest, new Error('second failure'));
        if (endRun) {
            runner.emit('end');
        }
    });

    return { runner, output };
}

function captureStdout(action: () => void): string {
    const originalWrite = process.stdout.write;
    let output = '';
    process.stdout.write = ((chunk: string | Uint8Array) => {
        output += chunk.toString();
        return true;
    }) as typeof process.stdout.write;

    try {
        action();
    } finally {
        process.stdout.write = originalWrite;
    }

    return output;
}
