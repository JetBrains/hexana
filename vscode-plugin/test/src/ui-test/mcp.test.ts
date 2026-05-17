import * as childProcess from 'child_process';
import * as fs from 'fs';
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

describe('Hexana MCP Server Update - Acceptance Tests', function () {
    this.timeout(180_000);

    const enabled = process.env.HEXANA_VSCODE_MCP_TEST === '1';
    const storageDir = process.env.HEXANA_MCP_STORAGE_DIR;
    const expectedVersion = process.env.HEXANA_MCP_TEST_VERSION;
    const wasmFixture = path.resolve(process.env.WASM_FIXTURE || path.join(process.cwd(), 'fixtures', 'test-exports.wasm'));

    before(function () {
        if (!enabled) {
            this.skip();
        }
        expect(storageDir, 'HEXANA_MCP_STORAGE_DIR must be set').to.be.a('string').and.not.empty;
        expect(expectedVersion, 'HEXANA_MCP_TEST_VERSION must be set').to.be.a('string').and.not.empty;
    });

    it('downloads, extracts, and launches the MCP server from the mocked GitHub release', async function () {
        const workbench = new Workbench();
        await workbench.executeCommand('Hexana MCP: Update Server');

        const serverDir = path.join(storageDir!, 'server');
        const launcherPath = path.join(
            serverDir,
            'bin',
            process.platform === 'win32' ? 'hexana-vscode-mcp-server.bat' : 'hexana-vscode-mcp-server',
        );
        const versionMarkerPath = path.join(storageDir!, 'server.version');
        const lockPath = path.join(storageDir!, '.download.lock');

        await waitForFile(launcherPath, 90_000);
        await waitForFile(versionMarkerPath, 10_000);
        await waitForNoFile(lockPath, 30_000);

        const marker = fs.readFileSync(versionMarkerPath, 'utf8').trim();
        expect(marker).to.equal(expectedVersion);

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
        expect(result.stdout).to.match(new RegExp(`hexana-mcp\\s+${expectedVersion}`));

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
