import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { CodeUtil } from 'vscode-extension-tester/out/util/codeUtil';
import { FakePostHogServer } from './fakePostHogServer';
import { assertDownloadedVsCodeVersion } from './vscodeVersion';

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
    process.env.MCP_SERVER_ZIP ||
        path.join(repoRoot, 'mcp', 'server', 'build', 'distributions', `server-${mcpVersion}.zip`),
);
const mcpMockPort = Number(process.env.HEXANA_MCP_MOCK_PORT || '18765');
const mcpStorageDir = path.resolve(
    process.env.HEXANA_MCP_STORAGE_DIR || path.join(os.tmpdir(), `hexana-vscode-mcp-test-${process.pid}`),
);
const mcpFailureMockPort = Number(process.env.HEXANA_MCP_FAILURE_MOCK_PORT || '18766');
const mcpFailureStorageDir = path.resolve(
    process.env.HEXANA_MCP_FAILURE_STORAGE_DIR ||
        path.join(os.tmpdir(), `hexana-vscode-mcp-failure-test-${process.pid}`),
);
const mcpFailureRequestLog = path.join(mcpFailureStorageDir, 'mock-requests.log');

if (require.main === module) {
    main().catch(error => {
        console.error(error);
        process.exit(1);
    });
}

async function main(): Promise<void> {
    const fakePostHog = await FakePostHogServer.start();
    console.log(`Fake PostHog server listening at ${fakePostHog.url}`);
    try {
        console.log(`Requested VS Code version: ${codeVersion}`);
        await runExtest(['get-vscode', '--code_version', codeVersion]);
        verifyDownloadedVsCodeVersion(codeVersion);
        await runExtest(['get-chromedriver', '--code_version', codeVersion]);
        await runExtest(['install-vsix', '--extensions_dir', extensionsDir, '-f', vsixPath]);

        const mainSettingsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-vscode-main-settings-'));
        try {
            fs.rmSync(mcpStorageDir, { recursive: true, force: true });
            fs.mkdirSync(mcpStorageDir, { recursive: true });
            // Seed this before VS Code opens its copied settings file. Updating the setting through the UI makes
            // VS Code atomically rename settings.json, which can fail with EPERM on Windows CI and leave the
            // debugger launch configuration pointing at a bare, unavailable wasmtime.exe.
            const mainSettingsPath = writeSettingsOverride(mainSettingsDir, settingsPath, {
                'hexana.wasmtimePath': defaultWasmtimePath(),
            });
            fakePostHog.reset();
            await runExtest([
                'run-tests',
                './out/ui-test/hexana.test.js',
                './out/ui-test/run-configurations.test.js',
                './out/ui-test/mcp.test.js',
                '--code_version',
                codeVersion,
                '--code_settings',
                mainSettingsPath,
                '--extensions_dir',
                extensionsDir,
                '-r',
                wasmWorkspace,
                wasmFixture,
            ], {
                ...fakePostHog.env,
                ...mcpProductionTestEnv(mcpStorageDir),
                HEXANA_VSCODE_TEST_RUN: 'VSCode UI Tests',
                VSIX_PATH: vsixPath,
                WASM_FIXTURE: wasmFixture,
                WASM_WORKSPACE: wasmWorkspace,
                VSCODE_EXTENSIONS_DIR: extensionsDir,
            });
        } finally {
            fs.rmSync(mainSettingsDir, { recursive: true, force: true });
            fs.rmSync(mcpStorageDir, { recursive: true, force: true });
        }

        const failureMockServer = startMcpMockServerIfAvailable({
            port: mcpFailureMockPort,
            storageDir: mcpFailureStorageDir,
            failArchive: true,
            requestLog: mcpFailureRequestLog,
            version: mcpVersion,
        });
        try {
            if (failureMockServer) {
                fakePostHog.reset();
                await runExtest([
                    'run-tests',
                    './out/ui-test/mcpFailure.test.js',
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
                    ...mcpTestEnv({
                        enabled: true,
                        port: mcpFailureMockPort,
                        storageDir: mcpFailureStorageDir,
                        failure: true,
                        requestLog: mcpFailureRequestLog,
                    }),
                    HEXANA_VSCODE_TEST_RUN: 'VSCode UI Tests (MCP download failure)',
                    VSIX_PATH: vsixPath,
                    WASM_FIXTURE: wasmFixture,
                    WASM_WORKSPACE: wasmWorkspace,
                    VSCODE_EXTENSIONS_DIR: extensionsDir,
                });
            }
        } finally {
            failureMockServer?.kill();
            fs.rmSync(mcpFailureStorageDir, { recursive: true, force: true });
        }

        const noJavaPath = buildPathWithoutJava();
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
                PATH: noJavaPath,
                Path: noJavaPath,
                VSIX_PATH: vsixPath,
                WASM_FIXTURE: wasmFixture,
                WASM_WORKSPACE: wasmWorkspace,
                VSCODE_EXTENSIONS_DIR: extensionsDir,
            }, ['JAVA_HOME']);
        } finally {
            // no temp dir to clean up
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

function verifyDownloadedVsCodeVersion(requestedVersion: string): void {
    const executable = downloadedVsCodeExecutable();
    if (!fs.existsSync(executable)) {
        throw new Error(`Downloaded VS Code executable was not found: ${executable}`);
    }

    const result = childProcess.spawnSync(executable, ['--version'], {
        encoding: 'utf8',
        windowsHide: true,
    });
    if (result.error) {
        throw new Error(`Could not run downloaded VS Code executable ${executable}: ${result.error.message}`);
    }
    if (result.status !== 0) {
        throw new Error(
            `Downloaded VS Code executable ${executable} exited with ${result.status}: ${result.stderr || result.stdout}`,
        );
    }

    const actualVersion = result.stdout.trim().split(/\r?\n/, 1)[0];
    assertDownloadedVsCodeVersion(requestedVersion, actualVersion);
    console.log(`Downloaded VS Code executable: ${executable}`);
    console.log(`Downloaded VS Code version: ${actualVersion}`);
}

function downloadedVsCodeExecutable(): string {
    const codeFolder = new CodeUtil().getCodeFolder();
    const candidates = process.platform === 'darwin'
        ? [
            path.join(codeFolder, 'Contents', 'MacOS', 'Code'),
            path.join(codeFolder, 'Contents', 'MacOS', 'Electron'),
        ]
        : process.platform === 'win32'
            ? [path.join(codeFolder, 'Code.exe')]
            : [path.join(codeFolder, 'code')];
    const executable = candidates.find(fs.existsSync);
    if (!executable) {
        throw new Error(`Extest downloaded VS Code under ${codeFolder}, but no executable was found`);
    }
    return executable;
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

function defaultWasmtimePath(): string {
    return path.join(
        os.homedir(),
        '.wasmtime',
        'bin',
        process.platform === 'win32' ? 'wasmtime.exe' : 'wasmtime',
    );
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

interface McpMockServerOptions {
    port: number;
    storageDir: string;
    failArchive?: boolean;
    requestLog?: string;
    version?: string;
}

function startMcpMockServerIfAvailable(options: McpMockServerOptions = {
    port: mcpMockPort,
    storageDir: mcpStorageDir,
}): childProcess.ChildProcess | undefined {
    if (!fs.existsSync(mcpServerZip)) {
        console.warn(
            `Skipping Hexana MCP UI test setup because MCP server ZIP was not found: ${mcpServerZip}\n` +
                'Build it with: ./gradlew :mcp:server:distZip',
        );
        return undefined;
    }

    fs.rmSync(options.storageDir, { recursive: true, force: true });
    fs.mkdirSync(options.storageDir, { recursive: true });

    const scriptPath = path.resolve(testRoot, '..', 'scripts', 'mock-mcp-downloads-server.js');
    const child = childProcess.spawn(process.execPath, [scriptPath, mcpServerZip, options.version ?? mcpVersion], {
        cwd: path.dirname(scriptPath),
        env: {
            ...process.env,
            PORT: String(options.port),
            HEXANA_MCP_MOCK_FAIL_ARCHIVE: options.failArchive ? '1' : '0',
            HEXANA_MCP_MOCK_REQUEST_LOG: options.requestLog ?? '',
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout?.on('data', (chunk) => process.stdout.write(chunk));
    child.stderr?.on('data', (chunk) => process.stderr.write(chunk));

    waitForMockServer(options.port);
    return child;
}

interface McpTestEnvOptions {
    enabled: boolean;
    port: number;
    storageDir: string;
    failure?: boolean;
    requestLog?: string;
}

function mcpProductionTestEnv(storageDir: string): NodeJS.ProcessEnv {
    return {
        HEXANA_VSCODE_MCP_TEST: '1',
        HEXANA_MCP_STORAGE_DIR: storageDir,
    };
}

function mcpTestEnv(options: boolean | McpTestEnvOptions): NodeJS.ProcessEnv {
    const resolved = typeof options === 'boolean'
        ? {
            enabled: options,
            port: mcpMockPort,
            storageDir: mcpStorageDir,
        }
        : options;

    if (!resolved.enabled) {
        return {};
    }

    return {
        HEXANA_VSCODE_MCP_TEST: '1',
        HEXANA_VSCODE_MCP_FAILURE_TEST: resolved.failure ? '1' : '0',
        HEXANA_MCP_TEST_VERSION: mcpVersion,
        HEXANA_MCP_STORAGE_DIR: resolved.storageDir,
        HEXANA_MCP_DOWNLOADS_RELEASES_INDEX_URL: `http://127.0.0.1:${resolved.port}/resources/hexana/mcp/releases.json`,
        HEXANA_MCP_RELEASES_PAGE_URL: `http://127.0.0.1:${resolved.port}/resources/hexana/mcp`,
        HEXANA_MCP_MOCK_REQUEST_LOG: resolved.requestLog ?? '',
    };
}

function waitForMockServer(port: number): void {
    const deadline = Date.now() + 10_000;
    const url = `http://127.0.0.1:${port}/resources/hexana/mcp/releases.json`;

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

/**
 * Builds a PATH string that excludes directories containing a Java
 * executable, so the "Java hidden" test run cannot find `java` but
 * still has access to essential system tools like `cmd.exe` on
 * Windows.
 *
 * The previous approach replaced PATH with an empty temp directory,
 * which hid *everything* — including `cmd.exe` — causing
 * `spawnSync cmd.exe ENOENT` failures from vscode-extension-tester.
 */
function buildPathWithoutJava(): string {
    const isWindows = os.platform() === 'win32';
    const sep = isWindows ? ';' : ':';
    const currentPath = process.env.PATH || process.env.Path || '';
    const javaExe = isWindows ? 'java.exe' : 'java';

    const filtered = currentPath.split(sep).filter(dir => {
        if (!dir) return false;
        try {
            return !fs.existsSync(path.join(dir, javaExe));
        } catch {
            return true;
        }
    });

    return filtered.join(sep);
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
