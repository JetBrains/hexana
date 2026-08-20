import { expect } from 'chai';
import { waitForLaunchOpenedFixture } from '../ui-test/test-utils';

describe('launch-opened fixture readiness', () => {
    it('does not invoke fallback when the launch-opened frame is ready', async () => {
        let openEditorCalls = 0;
        let readinessCalls = 0;

        await waitForLaunchOpenedFixture(
            async () => { readinessCalls += 1; },
            async () => { openEditorCalls += 1; },
            () => false,
        );

        expect(openEditorCalls).to.equal(0);
        expect(readinessCalls).to.equal(1);
    });

    it('opens the fixture and retries after the expected readiness timeout', async () => {
        class ExpectedTimeout extends Error {}
        let openEditorCalls = 0;
        let readinessCalls = 0;

        await waitForLaunchOpenedFixture(
            async () => {
                readinessCalls += 1;
                if (readinessCalls === 1) {
                    throw new ExpectedTimeout();
                }
            },
            async () => { openEditorCalls += 1; },
            (error) => error instanceof ExpectedTimeout,
        );

        expect(openEditorCalls).to.equal(1);
        expect(readinessCalls).to.equal(2);
    });

    it('propagates an unexpected readiness error without invoking fallback', async () => {
        let openEditorCalls = 0;
        let failure: unknown;
        try {
            await waitForLaunchOpenedFixture(
                async () => { throw new Error('unexpected readiness failure'); },
                async () => { openEditorCalls += 1; },
                () => false,
            );
        } catch (error) {
            failure = error;
        }

        expect(failure).to.be.an('error').with.property(
            'message',
            'unexpected readiness failure',
        );
        expect(openEditorCalls).to.equal(0);
    });
});
