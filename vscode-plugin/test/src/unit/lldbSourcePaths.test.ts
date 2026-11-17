import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {expect} from 'chai';
import {installVscodeMock, VscodeMock} from './vscode-mock';

interface Source {
    path?: string;
    name?: string;
    sourceReference?: number;
}

interface Message {
    seq: number;
    type: string;
    command?: string;
    success?: boolean;
    body?: {stackFrames: Array<{id: number; line: number; source: Source}>};
    arguments?: unknown;
}

interface Proxy {
    handleMessage(message: Message): void;
    onStdoutData(chunk: Buffer): void;
    onDidSendMessage(listener: (message: Message) => void): {dispose(): void};
    dispose(): void;
}

describe('LLDB stack trace source paths', function () {
    let vscode: VscodeMock;
    let tempRoot: string;
    let proxy: Proxy;
    let localSource: string;
    let nextSeq: number;

    beforeEach(function () {
        tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-lldb-source-test-'));
        localSource = createSource('checkout/debug_add.rs');
        vscode = installVscodeMock();
        const mock = vscode.module as {window: {createOutputChannel?: unknown}};
        mock.window.createOutputChannel = () => ({appendLine: () => undefined});
        clearDebugModuleCache();
        const {LldbDapProxy} = require(path.join(__dirname, '..', '..', '..', '..', '..', 'out', 'LldbDapDebugAdapter'));
        proxy = new LldbDapProxy('unused', [], false);
        nextSeq = 1;
        registerSource(localSource);
    });

    afterEach(function () {
        proxy?.dispose();
        vscode.restore();
        clearDebugModuleCache();
        fs.rmSync(tempRoot, {recursive: true, force: true});
    });

    it('remaps the absolute macOS build-machine path from the DWARF fixture', function () {
        expect(remap('/Users/dev/git/Hexana/vscode-plugin/test/fixtures/debug_add.rs'))
            .to.deep.equal({path: localSource, name: 'debug_add.rs', sourceReference: 0});
    });

    it('remaps a missing Windows build-machine path on any host', function () {
        expect(remap('Z:\\build-machine\\fixtures\\debug_add.rs'))
            .to.deep.equal({path: localSource, name: 'debug_add.rs', sourceReference: 0});
    });

    it('still remaps relative DWARF paths', function () {
        expect(remap('fixtures/debug_add.rs').path).to.equal(localSource);
        expect(remap('debug_add.rs').path).to.equal(localSource);
    });

    it('preserves an existing absolute source even if another file shares its name', function () {
        const existing = createSource('system/debug_add.rs');
        expect(remap(existing)).to.deep.equal({path: existing, sourceReference: 7});
    });

    it('leaves an unknown source and its source reference unchanged', function () {
        expect(remap('/build-machine/unknown.rs'))
            .to.deep.equal({path: '/build-machine/unknown.rs', sourceReference: 7});
    });

    it('does not guess between multiple registered files with the same basename', function () {
        registerSource(createSource('other/debug_add.rs'));
        const original = '/build-machine/debug_add.rs';
        expect(remap(original)).to.deep.equal({path: original, sourceReference: 7});
        expect(remap('debug_add.rs')).to.deep.equal({path: 'debug_add.rs', sourceReference: 7});
    });

    it('retains suffix disambiguation for relative paths', function () {
        registerSource(createSource('other/debug_add.rs'));
        expect(remap('checkout/debug_add.rs').path).to.equal(localSource);
    });

    it('does not replace a source with a registered file that no longer exists', function () {
        fs.unlinkSync(localSource);
        expect(remap('debug_add.rs')).to.deep.equal({path: 'debug_add.rs', sourceReference: 7});
    });

    it('leaves synthetic LLDB locations and sources without paths alone', function () {
        registerSource(createSource('checkout/dyld`_dyld_start'));
        expect(remap('/usr/lib/dyld`_dyld_start').path).to.equal('/usr/lib/dyld`_dyld_start');
        expect(forward([{name: 'disassembly', sourceReference: 7}]))
            .to.deep.equal([{name: 'disassembly', sourceReference: 7}]);
    });

    it('remaps every stack frame without changing frame locations', function () {
        expect(forward([
            {path: '/build-machine/debug_add.rs'},
            {path: 'debug_add.rs'},
        ])).to.deep.equal([
            {path: localSource, name: 'debug_add.rs'},
            {path: localSource, name: 'debug_add.rs'},
        ]);
    });

    function createSource(relativePath: string): string {
        const file = path.join(tempRoot, relativePath);
        fs.mkdirSync(path.dirname(file), {recursive: true});
        fs.writeFileSync(file, 'fn add(a: i32, b: i32) -> i32 { a + b }\n');
        return file;
    }

    function registerSource(file: string): void {
        proxy.handleMessage({
            seq: nextSeq++, type: 'request', command: 'setBreakpoints',
            arguments: {source: {path: file}, breakpoints: [{line: 10}]},
        });
    }

    function remap(sourcePath: string): Source {
        return forward([{path: sourcePath, sourceReference: 7}])[0];
    }

    function forward(sources: Source[]): Source[] {
        let received: Message | undefined;
        const subscription = proxy.onDidSendMessage(message => { received = message; });
        const frames = sources.map((source, id) => ({id, line: 10, source}));
        const message: Message = {
            seq: nextSeq++, type: 'response', command: 'stackTrace', success: true,
            body: {stackFrames: frames},
        };
        const body = JSON.stringify(message);
        proxy.onStdoutData(Buffer.from(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`));
        subscription.dispose();
        expect(received?.body?.stackFrames.map(({id, line}) => ({id, line})))
            .to.deep.equal(frames.map(({id, line}) => ({id, line})));
        return received!.body!.stackFrames.map(frame => frame.source);
    }
});

function clearDebugModuleCache(): void {
    const outputDir = path.resolve(__dirname, '..', '..', '..', '..', '..', 'out') + path.sep;
    for (const key of Object.keys(require.cache)) {
        if (key.startsWith(outputDir)) delete require.cache[key];
    }
}
