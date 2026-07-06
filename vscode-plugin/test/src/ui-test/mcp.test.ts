import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as http from 'http';
import * as https from 'https';
import * as path from 'path';
import * as readline from 'readline';
import { expect } from 'chai';
import { Workbench } from 'vscode-extension-tester';

type JsonRpcId = number;

interface JsonRpcResponse {
    jsonrpc: '2.0';
    id?: JsonRpcId;
    result?: unknown;
    error?: {
        code: number;
        message: string;
        data?: unknown;
    };
}

interface PendingRequest {
    resolve: (value: unknown) => void;
    reject: (reason?: unknown) => void;
    timer: NodeJS.Timeout;
}

interface DownloadsReleasesIndex {
    schemaVersion: number;
    latest: string;
    releases: Array<{
        version: string;
        manifestUrl: string;
    }>;
}

interface DownloadsReleaseManifest {
    version: string;
    artifacts: Array<{
        platform: string;
        kind: string;
        downloadsUrl?: string | null;
    }>;
}

interface ExpectedNativeRelease {
    version: string;
    platform: string;
}

describe('Hexana MCP Server Update - Acceptance Tests', function () {
    this.timeout(180_000);

    const enabled = process.env.HEXANA_VSCODE_MCP_TEST === '1';
    const storageDir = process.env.HEXANA_MCP_STORAGE_DIR;
    const wasmFixture = path.resolve(process.env.WASM_FIXTURE || path.join(process.cwd(), 'fixtures', 'test-exports.wasm'));
    let expectedRelease: ExpectedNativeRelease;

    before(async function () {
        if (!enabled) {
            this.skip();
        }
        expect(storageDir, 'HEXANA_MCP_STORAGE_DIR must be set').to.be.a('string').and.not.empty;
        const release = await fetchExpectedNativeRelease();
        if (!release) {
            this.skip();
            return;
        }
        expectedRelease = release;
    });

    it('downloads, extracts, and launches the MCP server from production downloads', async function () {
        const workbench = new Workbench();
        await workbench.executeCommand('Hexana MCP: Update Server');

        const serverDir = path.join(storageDir!, 'server');
        const launcherPath = path.join(
            serverDir,
            'bin',
            process.platform === 'win32' ? 'hexana-mcp.exe' : 'hexana-mcp',
        );
        const versionMarkerPath = path.join(storageDir!, 'server.version');
        const lockPath = path.join(storageDir!, '.download.lock');

        await waitForFile(launcherPath, 120_000);
        await waitForFile(versionMarkerPath, 10_000);
        await waitForNoFile(lockPath, 30_000);

        const marker = fs.readFileSync(versionMarkerPath, 'utf8').trim();
        expect(marker).to.equal(expectedRelease.version);

        if (process.platform !== 'win32') {
            fs.accessSync(launcherPath, fs.constants.X_OK);
        }

        const result = childProcess.spawnSync(launcherPath, ['version'], {
            cwd: process.cwd(),
            encoding: 'utf8',
            env: {
                ...process.env,
                HEXANA_MCP_TELEMETRY_ENABLED: 'false',
            },
            shell: process.platform === 'win32',
        });

        expect(result.status, result.stderr || result.stdout).to.equal(0);
        expect(result.stdout).to.match(new RegExp(`hexana-mcp\\s+${expectedRelease.version}`));

        const client = new StdioMcpClient(
            launcherPath,
            ['stdio', '--log-level', 'quiet', '--telemetry-enabled', 'false'],
            serverDir,
        );
        try {
            await client.initialize();

            const tools = await client.request<{ tools: Array<{ name: string }> }>('tools/list', {});
            expect(tools.tools.map(tool => tool.name)).to.include('query_artifacts');

            const toolResult = await client.request<{
                content: Array<{ type: string; text?: string }>;
                structuredContent: { modules: Array<{ moduleSize: number; functions: number }> };
                isError?: boolean;
            }>('tools/call', {
                name: 'query_artifacts',
                arguments: {
                    path: wasmFixture,
                    operation: 'overview',
                },
            });

            expect(toolResult.isError).to.not.equal(true);
            expect(toolResult.structuredContent.modules[0].moduleSize).to.be.greaterThan(0);
            expect(toolResult.structuredContent.modules[0].functions).to.be.greaterThan(0);
            expect(toolResult.content[0].type).to.equal('text');
            expect(JSON.parse(toolResult.content[0].text || '{}')).to.deep.equal(toolResult.structuredContent);
        } finally {
            await client.close();
        }
    });
});

async function fetchExpectedNativeRelease(): Promise<ExpectedNativeRelease | undefined> {
    const platform = currentDownloadsPlatform();
    if (!platform) {
        return undefined;
    }

    const index = await getJson<DownloadsReleasesIndex>(
        process.env.HEXANA_MCP_DOWNLOADS_RELEASES_INDEX_URL ||
        'https://download.jetbrains.com/resources/hexana/mcp/releases.json',
    );
    expect(index.schemaVersion).to.equal(1);

    const release = index.releases.find(candidate => candidate.version === index.latest);
    expect(release, `Downloads index does not contain latest release ${index.latest}`).to.not.equal(undefined);

    const manifest = await getJson<DownloadsReleaseManifest>(release!.manifestUrl);
    expect(manifest.version).to.equal(release!.version);

    const nativeArtifact = manifest.artifacts.find(candidate =>
        candidate.platform === platform &&
        candidate.kind === 'native-executable' &&
        typeof candidate.downloadsUrl === 'string',
    );
    return nativeArtifact ? { version: manifest.version, platform } : undefined;
}

function currentDownloadsPlatform(): string | undefined {
    const osName = process.platform === 'darwin'
        ? 'macos'
        : process.platform === 'linux'
            ? 'linux'
            : process.platform === 'win32'
                ? 'windows'
                : undefined;
    const archName = process.arch === 'x64' || process.arch === 'arm64'
        ? process.arch
        : undefined;
    return osName && archName ? `${osName}-${archName}` : undefined;
}

function getJson<T>(url: string): Promise<T> {
    return new Promise((resolve, reject) => {
        const request = getHttpClient(url).get(
            url,
            {
                headers: {
                    'User-Agent': 'Hexana-VSCode-UI-Test',
                },
            },
            response => {
                const statusCode = response.statusCode ?? 0;
                const location = response.headers.location;
                if (statusCode >= 300 && statusCode < 400 && location) {
                    response.resume();
                    getJson<T>(new URL(location, url).toString()).then(resolve, reject);
                    return;
                }

                const chunks: Buffer[] = [];
                response.on('error', reject);
                response.on('data', (chunk: Buffer) => chunks.push(chunk));
                response.on('end', () => {
                    const body = Buffer.concat(chunks).toString('utf8');
                    if (statusCode < 200 || statusCode >= 300) {
                        reject(new Error(`Release metadata request failed with HTTP ${statusCode}: ${body}`));
                        return;
                    }

                    try {
                        resolve(JSON.parse(body) as T);
                    } catch (error) {
                        reject(error);
                    }
                });
            },
        );

        request.setTimeout(30_000, () => {
            request.destroy(new Error('Release metadata request timed out'));
        });
        request.on('error', reject);
        request.end();
    });
}

function getHttpClient(url: string): typeof http | typeof https {
    const protocol = new URL(url).protocol;
    if (protocol === 'http:') {
        return http;
    }
    if (protocol === 'https:') {
        return https;
    }
    throw new Error(`Unsupported URL protocol: ${protocol}`);
}

class StdioMcpClient {
    private nextId = 1;
    private readonly child: childProcess.ChildProcessWithoutNullStreams;
    private readonly stderrChunks: string[] = [];
    private readonly pending = new Map<JsonRpcId, PendingRequest>();
    private readonly lines: readline.Interface;

    constructor(command: string, args: string[], cwd: string) {
        this.child = childProcess.spawn(command, args, {
            cwd,
            env: {
                ...process.env,
                HEXANA_MCP_HOST: 'vscode-ui-test',
                HEXANA_MCP_TELEMETRY_ENABLED: 'false',
            },
            shell: process.platform === 'win32',
            stdio: 'pipe',
        });

        this.child.stderr.setEncoding('utf8');
        this.child.stderr.on('data', chunk => this.stderrChunks.push(String(chunk)));

        this.lines = readline.createInterface({ input: this.child.stdout });
        this.lines.on('line', line => this.handleLine(line));

        this.child.on('exit', (code, signal) => {
            const error = new Error(
                `MCP server exited with code ${code ?? 'null'} signal ${signal ?? 'null'}.\n${this.stderrText()}`,
            );
            for (const [id, pending] of this.pending) {
                clearTimeout(pending.timer);
                pending.reject(error);
                this.pending.delete(id);
            }
        });
    }

    async initialize(): Promise<void> {
        const result = await this.request<{ protocolVersion: string; serverInfo: { name: string } }>('initialize', {
            protocolVersion: '2025-11-25',
            capabilities: {},
            clientInfo: {
                name: 'hexana-vscode-ui-test',
                version: '1.0.0',
            },
        });

        expect(result.protocolVersion).to.be.a('string');
        expect(result.serverInfo.name).to.equal('hexana');
        this.notify('notifications/initialized', {});
    }

    request<T>(method: string, params: unknown, timeoutMs = 30_000): Promise<T> {
        const id = this.nextId++;
        const message = {
            jsonrpc: '2.0',
            id,
            method,
            params,
        };

        return new Promise<T>((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`Timed out waiting for ${method} response.\n${this.stderrText()}`));
            }, timeoutMs);

            this.pending.set(id, {
                resolve: value => resolve(value as T),
                reject,
                timer,
            });
            this.writeJson(message);
        });
    }

    notify(method: string, params: unknown): void {
        this.writeJson({
            jsonrpc: '2.0',
            method,
            params,
        });
    }

    async close(): Promise<void> {
        this.lines.close();
        this.child.stdin.end();
        if (this.child.exitCode !== null) {
            return;
        }

        await new Promise<void>(resolve => {
            const timer = setTimeout(() => {
                this.child.kill('SIGKILL');
                resolve();
            }, 3_000);
            this.child.once('exit', () => {
                clearTimeout(timer);
                resolve();
            });
        });
    }

    private handleLine(line: string): void {
        if (line.trim().length === 0) {
            return;
        }

        let message: JsonRpcResponse;
        try {
            message = JSON.parse(line) as JsonRpcResponse;
        } catch (error) {
            throw new Error(`MCP server wrote non-JSON stdout line: ${line}: ${String(error)}`);
        }

        if (message.id === undefined) {
            return;
        }

        const pending = this.pending.get(message.id);
        if (!pending) {
            return;
        }

        clearTimeout(pending.timer);
        this.pending.delete(message.id);

        if (message.error) {
            pending.reject(new Error(`MCP request failed: ${message.error.message}`));
        } else {
            pending.resolve(message.result);
        }
    }

    private writeJson(message: unknown): void {
        this.child.stdin.write(`${JSON.stringify(message)}\n`);
    }

    private stderrText(): string {
        return this.stderrChunks.join('').trim();
    }
}

async function waitForFile(filePath: string, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (fs.existsSync(filePath)) {
            return;
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`Timed out waiting for ${filePath}`);
}

async function waitForNoFile(filePath: string, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (!fs.existsSync(filePath)) {
            return;
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`Timed out waiting for ${filePath} to disappear`);
}
