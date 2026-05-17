import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { FakePostHogServer } from './fakePostHogServer';

function defaultVsixName(): string {
    const packageJsonPath = path.join(__dirname, '..', '..', 'package.json');
    const pkg = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8')) as { name: string; version: string };
    return `${pkg.name}-${pkg.version}.vsix`;
}

const testRoot = process.cwd();
const codeVersion = process.env.VSCODE_TEST_VERSION || 'max';
const extensionsDir = path.resolve(process.env.VSCODE_EXTENSIONS_DIR || path.join(testRoot, '.test-extensions'));
const settingsPath = path.resolve(process.env.VSCODE_CODE_SETTINGS || path.join(testRoot, 'settings.json'));
const vsixPath = path.resolve(process.env.VSIX_PATH || path.join(testRoot, '..', defaultVsixName()));
const wasmFixture = path.resolve(process.env.WASM_FIXTURE || path.join(testRoot, 'fixtures', 'test-exports.wasm'));
const wasmWorkspace = path.resolve(process.env.WASM_WORKSPACE || path.dirname(wasmFixture));
const repoRoot = path.resolve(testRoot, '..', '..');
const mcpVersion = readMcpServerVersion();
const mcpServerZip = path.resolve(
    process.env.MCP_VSCODE_SERVER_ZIP ||
        path.join(repoRoot, 'mcp', 'vscode-server', 'build', 'distributions', `hexana-vscode-mcp-server-${mcpVersion}.zip`),
);
const mcpMockPort = Number(process.env.HEXANA_MCP_MOCK_PORT || '18765');
const mcpStorageDir = path.resolve(
    process.env.HEXANA_MCP_STORAGE_DIR || path.join(os.tmpdir(), `hexana-vscode-mcp-test-${process.pid}`),
);

main().catch(error => {
    console.error(error);
    process.exit(1);
});

async function main(): Promise<void> {
    const fakePostHog = await FakePostHogServer.start();
    console.log(`Fake PostHog server listening at ${fakePostHog.url}`);
    try {
        await runExtest(['get-vscode', '--code_version', codeVersion]);
        await runExtest(['get-chromedriver', '--code_version', codeVersion]);
        await runExtest(['install-vsix', '--extensions_dir', extensionsDir, '-f', vsixPath]);

        const mockServer = startMcpMockServerIfAvailable();
        try {
            fakePostHog.reset();
            await runExtest([
                'run-tests',
                './out/ui-test/*.test.js',
                '--code_version',
                codeVersion,
                '--code_settings',
                settingsPath,
                '--extensions_dir',
                extensionsDir,
                '-r',
                wasmWorkspace,
                wasmFixture,
            ], {
                ...fakePostHog.env,
                ...mcpTestEnv(mockServer !== undefined),
                HEXANA_VSCODE_TEST_RUN: 'VSCode UI Tests',
                VSIX_PATH: vsixPath,
                WASM_FIXTURE: wasmFixture,
                WASM_WORKSPACE: wasmWorkspace,
                VSCODE_EXTENSIONS_DIR: extensionsDir,
            });
        } finally {
            mockServer?.kill();
            fs.rmSync(mcpStorageDir, { recursive: true, force: true });
        }

        const noJavaPathDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-vscode-no-java-path-'));
        try {
            console.log('Running Hexana editor smoke test with Java hidden from VS Code...');
            fakePostHog.reset();
            await runExtest([
                'run-tests',
                './out/ui-test/hexana.test.js',
                '--code_version',
                codeVersion,
                '--code_settings',
                settingsPath,
                '--extensions_dir',
                extensionsDir,
                '--offline',
                '-r',
                wasmWorkspace,
                wasmFixture,
            ], {
                ...fakePostHog.env,
                HEXANA_VSCODE_TEST_RUN: 'VSCode UI Tests (Java hidden)',
                PATH: noJavaPathDir,
                Path: noJavaPathDir,
                VSIX_PATH: vsixPath,
                WASM_FIXTURE: wasmFixture,
                WASM_WORKSPACE: wasmWorkspace,
                VSCODE_EXTENSIONS_DIR: extensionsDir,
            }, ['JAVA_HOME']);
        } finally {
            fs.rmSync(noJavaPathDir, { recursive: true, force: true });
        }

        const disabledSettingsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-vscode-telemetry-disabled-'));
        try {
            console.log('Running Hexana telemetry-disabled smoke test...');
            const disabledSettingsPath = writeSettingsOverride(disabledSettingsDir, settingsPath, {
                'hexana.enableStatistics': false,
            });
            fakePostHog.reset();
            await runExtest([
                'run-tests',
                './out/ui-test-disabled/*.test.js',
                '--code_version',
                codeVersion,
                '--code_settings',
                disabledSettingsPath,
                '--extensions_dir',
                extensionsDir,
                '--offline',
                '-r',
                wasmWorkspace,
                wasmFixture,
            ], {
                ...fakePostHog.env,
                HEXANA_VSCODE_TEST_RUN: 'VSCode UI Tests (telemetry disabled)',
                VSIX_PATH: vsixPath,
                WASM_FIXTURE: wasmFixture,
                WASM_WORKSPACE: wasmWorkspace,
                VSCODE_EXTENSIONS_DIR: extensionsDir,
            });
        } finally {
            fs.rmSync(disabledSettingsDir, { recursive: true, force: true });
        }
    } finally {
        await fakePostHog.close();
    }
}

function runExtest(args: string[], env: NodeJS.ProcessEnv = {}, unsetEnv: string[] = []): Promise<void> {
    const childEnv = { ...process.env, ...env };
    for (const key of unsetEnv) {
        delete childEnv[key];
    }

    const extestCli = path.join(testRoot, 'node_modules', 'vscode-extension-tester', 'out', 'cli.js');
    return new Promise((resolve, reject) => {
        const child = childProcess.spawn(process.execPath, [extestCli, ...args], {
            cwd: testRoot,
            env: childEnv,
            shell: false,
            stdio: 'inherit',
        });
        child.on('error', reject);
        child.on('exit', (code, signal) => {
            if (code === 0) {
                resolve();
            } else {
                reject(new Error(`extest failed with ${signal ? `signal ${signal}` : `exit code ${code ?? 1}`}`));
            }
        });
    });
}

function writeSettingsOverride(
    directory: string,
    baseSettingsPath: string,
    override: Record<string, unknown>,
): string {
    const baseSettings = JSON.parse(fs.readFileSync(baseSettingsPath, 'utf8')) as Record<string, unknown>;
    const settingsPath = path.join(directory, 'settings.json');
    fs.writeFileSync(settingsPath, JSON.stringify(deepMerge(baseSettings, override), null, 2));
    return settingsPath;
}

function deepMerge(base: Record<string, unknown>, override: Record<string, unknown>): Record<string, unknown> {
    const result = { ...base };
    for (const [key, value] of Object.entries(override)) {
        const baseValue = result[key];
        if (isRecord(baseValue) && isRecord(value)) {
            result[key] = deepMerge(baseValue, value);
        } else {
            result[key] = value;
        }
    }
    return result;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function startMcpMockServerIfAvailable(): childProcess.ChildProcess | undefined {
    if (!fs.existsSync(mcpServerZip)) {
        console.warn(
            `Skipping Hexana MCP UI test setup because MCP server ZIP was not found: ${mcpServerZip}\n` +
                'Build it with: ./gradlew :mcp:vscode-server:distZip',
        );
        return undefined;
    }

    fs.rmSync(mcpStorageDir, { recursive: true, force: true });
    fs.mkdirSync(mcpStorageDir, { recursive: true });

    const scriptPath = path.resolve(testRoot, '..', 'scripts', 'mock-github-release-server.js');
    const child = childProcess.spawn(process.execPath, [scriptPath, mcpServerZip, mcpVersion], {
        cwd: path.dirname(scriptPath),
        env: {
            ...process.env,
            PORT: String(mcpMockPort),
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout?.on('data', (chunk) => process.stdout.write(chunk));
    child.stderr?.on('data', (chunk) => process.stderr.write(chunk));

    waitForMockServer();
    return child;
}

function mcpTestEnv(enabled: boolean): NodeJS.ProcessEnv {
    if (!enabled) {
        return {};
    }

    return {
        HEXANA_VSCODE_MCP_TEST: '1',
        HEXANA_MCP_TEST_VERSION: mcpVersion,
        HEXANA_MCP_STORAGE_DIR: mcpStorageDir,
        HEXANA_MCP_GITHUB_RELEASE_API_URL: `http://127.0.0.1:${mcpMockPort}/repos/example/hexana-mcp/releases/latest`,
        HEXANA_MCP_RELEASES_PAGE_URL: `http://127.0.0.1:${mcpMockPort}/releases`,
    };
}

function waitForMockServer(): void {
    const deadline = Date.now() + 10_000;
    const url = `http://127.0.0.1:${mcpMockPort}/repos/example/hexana-mcp/releases/latest`;

    while (Date.now() < deadline) {
        const result = childProcess.spawnSync(process.execPath, [
            '-e',
            "require('http').get(process.argv[1], r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))",
            url,
        ], { stdio: 'ignore' });
        if (result.status === 0) {
            return;
        }
        sleep(250);
    }

    throw new Error(`Mock MCP release server did not start at ${url}`);
}

function sleep(milliseconds: number): void {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function readMcpServerVersion(): string {
    const propertiesPath = path.join(repoRoot, 'mcp', 'server', 'gradle.properties');
    const properties = fs.readFileSync(propertiesPath, 'utf8');
    const match = /^mcpServerVersion=(.+)$/m.exec(properties);
    if (!match) {
        throw new Error(`Could not read mcpServerVersion from ${propertiesPath}`);
    }
    return match[1].trim();
}
