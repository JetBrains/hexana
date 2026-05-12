import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

const testRoot = process.cwd();
const codeVersion = process.env.VSCODE_TEST_VERSION || 'max';
const extensionsDir = path.resolve(process.env.VSCODE_EXTENSIONS_DIR || path.join(testRoot, '.test-extensions'));
const settingsPath = path.resolve(process.env.VSCODE_CODE_SETTINGS || path.join(testRoot, 'settings.json'));
const vsixPath = path.resolve(process.env.VSIX_PATH || path.join(testRoot, '..', 'hexana-wasm-0.0.1.vsix'));
const wasmFixture = path.resolve(process.env.WASM_FIXTURE || path.join(testRoot, 'fixtures', 'test-exports.wasm'));
const wasmWorkspace = path.resolve(process.env.WASM_WORKSPACE || path.dirname(wasmFixture));

runExtest(['get-vscode', '--code_version', codeVersion]);
runExtest(['get-chromedriver', '--code_version', codeVersion]);
runExtest(['install-vsix', '--extensions_dir', extensionsDir, '-f', vsixPath]);
runExtest([
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
    HEXANA_VSCODE_TEST_RUN: 'VSCode UI Tests',
    VSIX_PATH: vsixPath,
    WASM_FIXTURE: wasmFixture,
    WASM_WORKSPACE: wasmWorkspace,
    VSCODE_EXTENSIONS_DIR: extensionsDir,
});

const noJavaPathDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-vscode-no-java-path-'));
try {
    console.log('Running Hexana editor smoke test with Java hidden from VS Code...');
    runExtest([
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

function runExtest(args: string[], env: NodeJS.ProcessEnv = {}, unsetEnv: string[] = []): void {
    const childEnv = { ...process.env, ...env };
    for (const key of unsetEnv) {
        delete childEnv[key];
    }

    const extestCli = path.join(testRoot, 'node_modules', 'vscode-extension-tester', 'out', 'cli.js');
    const result = childProcess.spawnSync(process.execPath, [extestCli, ...args], {
        cwd: testRoot,
        env: childEnv,
        shell: false,
        stdio: 'inherit',
    });

    if (result.error) {
        throw result.error;
    }
    if (result.status !== 0) {
        process.exit(result.status ?? 1);
    }
}
