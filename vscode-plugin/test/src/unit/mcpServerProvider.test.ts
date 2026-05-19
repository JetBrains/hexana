import * as crypto from 'crypto';
import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import { expect } from 'chai';
import { installVscodeMock, MockMcpStdioServerDefinition, VscodeMock } from './vscode-mock';

describe('Hexana MCP server provider', function () {
    this.timeout(30_000);

    let vscode: VscodeMock;
    let tempRoot: string;
    let previousEnv: NodeJS.ProcessEnv;

    beforeEach(function () {
        previousEnv = { ...process.env };
        tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-mcp-provider-test-'));
        process.env.HEXANA_MCP_STORAGE_DIR = path.join(tempRoot, 'storage');
        vscode = installVscodeMock();
        clearExtensionModuleCache();
    });

    afterEach(function () {
        vscode.restore();
        process.env = previousEnv;
        fs.rmSync(tempRoot, { recursive: true, force: true });
        clearExtensionModuleCache();
    });

    it('does not publish or resolve the server when MCP is disabled', async function () {
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = 'http://127.0.0.1:1/unreachable';
        vscode.settings['hexana.mcp.enabled'] = false;

        const providerModule = requireExtensionModule('mcpServerProvider');
        providerModule.registerMcpServerDefinitionProvider(extensionContext(tempRoot), {
            isTelemetryEnabled: () => true,
        });

        const provider = vscode.providers.get('hexana.vscode.mcp') as McpProvider;
        expect(await provider.provideMcpServerDefinitions()).to.deep.equal([]);
        expect(await provider.resolveMcpServerDefinition(new MockMcpStdioServerDefinition('Hexana MCP', '/missing')))
            .to.equal(undefined);
        expect(vscode.messages).to.deep.equal([]);
    });

    it('publishes a quiet stdio server and resolves it through the downloaded launcher', async function () {
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const providerModule = requireExtensionModule('mcpServerProvider');
            const context = extensionContext(tempRoot);
            providerModule.registerMcpServerDefinitionProvider(context, {
                isTelemetryEnabled: () => false,
            });

            const provider = vscode.providers.get('hexana.vscode.mcp') as McpProvider;
            const definitions = await provider.provideMcpServerDefinitions();
            expect(definitions).to.have.length(1);
            expect(definitions[0].label).to.equal('Hexana MCP');
            expect(definitions[0].args).to.deep.equal([
                'stdio',
                '--log-level',
                'quiet',
                '--telemetry-enabled',
                'false',
            ]);

            const resolved = await provider.resolveMcpServerDefinition(definitions[0]);
            expect(resolved).to.not.equal(undefined);
            expect(resolved!.command).to.equal(path.join(
                process.env.HEXANA_MCP_STORAGE_DIR!,
                'server',
                'bin',
                process.platform === 'win32' ? 'server.bat' : 'server',
            ));
            expect((resolved!.cwd as { fsPath: string } | undefined)?.fsPath)
                .to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
            expect(resolved!.version).to.equal('0.7.0');
            expect(resolved!.env.HEXANA_MCP_TELEMETRY_ENABLED).to.equal('false');
            expect(vscode.messages).to.deep.equal([]);
        } finally {
            await server.close();
        }
    });

    it('passes configured Java home and log level into the MCP server definition', async function () {
        vscode.settings['hexana.mcp.javaHome'] = '/opt/jbr-21';
        vscode.settings['hexana.mcp.logLevel'] = 'debug';
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const providerModule = requireExtensionModule('mcpServerProvider');
            providerModule.registerMcpServerDefinitionProvider(extensionContext(tempRoot), {
                isTelemetryEnabled: () => true,
            });

            const provider = vscode.providers.get('hexana.vscode.mcp') as McpProvider;
            const definitions = await provider.provideMcpServerDefinitions();

            expect(definitions[0].args).to.include('debug');
            expect(definitions[0].env.JAVA_HOME).to.equal('/opt/jbr-21');
            expect(definitions[0].env.HEXANA_MCP_TELEMETRY_ENABLED).to.equal('true');
            await waitForFile(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'));
        } finally {
            await server.close();
        }
    });
});

interface McpProvider {
    provideMcpServerDefinitions(): Promise<MockMcpStdioServerDefinition[]>;
    resolveMcpServerDefinition(server: MockMcpStdioServerDefinition): Promise<MockMcpStdioServerDefinition | undefined>;
}

function extensionContext(tempRoot: string): any {
    return {
        extensionPath: tempRoot,
        extension: {
            packageJSON: {
                version: '0.7.0',
            },
        },
        globalStorageUri: {
            fsPath: path.join(tempRoot, 'global-storage'),
        },
        subscriptions: [],
    };
}

function createServerArchive(tempRoot: string, version: string): string {
    const sourceRoot = path.join(tempRoot, `server-${version}-source`);
    const serverBin = path.join(sourceRoot, 'server', 'bin');
    fs.mkdirSync(serverBin, { recursive: true });
    const posixLauncherPath = path.join(serverBin, 'server');
    const windowsLauncherPath = path.join(serverBin, 'server.bat');
    fs.writeFileSync(
        posixLauncherPath,
        `#!/usr/bin/env sh\necho hexana-mcp ${version}\n`,
    );
    fs.writeFileSync(windowsLauncherPath, `@echo off\r\necho hexana-mcp ${version}\r\n`);
    if (process.platform !== 'win32') {
        fs.chmodSync(posixLauncherPath, 0o755);
    }

    const archivePath = path.join(tempRoot, `server-${version}.zip`);
    createZipArchive(path.join(sourceRoot, 'server'), archivePath);
    return archivePath;
}

function createZipArchive(sourceDir: string, archivePath: string): void {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const AdmZip = require('adm-zip') as new () => {
        addLocalFolder(localPath: string, zipPath?: string): void;
        writeZip(targetPath: string): void;
    };
    const zip = new AdmZip();
    zip.addLocalFolder(sourceDir, 'server');
    zip.writeZip(archivePath);
}

async function startReleaseServer(archivePath: string, version: string): Promise<{ apiUrl: string; close(): Promise<void> }> {
    const digest = crypto.createHash('sha256').update(fs.readFileSync(archivePath)).digest('hex');
    const server = http.createServer((request, response) => {
        if (request.url?.startsWith('/repos/example/hexana/releases/tags/')) {
            response.setHeader('Content-Type', 'application/json');
            response.end(JSON.stringify({
                tag_name: version,
                assets: [
                    {
                        name: `server-${version}.zip`,
                        browser_download_url: baseUrl(server) + `/download/server-${version}.zip`,
                        digest: `sha256:${digest}`,
                    },
                ],
            }));
            return;
        }

        if (request.url === `/download/server-${version}.zip`) {
            response.setHeader('Content-Type', 'application/zip');
            fs.createReadStream(archivePath).pipe(response);
            return;
        }

        response.statusCode = 404;
        response.end('not found');
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
    return {
        apiUrl: baseUrl(server) + '/repos/example/hexana/releases',
        close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
    };
}

function baseUrl(server: http.Server): string {
    const address = server.address();
    if (!address || typeof address === 'string') {
        throw new Error('Test server did not bind to a TCP port');
    }
    return `http://127.0.0.1:${address.port}`;
}

function clearExtensionModuleCache(): void {
    for (const key of Object.keys(require.cache)) {
        if (key.includes(`${path.sep}vscode-plugin${path.sep}out${path.sep}mcpServer`)) {
            delete require.cache[key];
        }
    }
}

function requireExtensionModule(moduleName: string): any {
    return require(path.join(__dirname, '..', '..', '..', '..', '..', 'out', moduleName));
}

async function waitForFile(filePath: string): Promise<void> {
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
        if (fs.existsSync(filePath)) {
            return;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error(`Timed out waiting for ${filePath}`);
}
