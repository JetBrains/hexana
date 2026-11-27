import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import {AddressInfo} from 'net';
import {expect} from 'chai';
import {installVscodeMock, VscodeMock} from './vscode-mock';

interface Message {
    seq: number;
    type: string;
    command?: string;
    request_seq?: number;
    success?: boolean;
    arguments?: Record<string, unknown>;
}

interface AttachTarget {
    runtime: string;
    host: string | null;
    port: number;
    userDataDir: string | null;
    wasmPath: string | null;
    wasmPathCandidates: string[];
    needsPortDiscovery: boolean;
}

const OUT = path.join(__dirname, '..', '..', '..', '..', '..', 'out');

/**
 * Attaching `wasm` sessions to processes the extension did not start.
 * Target detection itself is the shared Kotlin `WasmAttachTarget`
 * (plugins-shared commonTest); these cases pin the VS Code plumbing around it.
 */
describe('wasm debugger attach', function () {
    let vscode: VscodeMock;

    beforeEach(function () {
        vscode = installVscodeMock();
        const mock = vscode.module as {window: {createOutputChannel?: unknown}};
        mock.window.createOutputChannel = () => ({appendLine: () => undefined});
        clearOutModuleCache();
    });

    afterEach(function () {
        vscode.restore();
        clearOutModuleCache();
    });

    describe('gdbstub attach through lldb-dap', function () {
        it('forwards attach as the launched-session process connect and restores the response command', function () {
            const {LldbDapProxy} = require(path.join(OUT, 'LldbDapDebugAdapter'));
            const proxy = new LldbDapProxy('unused', [], false);
            const forwarded: Message[] = [];
            (proxy as any).process = {stdin: {write: (data: string) => forwarded.push(parseDap(data))}, kill: () => true};
            const received: Message[] = [];
            proxy.onDidSendMessage((m: Message) => received.push(m));
            proxy.setAttachEndpoint('127.0.0.1', 1234);

            proxy.handleMessage({seq: 3, type: 'request', command: 'attach', arguments: {runtime: 'wasmtime'}});
            expect(forwarded[0].command).to.equal('launch');
            expect(forwarded[0].arguments).to.include({stopOnEntry: true, runtime: 'wasmtime'});
            expect(forwarded[0].arguments!.launchCommands)
                .to.deep.equal(['process connect --plugin wasm connect://127.0.0.1:1234']);

            proxy.onStdoutData(dapFrame({seq: 1, type: 'response', request_seq: 3, command: 'launch', success: true}));
            expect(received[0].command).to.equal('attach');
            expect(received[0].request_seq).to.equal(3);

            proxy.handleMessage({seq: 9, type: 'request', command: 'disconnect', arguments: {restart: false}});
            expect(forwarded[1].arguments).to.deep.equal({restart: false, terminateDebuggee: false});
            proxy.dispose();
        });

        it('leaves launched sessions untouched', function () {
            const {LldbDapProxy} = require(path.join(OUT, 'LldbDapDebugAdapter'));
            const proxy = new LldbDapProxy('unused', [], false);
            const forwarded: Message[] = [];
            (proxy as any).process = {stdin: {write: (data: string) => forwarded.push(parseDap(data))}, kill: () => true};

            proxy.handleMessage({seq: 3, type: 'request', command: 'launch', arguments: {}});
            proxy.handleMessage({seq: 4, type: 'request', command: 'disconnect', arguments: {}});
            expect(forwarded.map((m) => m.command)).to.deep.equal(['launch', 'disconnect']);
            expect(forwarded[1].arguments).to.deep.equal({});
            proxy.dispose();
        });
    });

    describe('process listing', function () {
        it('parses ps rows with parent pids and executables', function () {
            const {parseUnixProcessList, parseUnixCommList} = require(path.join(OUT, 'WasmAttach'));
            expect(parseUnixProcessList('  42     1 wasmtime run -g 127.0.0.1:1234 app.wasm\n   7   0 \n  x junk\n'))
                .to.deep.equal([{pid: 42, ppid: 1, commandLine: 'wasmtime run -g 127.0.0.1:1234 app.wasm'}]);
            expect([...parseUnixCommList('  42 /Users/me/my tools/wasmtime\n').entries()])
                .to.deep.equal([[42, '/Users/me/my tools/wasmtime']]);
        });

        it('parses PowerShell Win32_Process rows', function () {
            const {parseWindowsProcessList} = require(path.join(OUT, 'WasmAttach'));
            const out = '4\t0\t\t\r\n1200\t900\tC:\\Program Files\\nodejs\\node.exe\t"C:\\Program Files\\nodejs\\node.exe" --inspect app.js\r\n';
            expect(parseWindowsProcessList(out)).to.deep.equal([{
                pid: 1200,
                ppid: 900,
                commandLine: '"C:\\Program Files\\nodejs\\node.exe" --inspect app.js',
                executable: 'C:\\Program Files\\nodejs\\node.exe',
            }]);
        });

        it('detects a macOS process whose executable path has spaces', function () {
            const {detectProcess} = require(path.join(OUT, 'WasmAttach'));
            if (process.platform === 'linux') this.skip(); // Linux reads /proc/<pid>/cmdline instead
            const target = detectProcess({
                pid: -1,
                ppid: 1,
                commandLine: '/Users/me/my tools/iwasm -g=127.0.0.1:1234 /Users/me/my wasm/app.wasm',
                executable: '/Users/me/my tools/iwasm',
            });
            if (target === null && process.platform === 'win32') this.skip(); // Windows tokenizing of a Unix line
            expect(target).to.include({runtime: 'wamr', port: 1234, wasmPath: 'wasm/app.wasm'});
            expect(target.wasmPathCandidates).to.deep.equal(['/Users/me/my wasm/app.wasm', 'wasm/app.wasm']);
        });
    });

    describe('endpoint resolution', function () {
        let tempRoot: string;
        let server: http.Server | undefined;

        beforeEach(function () {
            tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-attach-test-'));
        });

        afterEach(function () {
            server?.close();
            server = undefined;
            fs.rmSync(tempRoot, {recursive: true, force: true});
        });

        it('reads DevToolsActivePort', function () {
            const {readDevToolsActivePort} = require(path.join(OUT, 'WasmAttach'));
            fs.writeFileSync(path.join(tempRoot, 'DevToolsActivePort'), '53111\n/devtools/browser/abc\n');
            expect(readDevToolsActivePort(tempRoot)).to.equal(53111);
            expect(readDevToolsActivePort(path.join(tempRoot, 'missing'))).to.equal(undefined);
        });

        it('finds the Node.js inspector and the first browser page', async function () {
            const {resolveCdpAttachWebSocketUrl} = require(path.join(OUT, 'WasmAttach'));
            const port = await serve({
                '/json/list': [{type: 'node', webSocketDebuggerUrl: 'ws://127.0.0.1/node-target'}],
                '/json': [
                    {type: 'service_worker', webSocketDebuggerUrl: 'ws://127.0.0.1/worker'},
                    {type: 'page', webSocketDebuggerUrl: 'ws://127.0.0.1/page'},
                ],
            });
            expect(await resolveCdpAttachWebSocketUrl({runtime: 'nodejs', host: '127.0.0.1', port}))
                .to.equal('ws://127.0.0.1/node-target');

            fs.writeFileSync(path.join(tempRoot, 'DevToolsActivePort'), `${port}\n`);
            expect(await resolveCdpAttachWebSocketUrl({runtime: 'browser', port: 0, userDataDir: tempRoot}))
                .to.equal('ws://127.0.0.1/page');
        });

        it('fails clearly when nothing answers', async function () {
            const {resolveCdpAttachWebSocketUrl} = require(path.join(OUT, 'WasmAttach'));
            const port = await serve({});
            server!.close();
            server = undefined;
            let error: unknown;
            try {
                await resolveCdpAttachWebSocketUrl({runtime: 'nodejs', port});
            } catch (e) {
                error = e;
            }
            expect(String(error)).to.contain(`no Node.js inspector answered at 127.0.0.1:${port}`);
        });

        it('resolves module path candidates, longest first', function () {
            const {resolveModulePath} = require(path.join(OUT, 'WasmAttach'));
            fs.mkdirSync(path.join(tempRoot, 'my wasm'));
            const module = path.join(tempRoot, 'my wasm', 'app.wasm');
            fs.writeFileSync(module, Buffer.from([0, 0x61, 0x73, 0x6d]));
            const folder = {uri: {fsPath: tempRoot}};
            expect(resolveModulePath([module], -1, undefined)).to.equal(module);
            expect(resolveModulePath([path.join('my wasm', 'app.wasm')], -1, folder)).to.equal(module);
            // What a quote-dropping listing leaves of the path, rejoined by the detector.
            expect(resolveModulePath([module, path.join('wasm', 'app.wasm')], -1, folder)).to.equal(module);
            expect(resolveModulePath(['missing.wasm'], -1, folder)).to.equal(undefined);
        });

        it('discovers an inspector on an OS-chosen port of the process', async function () {
            const {discoverEndpoint} = require(path.join(OUT, 'WasmAttach'));
            // This test process plays the Node.js process started with --inspect=0.
            const port = await serve({'/json/list': [{type: 'node', webSocketDebuggerUrl: 'ws://127.0.0.1/self'}]});
            const config: Record<string, unknown> = {runtime: 'nodejs', port: 0};
            await discoverEndpoint(config, process.pid);
            expect(config).to.include({port, wsUrl: 'ws://127.0.0.1/self'});
        });

        it('takes the single listening port of a gdbstub on an OS-chosen port', async function () {
            const {discoverEndpoint} = require(path.join(OUT, 'WasmAttach'));
            const port = await serve({});
            const config: Record<string, unknown> = {runtime: 'wasmtime', port: 0};
            await discoverEndpoint(config, process.pid);
            expect(config).to.include({host: '127.0.0.1', port});
        });

        function serve(routes: Record<string, unknown>): Promise<number> {
            return new Promise((resolve) => {
                server = http.createServer((req, res) => {
                    const body = routes[req.url ?? ''];
                    res.statusCode = body ? 200 : 404;
                    res.end(body ? JSON.stringify(body) : '');
                });
                server.listen(0, '127.0.0.1', () => resolve((server!.address() as AddressInfo).port));
            });
        }
    });

    describe('CDP sessions across page navigations', function () {
        function connectedAdapter(runtime: 'nodejs' | 'browser') {
            const {CdpDebugAdapter} = require(path.join(OUT, 'CdpDebugAdapter'));
            const adapter = new CdpDebugAdapter(runtime, {request: 'attach'}) as any;
            const ws = {
                closed: false,
                send(data: string) {
                    const {id} = JSON.parse(data);
                    setImmediate(() => adapter.handleCdpMessage(JSON.stringify({id, result: {}})));
                },
                close() { this.closed = true; },
            };
            adapter.ws = ws;
            return {adapter, ws};
        }

        function emit(adapter: any, method: string, params: unknown = {}) {
            adapter.handleCdpMessage(JSON.stringify({method, params}));
        }

        it('keeps a browser session through a navigation and forgets the old page', async function () {
            const {adapter, ws} = connectedAdapter('browser');
            await adapter.initCdpSession();
            emit(adapter, 'Debugger.scriptParsed', {scriptId: '11', url: 'wasm://wasm/abc'});
            expect(adapter.wasmScriptId).to.equal('11');

            emit(adapter, 'Runtime.executionContextDestroyed', {executionContextId: 1});
            emit(adapter, 'Runtime.executionContextsCleared');
            expect(ws.closed).to.equal(false);
            expect(adapter.wasmScriptId).to.equal(undefined);
            expect(adapter.scripts.size).to.equal(0);

            emit(adapter, 'Debugger.scriptParsed', {scriptId: '42', url: 'wasm://wasm/def'});
            expect(adapter.wasmScriptId).to.equal('42');
            adapter.disposed = true;
        });

        it('still ends a Node.js session when its context is destroyed', async function () {
            const {adapter, ws} = connectedAdapter('nodejs');
            await adapter.initCdpSession();
            emit(adapter, 'Runtime.executionContextDestroyed', {executionContextId: 1});
            expect(ws.closed).to.equal(true);
            adapter.disposed = true;
        });
    });

    describe('extension-lib detection', function () {
        it('exposes the shared detector', function () {
            const bundle = findUpwards(
                'vscode-plugin/extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js',
            );
            if (!bundle) {
                this.skip();
                return;
            }
            const mod = require(bundle) as {
                hexanaExtensionLib: {org: {jetbrains: {hexana: {vscode: {lib: {
                    detectAttachTarget(commandLine: string, windows: boolean, executable: string | null): AttachTarget | null;
                    detectAttachTargetFromArgs(executable: string, args: string[]): AttachTarget | null;
                }}}}}};
            };
            const lib = mod.hexanaExtensionLib.org.jetbrains.hexana.vscode.lib;
            const target = lib.detectAttachTarget('/usr/local/bin/wasmtime run -g 0.0.0.0:4321 /w/app.wasm', false, null);
            expect(target).to.include({runtime: 'wasmtime', host: '127.0.0.1', port: 4321, wasmPath: '/w/app.wasm'});
            expect(lib.detectAttachTarget('node --inspect-brk=9333 server.js', false, null))
                .to.include({runtime: 'nodejs', port: 9333, needsPortDiscovery: false});
            expect(lib.detectAttachTarget('node --inspect=0 server.js', false, null))
                .to.include({runtime: 'nodejs', port: 0, needsPortDiscovery: true});
            expect(lib.detectAttachTarget('chrome --remote-debugging-port=9222', false, null))
                .to.include({runtime: 'browser', port: 9222, host: null});
            expect(lib.detectAttachTarget('"C:\\my tools\\iwasm.exe" -g=127.0.0.1:1 "C:\\my wasm\\a.wasm"', true, null))
                .to.include({runtime: 'wamr', wasmPath: 'C:\\my wasm\\a.wasm'});
            expect(lib.detectAttachTargetFromArgs('/opt/iwasm', ['-g=127.0.0.1:1', '/my wasm/a.wasm']))
                .to.include({wasmPath: '/my wasm/a.wasm'});
            expect(lib.detectAttachTarget('wasmtime run app.wasm', false, null)).to.equal(null);
        });
    });
});

function parseDap(data: string): Message {
    return JSON.parse(data.substring(data.indexOf('\r\n\r\n') + 4)) as Message;
}

function dapFrame(message: Message): Buffer {
    const json = JSON.stringify(message);
    return Buffer.from(`Content-Length: ${Buffer.byteLength(json, 'utf-8')}\r\n\r\n${json}`, 'utf-8');
}

function findUpwards(relative: string): string | undefined {
    let dir = __dirname;
    for (let i = 0; i < 10; i++) {
        const candidate = path.join(dir, relative);
        if (fs.existsSync(candidate)) return candidate;
        const parent = path.dirname(dir);
        if (parent === dir) break;
        dir = parent;
    }
    return undefined;
}

function clearOutModuleCache(): void {
    const outputDir = path.resolve(OUT) + path.sep;
    for (const key of Object.keys(require.cache)) {
        if (key.startsWith(outputDir)) delete require.cache[key];
    }
}
