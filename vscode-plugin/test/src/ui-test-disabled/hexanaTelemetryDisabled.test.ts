import { waitForNoFakePostHogEvents } from '../fakePostHogAssertions';

describe('Hexana VS Code telemetry disabled', function () {
    this.timeout(90_000);

    it('should not send analytics events when Hexana statistics are disabled', async function () {
        const assertUrl = process.env.HEXANA_FAKE_POSTHOG_ASSERT_URL;
        if (!assertUrl) {
            this.skip();
            return;
        }

        await waitForNoFakePostHogEvents(assertUrl);
    });
});
