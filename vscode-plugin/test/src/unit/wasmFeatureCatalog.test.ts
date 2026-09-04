/**
 * Unit tests for wasmFeatureCatalog.ts.
 *
 * HEXANA_WASM_FEATURES_STORAGE_DIR redirects the cache to a temp directory and
 * HEXANA_WASM_FEATURES_URL points the fetch at a local server, so no test reaches
 * raw.githubusercontent.com. Assertions use exact values and there are no skip escapes.
 */
import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import { expect } from 'chai';
import { installVscodeMock, VscodeMock } from './vscode-mock';

function requireCatalog() {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    return require('../../../src/wasmFeatureCatalog') as typeof import('../../../src/wasmFeatureCatalog');
}

function clearModuleCache(): void {
    Object.keys(require.cache).forEach((key) => {
        if (key.includes('wasmFeatureCatalog')) {
            delete require.cache[key];
        }
    });
}

/** A minimal but schema-shaped catalog, so the parseability guard accepts it. */
function catalogJson(marker: string): string {
    return JSON.stringify({
        features: { gc: { description: marker, url: 'https://example.invalid/gc', phase: 5 } },
        browsers: { Chrome: { url: 'https://example.invalid', category: 'Web Browsers', features: { gc: '119' } } },
    });
}

const CATALOG_FILE = 'features.json';
const METADATA_FILE = 'features-meta.json';
const TTL_MS = 24 * 60 * 60 * 1000;

interface ServerHandle {
    url: string;
    requests: Array<{ ifNoneMatch?: string }>;
    close(): Promise<void>;
}

/** Serves `body` with `etag`, answering 304 when the request revalidates that etag. */
async function startServer(body: string, etag: string): Promise<ServerHandle> {
    const requests: Array<{ ifNoneMatch?: string }> = [];
    const server = http.createServer((request, response) => {
        const ifNoneMatch = request.headers['if-none-match'];
        requests.push({ ifNoneMatch: typeof ifNoneMatch === 'string' ? ifNoneMatch : undefined });
        if (ifNoneMatch === etag) {
            response.writeHead(304, { ETag: etag });
            response.end();
            return;
        }
        response.writeHead(200, { 'Content-Type': 'application/json', ETag: etag });
        response.end(body);
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') {
        throw new Error('server did not bind to a port');
    }
    return {
        url: `http://127.0.0.1:${address.port}/features.json`,
        requests,
        close: () => new Promise<void>((resolve) => server.close(() => resolve())),
    };
}

describe('wasmFeatureCatalog', () => {
    let mock: VscodeMock;
    let storageDir: string;
    let extensionDir: string;
    const originalEnv = { ...process.env };

    beforeEach(() => {
        mock = installVscodeMock();
        clearModuleCache();
        const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-features-'));
        storageDir = path.join(root, 'cache');
        extensionDir = path.join(root, 'extension');
        fs.mkdirSync(storageDir, { recursive: true });
        fs.mkdirSync(path.join(extensionDir, 'wasm-features'), { recursive: true });
        process.env.HEXANA_WASM_FEATURES_STORAGE_DIR = storageDir;
        // Unreachable by default, so a test that does not start a server cannot hit the real
        // endpoint; the fetch failure path is silent by design.
        process.env.HEXANA_WASM_FEATURES_URL = 'http://127.0.0.1:1/features.json';
    });

    afterEach(() => {
        process.env = { ...originalEnv };
        mock.restore();
        clearModuleCache();
    });

    function provider() {
        const { WasmFeatureCatalogProvider } = requireCatalog();
        const context = {
            globalStorageUri: { fsPath: path.join(storageDir, 'unused') },
            extensionUri: { fsPath: extensionDir },
            subscriptions: [] as Array<{ dispose(): void }>,
        };
        return new WasmFeatureCatalogProvider(context as never);
    }

    function writeBundled(json: string): void {
        fs.writeFileSync(path.join(extensionDir, 'wasm-features', CATALOG_FILE), json, 'utf8');
    }

    function writeCache(json: string, etag: string | undefined, fetchedAt: number): void {
        fs.writeFileSync(path.join(storageDir, CATALOG_FILE), json, 'utf8');
        fs.writeFileSync(path.join(storageDir, METADATA_FILE), JSON.stringify({ etag, fetchedAt }), 'utf8');
    }

    function readMetadata(): { etag?: string; fetchedAt: number } {
        return JSON.parse(fs.readFileSync(path.join(storageDir, METADATA_FILE), 'utf8'));
    }

    it('falls back to the snapshot shipped in the extension when nothing is cached', async () => {
        writeBundled(catalogJson('bundled'));
        const subject = provider();

        expect(subject.get()).to.equal(catalogJson('bundled'));
        await subject.settle();
    });

    it('returns an empty string when there is neither a cache nor a snapshot', async () => {
        const subject = provider();
        expect(subject.get()).to.equal('');
        await subject.settle();
    });

    it('prefers a fresh cache over the snapshot and makes no request', async () => {
        writeBundled(catalogJson('bundled'));
        writeCache(catalogJson('cached'), '"etag-1"', Date.now());
        const server = await startServer(catalogJson('remote'), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            expect(subject.get()).to.equal(catalogJson('cached'));
            await subject.settle();
            expect(server.requests).to.have.length(0);
        } finally {
            await server.close();
        }
    });

    it('revalidates a stale cache and keeps the payload on 304, moving only the timestamp', async () => {
        const cached = catalogJson('cached');
        writeCache(cached, '"etag-1"', Date.now() - TTL_MS - 1000);
        const server = await startServer(catalogJson('remote'), '"etag-1"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            subject.get();
            await subject.settle();

            expect(server.requests).to.have.length(1);
            expect(server.requests[0].ifNoneMatch).to.equal('"etag-1"');
            expect(fs.readFileSync(path.join(storageDir, CATALOG_FILE), 'utf8')).to.equal(cached);
            expect(readMetadata().etag).to.equal('"etag-1"');
            expect(readMetadata().fetchedAt).to.be.greaterThan(Date.now() - 60_000);
        } finally {
            await server.close();
        }
    });

    it('replaces the cache and its etag when the payload changed', async () => {
        writeCache(catalogJson('cached'), '"etag-1"', Date.now() - TTL_MS - 1000);
        const server = await startServer(catalogJson('remote'), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            const changes: string[] = [];
            subject.onDidChange((json) => changes.push(json));

            subject.get();
            await subject.settle();

            expect(fs.readFileSync(path.join(storageDir, CATALOG_FILE), 'utf8')).to.equal(catalogJson('remote'));
            expect(readMetadata().etag).to.equal('"etag-2"');
            expect(changes).to.deep.equal([catalogJson('remote')]);
            expect(subject.get()).to.equal(catalogJson('remote'));
        } finally {
            await server.close();
        }
    });

    it('sends no If-None-Match when nothing is cached', async () => {
        const server = await startServer(catalogJson('remote'), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            subject.get();
            await subject.settle();

            expect(server.requests).to.have.length(1);
            expect(server.requests[0].ifNoneMatch).to.equal(undefined);
        } finally {
            await server.close();
        }
    });

    it('rejects an unparseable download rather than replacing a working cache', async () => {
        const cached = catalogJson('cached');
        writeCache(cached, '"etag-1"', Date.now() - TTL_MS - 1000);
        const server = await startServer('{ truncated', '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            subject.get();
            await subject.settle();

            expect(fs.readFileSync(path.join(storageDir, CATALOG_FILE), 'utf8')).to.equal(cached);
            expect(readMetadata().etag).to.equal('"etag-1"');
        } finally {
            await server.close();
        }
    });

    it('rejects JSON that lacks the expected top-level keys', async () => {
        const cached = catalogJson('cached');
        writeCache(cached, '"etag-1"', Date.now() - TTL_MS - 1000);
        const server = await startServer(JSON.stringify({ unrelated: true }), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            subject.get();
            await subject.settle();
            expect(fs.readFileSync(path.join(storageDir, CATALOG_FILE), 'utf8')).to.equal(cached);
        } finally {
            await server.close();
        }
    });

    it('keeps serving the snapshot when the endpoint is unreachable', async () => {
        writeBundled(catalogJson('bundled'));
        const subject = provider();

        expect(subject.get()).to.equal(catalogJson('bundled'));
        await subject.settle();
        expect(subject.get()).to.equal(catalogJson('bundled'));
    });

    it('treats corrupt metadata as stale and revalidates', async () => {
        fs.writeFileSync(path.join(storageDir, CATALOG_FILE), catalogJson('cached'), 'utf8');
        fs.writeFileSync(path.join(storageDir, METADATA_FILE), '{ truncated', 'utf8');
        const server = await startServer(catalogJson('remote'), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            subject.get();
            await subject.settle();
            expect(server.requests).to.have.length(1);
            expect(fs.readFileSync(path.join(storageDir, CATALOG_FILE), 'utf8')).to.equal(catalogJson('remote'));
        } finally {
            await server.close();
        }
    });

    /** A restored backup or a reset system clock must not pin the cache as fresh forever. */
    it('treats a future timestamp as stale', async () => {
        writeCache(catalogJson('cached'), '"etag-1"', Date.now() + 60_000);
        const server = await startServer(catalogJson('remote'), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            subject.get();
            await subject.settle();
            expect(server.requests).to.have.length(1);
        } finally {
            await server.close();
        }
    });

    it('collapses concurrent refreshes onto one request', async () => {
        const server = await startServer(catalogJson('remote'), '"etag-2"');
        process.env.HEXANA_WASM_FEATURES_URL = server.url;
        try {
            const subject = provider();
            await Promise.all([subject.refreshNow(), subject.refreshNow(), subject.refreshNow()]);
            expect(server.requests).to.have.length(1);
        } finally {
            await server.close();
        }
    });
});
