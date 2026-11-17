import * as http from 'http';

export interface FakePostHogEvent {
    name: string;
    distinctId?: string;
    properties: Record<string, unknown>;
}

export async function waitForFakePostHogEvent(assertUrl: string, eventName: string): Promise<FakePostHogEvent> {
    const url = new URL('/__wait', assertUrl);
    url.searchParams.set('event', eventName);
    url.searchParams.set('timeoutMs', '60000');

    const response = await getJson<{ event?: FakePostHogEvent; error?: string; events?: FakePostHogEvent[] }>(
        url,
        statusCode => isSuccessStatus(statusCode) || statusCode === 404,
    );
    if (!response.event) {
        const recorded = (response.events ?? []).map(event => event.name).join(', ') || '<none>';
        throw new Error(response.error || `Expected ${eventName}, recorded events: ${recorded}`);
    }
    return response.event;
}

export async function waitForNoFakePostHogEvents(assertUrl: string, timeoutMs = 3_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (true) {
        const response = await getJson<{ events: FakePostHogEvent[] }>(new URL('/__events', assertUrl));
        if (response.events.length > 0) {
            throw new Error(
                `Expected no PostHog events, recorded: ${response.events.map(event => event.name).join(', ')}`
            );
        }
        const remainingMs = deadline - Date.now();
        if (remainingMs <= 0) {
            return;
        }
        await delay(Math.min(100, remainingMs));
    }
}

function getJson<T>(
    url: URL,
    acceptsStatus: (statusCode: number) => boolean = isSuccessStatus,
): Promise<T> {
    return new Promise((resolve, reject) => {
        const request = http.get(url, response => {
            const chunks: Buffer[] = [];
            response.on('data', chunk => chunks.push(Buffer.from(chunk)));
            response.on('error', reject);
            response.on('end', () => {
                const body = Buffer.concat(chunks).toString('utf8');
                if (response.statusCode != null && acceptsStatus(response.statusCode)) {
                    const payload = body ? JSON.parse(body) : {};
                    resolve(payload as T);
                } else {
                    reject(new Error(body || `HTTP ${response.statusCode ?? 'unknown'}`));
                }
            });
        });
        request.on('error', reject);
    });
}

function isSuccessStatus(statusCode: number): boolean {
    return statusCode >= 200 && statusCode < 400;
}

function delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}
