import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as readline from 'readline';
import { expect } from 'chai';

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

describe('Hexana VS Code MCP - Packaged Server Smoke', function () {
    this.timeout(90_000);

    it('should expose Hexana MCP tools from the installed VSIX server over stdio', async function () {
        const extensionRoot = findInstalledExtensionRoot();
        const packageJson = readPackageJson(extensionRoot);
        const launcher = mcpLauncherPath(extensionRoot);
        const fixturePath = path.resolve(process.env.WASM_FIXTURE || path.join(process.cwd(), 'fixtures', 'test-exports.wasm'));

        expect(packageJson.contributes?.mcpServerDefinitionProviders).to.deep.include({
            id: 'hexana.vscode.mcp',
            label: 'Hexana MCP',
        });
        expect(fs.existsSync(fixturePath), `Missing wasm fixture: ${fixturePath}`).to.equal(true);
        expect(fs.existsSync(launcher), `Missing bundled MCP server launcher: ${launcher}`).to.equal(true);
        if (process.platform !== 'win32') {
            const mode = fs.statSync(launcher).mode;
            expect((mode & 0o111) !== 0, `MCP server launcher is not executable: ${launcher}`).to.equal(true);
        }

        const client = new StdioMcpClient(launcher, ['--transport', 'stdio', '--log-level', 'quiet'], extensionRoot);
        try {
            await client.initialize();

            const tools = await client.request<{ tools: Array<{ name: string; annotations?: Record<string, unknown> }> }>('tools/list', {});
            const toolNames = tools.tools.map(tool => tool.name);
            expect(toolNames).to.include('summarize_module');
            expect(toolNames).to.include('list_exported_functions');

            const summarizeModule = tools.tools.find(tool => tool.name === 'summarize_module');
            expect(summarizeModule?.annotations?.readOnlyHint).to.equal(true);
            expect(summarizeModule?.annotations?.destructiveHint).to.equal(false);

            const result = await client.request<{
                content: Array<{ type: string; text?: string }>;
                structuredContent: { moduleSize: number; functions: number };
                isError?: boolean;
            }>('tools/call', {
                name: 'summarize_module',
                arguments: {
                    path: fixturePath,
                },
            });

            expect(result.isError).to.not.equal(true);
            expect(result.structuredContent.moduleSize).to.be.greaterThan(0);
            expect(result.structuredContent.functions).to.be.greaterThan(0);
            expect(result.content).to.have.length(1);
            expect(result.content[0].type).to.equal('text');
            expect(JSON.parse(result.content[0].text || '{}')).to.deep.equal(result.structuredContent);
        } finally {
            await client.close();
        }
    });

    it('should keep missing Java isolated to the MCP launcher', function () {
        const extensionRoot = findInstalledExtensionRoot();
        const launcher = mcpLauncherPath(extensionRoot);
        const emptyPathDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-no-java-path-'));
        const env: NodeJS.ProcessEnv = {
            ...process.env,
            PATH: emptyPathDir,
            Path: emptyPathDir,
            HEXANA_MCP_HOST: 'vscode-ui-test-no-java',
            HEXANA_MCP_TELEMETRY_ENABLED: 'false',
        };
        delete env.JAVA_HOME;

        try {
            const result = childProcess.spawnSync(launcher, ['--transport', 'stdio', '--log-level', 'quiet'], {
                cwd: extensionRoot,
                env,
                encoding: 'utf8',
                timeout: 10_000,
            });

            expect(result.error, result.stderr || result.error?.message).to.equal(undefined);
            expect(result.status).to.not.equal(0);
            expect(result.stdout).to.equal('');
            expect(result.stderr).to.match(/JAVA_HOME|java/i);
        } finally {
            fs.rmSync(emptyPathDir, { recursive: true, force: true });
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
            env: { ...process.env, HEXANA_MCP_HOST: 'vscode-ui-test' },
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

function findInstalledExtensionRoot(): string {
    const extensionsDir = path.resolve(process.env.VSCODE_EXTENSIONS_DIR || path.join(process.cwd(), '.test-extensions'));
    expect(fs.existsSync(extensionsDir), `Missing VS Code extensions directory: ${extensionsDir}`).to.equal(true);

    const candidates = collectPackageJsonFiles(extensionsDir);
    for (const packageJsonPath of candidates) {
        const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8')) as { name?: string; publisher?: string };
        if (packageJson.name === 'hexana-wasm' && packageJson.publisher?.toLowerCase() === 'jetbrains') {
            return path.dirname(packageJsonPath);
        }
    }

    throw new Error(`Installed Hexana extension not found in ${extensionsDir}`);
}

function collectPackageJsonFiles(root: string): string[] {
    const result: string[] = [];
    const queue: Array<{ dir: string; depth: number }> = [{ dir: root, depth: 0 }];

    while (queue.length > 0) {
        const current = queue.shift()!;
        if (current.depth > 3) {
            continue;
        }

        for (const entry of fs.readdirSync(current.dir, { withFileTypes: true })) {
            const fullPath = path.join(current.dir, entry.name);
            if (entry.isFile() && entry.name === 'package.json') {
                result.push(fullPath);
            } else if (entry.isDirectory()) {
                queue.push({ dir: fullPath, depth: current.depth + 1 });
            }
        }
    }

    return result;
}

function readPackageJson(extensionRoot: string): {
    contributes?: {
        mcpServerDefinitionProviders?: Array<{ id: string; label: string }>;
    };
} {
    return JSON.parse(fs.readFileSync(path.join(extensionRoot, 'package.json'), 'utf8'));
}

function mcpLauncherPath(extensionRoot: string): string {
    const executableName = process.platform === 'win32'
        ? 'hexana-vscode-mcp-server.bat'
        : 'hexana-vscode-mcp-server';
    return path.join(extensionRoot, 'server', 'bin', executableName);
}
