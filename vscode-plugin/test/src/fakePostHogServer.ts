import * as http from 'http';
import * as zlib from 'zlib';

export interface FakePostHogEvent {
    name: string;
    distinctId?: string;
    properties: Record<string, unknown>;
}

export class FakePostHogServer {
    private readonly requests: RecordedRequest[] = [];

    private constructor(
        private readonly server: http.Server,
        readonly port: number,
    ) {}

    get url(): string {
        return `http://127.0.0.1:${this.port}`;
    }

    get assertUrl(): string {
        return this.url;
    }

    get env(): NodeJS.ProcessEnv {
        return {
            HEXANA_POSTHOG_HOST: this.url,
            HEXANA_POSTHOG_FLUSH_AT: '1',
            HEXANA_POSTHOG_FLUSH_INTERVAL_MS: '1000',
            HEXANA_FAKE_POSTHOG_ASSERT_URL: this.assertUrl,
        };
    }

    reset(): void {
        this.requests.length = 0;
    }

    events(): FakePostHogEvent[] {
        return this.requests.flatMap(request => requestToEvents(request));
    }

    close(): Promise<void> {
        return new Promise((resolve, reject) => {
            this.server.close(error => {
                if (error) {
                    reject(error);
                } else {
                    resolve();
                }
            });
        });
    }

    private async handle(request: http.IncomingMessage, response: http.ServerResponse): Promise<void> {
        try {
            const requestUrl = new URL(request.url ?? '/', this.url);
            if (requestUrl.pathname === '/__events') {
                this.respondJson(response, 200, { events: this.events() });
                return;
            }
            if (requestUrl.pathname === '/__reset') {
                this.reset();
                this.respondJson(response, 200, { status: 'ok' });
                return;
            }
            if (requestUrl.pathname === '/__wait') {
                const eventName = requestUrl.searchParams.get('event');
                if (!eventName) {
                    this.respondJson(response, 400, { error: 'Missing event query parameter' });
                    return;
                }
                const timeoutMs = positiveInt(requestUrl.searchParams.get('timeoutMs')) ?? 30_000;
                const event = await this.waitForEvent(eventName, timeoutMs);
                if (event) {
                    this.respondJson(response, 200, { event });
                } else {
                    this.respondJson(response, 404, {
                        error: `Timed out waiting for event ${eventName}`,
                        events: this.events(),
                    });
                }
                return;
            }

            const body = decodeRequestBody(await readBody(request), request.headers['content-encoding']);
            this.requests.push({
                method: request.method ?? 'GET',
                path: requestUrl.pathname,
                body,
            });
            this.respondJson(response, 200, { status: 'ok' });
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            this.respondJson(response, 500, { error: message });
        }
    }

    private waitForEvent(name: string, timeoutMs: number): Promise<FakePostHogEvent | undefined> {
        const deadline = Date.now() + timeoutMs;
        return new Promise(resolve => {
            const poll = () => {
                const event = this.events().find(candidate => candidate.name === name);
                if (event || Date.now() >= deadline) {
                    resolve(event);
                    return;
                }
                setTimeout(poll, 50);
            };
            poll();
        });
    }

    private respondJson(response: http.ServerResponse, statusCode: number, payload: unknown): void {
        const body = Buffer.from(JSON.stringify(payload), 'utf8');
        response.statusCode = statusCode;
        response.setHeader('Content-Type', 'application/json');
        response.setHeader('Content-Length', String(body.length));
        response.end(body);
    }

    static start(): Promise<FakePostHogServer> {
        return new Promise((resolve, reject) => {
            let fake: FakePostHogServer | undefined;
            const server = http.createServer((request, response) => {
                void fake?.handle(request, response);
            });
            server.once('error', reject);
            server.listen(0, '127.0.0.1', () => {
                const address = server.address();
                if (!address || typeof address === 'string') {
                    reject(new Error('Fake PostHog server did not bind to a TCP port'));
                    return;
                }
                fake = new FakePostHogServer(server, address.port);
                server.off('error', reject);
                resolve(fake);
            });
        });
    }
}

interface RecordedRequest {
    method: string;
    path: string;
    body: string;
}

function requestToEvents(request: RecordedRequest): FakePostHogEvent[] {
    if (!request.body.trim()) {
        return [];
    }
    const payload = JSON.parse(request.body);
    const batch = Array.isArray(payload.batch) ? payload.batch : undefined;
    if (batch) {
        return batch.map(toEvent).filter(isDefined);
    }
    const events = Array.isArray(payload.events) ? payload.events : undefined;
    if (events) {
        return events.map(toEvent).filter(isDefined);
    }
    const event = toEvent(payload);
    return event ? [event] : [];
}

function toEvent(payload: any): FakePostHogEvent | undefined {
    if (!payload || typeof payload.event !== 'string') {
        return undefined;
    }
    return {
        name: payload.event,
        distinctId: payload.distinct_id ?? payload.distinctId,
        properties: payload.properties ?? {},
    };
}

function readBody(request: http.IncomingMessage): Promise<Buffer> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        request.on('data', chunk => chunks.push(Buffer.from(chunk)));
        request.on('end', () => resolve(Buffer.concat(chunks)));
        request.on('error', reject);
    });
}

function decodeRequestBody(body: Buffer, contentEncoding: string | string[] | undefined): string {
    if (isGzipEncoded(body, contentEncoding)) {
        return zlib.gunzipSync(body).toString('utf8');
    }
    return body.toString('utf8');
}

function isGzipEncoded(body: Buffer, contentEncoding: string | string[] | undefined): boolean {
    const encodings = Array.isArray(contentEncoding) ? contentEncoding : [contentEncoding];
    return encodings.some(encoding => encoding?.toLowerCase() === 'gzip') ||
        body.length >= 2 && body[0] === 0x1f && body[1] === 0x8b;
}

function positiveInt(value: string | null): number | undefined {
    if (!value) {
        return undefined;
    }
    const parsed = Number.parseInt(value, 10);
    return parsed > 0 ? parsed : undefined;
}

function isDefined<T>(value: T | undefined): value is T {
    return value !== undefined;
}
