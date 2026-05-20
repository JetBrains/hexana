import * as crypto from 'crypto';
import * as fs from 'fs';
import * as http from 'http';
import * as os from 'os';
import * as path from 'path';
import { expect } from 'chai';
import { installVscodeMock, VscodeMock } from './vscode-mock';

interface TestReleaseServer {
    apiUrl: string;
    close(): Promise<void>;
    requestCounts: {
        metadata: number;
        archive: number;
    };
    requestPaths: string[];
}

describe('Hexana MCP server downloader', function () {
    this.timeout(30_000);

    let vscode: VscodeMock;
    let tempRoot: string;
    let previousEnv: NodeJS.ProcessEnv;

    beforeEach(function () {
        previousEnv = { ...process.env };
        tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hexana-mcp-downloader-test-'));
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

    it('downloads, verifies, extracts, marks version, and reuses an already prepared latest server', async function () {
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            const firstUri = await downloader.prepareHexanaMcpServer(context);
            expect(firstUri?.fsPath).to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
            expect(server.requestCounts.metadata, 'production must hit the tag-based metadata endpoint').to.equal(1);
            expect(server.requestPaths[0]).to.equal('/repos/example/hexana/releases/tags/v0.7.0');
            expect(server.requestCounts.archive).to.equal(1);
            expect(fs.readFileSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'), 'utf8').trim())
                .to.equal('0.7.0');
            expect(fs.existsSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, '.download.lock'))).to.equal(false);

            const launcherPath = downloader.getHexanaMcpServerLauncherPath(context);
            expect(fs.existsSync(launcherPath)).to.equal(true);
            if (process.platform !== 'win32') {
                fs.accessSync(launcherPath, fs.constants.X_OK);
            }

            const secondUri = await downloader.prepareHexanaMcpServer(context);
            expect(secondUri?.fsPath).to.equal(firstUri?.fsPath);
            expect(server.requestCounts.archive).to.equal(1);
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('0.7.0');
        } finally {
            await server.close();
        }
    });

    it('ignores stale version markers when the launcher is missing', async function () {
        const downloader = requireExtensionModule('mcpServerDownloader');
        const context = extensionContext(tempRoot);
        fs.mkdirSync(process.env.HEXANA_MCP_STORAGE_DIR!, { recursive: true });
        fs.writeFileSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'), '0.7.0');

        expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal(undefined);
    });

    it('returns undefined without user notification when background download fails quietly', async function () {
        const server = await startBrokenDownloadReleaseServer('0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const result = await downloader.prepareHexanaMcpServer(extensionContext(tempRoot), {
                showErrorNotification: false,
            });

            expect(result).to.equal(undefined);
            expect(vscode.messages).to.deep.equal([]);
        } finally {
            await server.close();
        }
    });

    it('keeps an installed server when latest release metadata is unavailable', async function () {
        const server = await startUnavailableMetadataReleaseServer();
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;
        createInstalledServer(process.env.HEXANA_MCP_STORAGE_DIR!, '0.7.0');

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const result = await downloader.prepareHexanaMcpServer(extensionContext(tempRoot), {
                showErrorNotification: false,
            });

            expect(result?.fsPath).to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
            expect(fs.readFileSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'), 'utf8').trim())
                .to.equal('0.7.0');
            expect(server.requestCounts.archive).to.equal(0);
        } finally {
            await server.close();
        }
    });

    it('shows retry/download notification on foreground failure', async function () {
        const server = await startBrokenDownloadReleaseServer('0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const result = await downloader.prepareHexanaMcpServer(extensionContext(tempRoot));

            expect(result).to.equal(undefined);
            expect(vscode.messages).to.deep.equal(['Hexana MCP server could not be downloaded automatically.']);
        } finally {
            await server.close();
        }
    });

    it('does not install a server and removes the lock after checksum mismatch', async function () {
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0', '0'.repeat(64));
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const result = await downloader.prepareHexanaMcpServer(extensionContext(tempRoot), {
                showErrorNotification: false,
            });

            expect(result).to.equal(undefined);
            expect(fs.existsSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'))).to.equal(false);
            expect(fs.existsSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, '.download.lock'))).to.equal(false);
        } finally {
            await server.close();
        }
    });

    it('keeps the existing server when replacing the downloaded server fails', async function () {
        const archivePath = createServerArchive(tempRoot, '0.8.0');
        const server = await startReleaseServer(archivePath, '0.8.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;
        createInstalledServer(process.env.HEXANA_MCP_STORAGE_DIR!, '0.7.0');

        const originalRename = fs.promises.rename;
        fs.promises.rename = (async (oldPath: fs.PathLike, newPath: fs.PathLike) => {
            if (String(oldPath).includes('.server-staged-') && String(newPath).endsWith(`${path.sep}server`)) {
                throw new Error('Injected server replacement failure');
            }
            return originalRename(oldPath, newPath);
        }) as typeof fs.promises.rename;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot, '0.8.0');
            const result = await downloader.prepareHexanaMcpServer(context, {
                showErrorNotification: false,
            });

            expect(result).to.equal(undefined);
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('0.7.0');
            expect(fs.readFileSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'), 'utf8').trim())
                .to.equal('0.7.0');
            expect(fs.existsSync(downloader.getHexanaMcpServerLauncherPath(context))).to.equal(true);
            expect(fs.existsSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, '.download.lock'))).to.equal(false);
        } finally {
            fs.promises.rename = originalRename;
            await server.close();
        }
    });

    it('keeps the new server installed when removing the previous backup fails', async function () {
        const archivePath = createServerArchive(tempRoot, '0.8.0');
        const server = await startReleaseServer(archivePath, '0.8.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;
        createInstalledServer(process.env.HEXANA_MCP_STORAGE_DIR!, '0.7.0');

        const originalRm = fs.promises.rm;
        fs.promises.rm = (async (rmPath: fs.PathLike, options?: fs.RmOptions) => {
            if (String(rmPath).includes('.server-backup-')) {
                throw new Error('Injected backup removal failure');
            }
            return originalRm(rmPath, options);
        }) as typeof fs.promises.rm;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot, '0.8.0');
            const result = await downloader.prepareHexanaMcpServer(context, {
                showErrorNotification: false,
            });

            expect(result?.fsPath).to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('0.8.0');
            expect(fs.readFileSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'), 'utf8').trim())
                .to.equal('0.8.0');
        } finally {
            fs.promises.rm = originalRm;
            await server.close();
        }
    });

    it('removes a stale download lock and retries the download', async function () {
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const storageDir = process.env.HEXANA_MCP_STORAGE_DIR!;
            fs.mkdirSync(storageDir, { recursive: true });
            const lockPath = path.join(storageDir, '.download.lock');
            fs.writeFileSync(lockPath, '');
            const staleTimestamp = new Date(Date.now() - 180_000);
            fs.utimesSync(lockPath, staleTimestamp, staleTimestamp);

            const downloader = requireExtensionModule('mcpServerDownloader');
            const result = await downloader.prepareHexanaMcpServer(extensionContext(tempRoot), {
                showErrorNotification: false,
            });

            expect(result?.fsPath).to.equal(path.join(storageDir, 'server'));
            expect(server.requestCounts.archive).to.equal(1);
            expect(fs.existsSync(lockPath)).to.equal(false);
        } finally {
            await server.close();
        }
    });

    it('Update Server command reports already up to date without redownloading the same version', async function () {
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);
            await downloader.prepareHexanaMcpServer(context);
            downloader.registerHexanaMcpServerCommands(context);

            await vscode.commands.get('hexana.mcp.updateServer')?.();

            expect(server.requestCounts.archive).to.equal(1);
            expect(vscode.messages).to.contain('Hexana MCP server is already up to date (0.7.0).');
        } finally {
            await server.close();
        }
    });

    it('Reinstall Server command redownloads the same version to repair an incomplete install', async function () {
        const archivePath = createServerArchive(tempRoot, '0.7.0');
        const server = await startReleaseServer(archivePath, '0.7.0');
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const storageDir = process.env.HEXANA_MCP_STORAGE_DIR!;
            const serverDir = path.join(storageDir, 'server');
            fs.mkdirSync(serverDir, { recursive: true });
            fs.writeFileSync(path.join(storageDir, 'server.version'), '0.7.0\n', 'utf8');

            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);
            downloader.registerHexanaMcpServerCommands(context);

            await vscode.commands.get('hexana.mcp.reinstallServer')?.();

            expect(server.requestCounts.archive).to.equal(1);
            expect(fs.existsSync(downloader.getHexanaMcpServerLauncherPath(context))).to.equal(true);
            expect(vscode.messages).to.contain('Hexana MCP server is reinstalled (0.7.0).');
        } finally {
            await server.close();
        }
    });

    it('Reinstall Server does not downgrade a newer installed server when release metadata is unavailable', async function () {
        const server = await startUnavailableMetadataReleaseServer();
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;
        createInstalledServer(process.env.HEXANA_MCP_STORAGE_DIR!, '0.7.0');

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot, '0.1.0');
            downloader.registerHexanaMcpServerCommands(context);

            await vscode.commands.get('hexana.mcp.reinstallServer')?.();

            expect(server.requestCounts.archive).to.equal(0);
            expect(fs.readFileSync(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server.version'), 'utf8').trim())
                .to.equal('0.7.0');
        } finally {
            await server.close();
        }
    });

    it('Show Server Status reports the installed server when release metadata is unavailable', async function () {
        const server = await startUnavailableMetadataReleaseServer();
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;
        createInstalledServer(process.env.HEXANA_MCP_STORAGE_DIR!, '0.7.0');

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot, '0.2.0');
            downloader.registerHexanaMcpServerCommands(context);

            await vscode.commands.get('hexana.mcp.showServerStatus')?.();

            expect(vscode.messages.some((message) => message.includes('Hexana MCP server: 0.7.0'))).to.equal(true);
            expect(server.requestCounts.archive).to.equal(0);
        } finally {
            await server.close();
        }
    });
});

function extensionContext(tempRoot: string, version = '0.7.0'): any {
    return {
        extensionPath: tempRoot,
        extension: {
            packageJSON: {
                version,
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
    createInstalledServer(sourceRoot, version);

    const archivePath = path.join(tempRoot, `server-${version}.zip`);
    createZipArchive(path.join(sourceRoot, 'server'), archivePath);
    return archivePath;
}

function createInstalledServer(storageDir: string, version: string): void {
    const serverBin = path.join(storageDir, 'server', 'bin');
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
    fs.writeFileSync(path.join(storageDir, 'server.version'), `${version}\n`, 'utf8');
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

async function startReleaseServer(
    archivePath: string,
    version: string,
    digest = sha256(archivePath),
): Promise<TestReleaseServer> {
    const requestCounts = { metadata: 0, archive: 0 };
    const requestPaths: string[] = [];
    const server = http.createServer((request, response) => {
        requestPaths.push(request.url ?? '');
        if (request.url?.startsWith('/repos/example/hexana/releases/tags/')) {
            requestCounts.metadata++;
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
            requestCounts.archive++;
            response.setHeader('Content-Type', 'application/zip');
            fs.createReadStream(archivePath).pipe(response);
            return;
        }

        response.statusCode = 404;
        response.end('not found');
    });

    await listen(server);
    return {
        apiUrl: baseUrl(server) + '/repos/example/hexana/releases',
        close: () => close(server),
        requestCounts,
        requestPaths,
    };
}

async function startBrokenDownloadReleaseServer(version: string): Promise<TestReleaseServer> {
    const requestCounts = { metadata: 0, archive: 0 };
    const server = http.createServer((request, response) => {
        if (request.url?.startsWith('/repos/example/hexana/releases/tags/')) {
            requestCounts.metadata++;
            response.setHeader('Content-Type', 'application/json');
            response.end(JSON.stringify({
                tag_name: version,
                assets: [
                    {
                        name: `server-${version}.zip`,
                        browser_download_url: baseUrl(server) + `/download/server-${version}.zip`,
                        digest: `sha256:${'0'.repeat(64)}`,
                    },
                ],
            }));
            return;
        }

        if (request.url === `/download/server-${version}.zip`) {
            requestCounts.archive++;
            response.statusCode = 500;
            response.end('download failure');
            return;
        }

        response.statusCode = 404;
        response.end('not found');
    });
    await listen(server);
    return {
        apiUrl: baseUrl(server) + '/repos/example/hexana/releases',
        close: () => close(server),
        requestCounts,
        requestPaths: [],
    };
}

async function startUnavailableMetadataReleaseServer(): Promise<TestReleaseServer> {
    const requestCounts = { metadata: 0, archive: 0 };
    const server = http.createServer((request, response) => {
        if (request.url?.startsWith('/repos/example/hexana/releases/tags/')) {
            requestCounts.metadata++;
            response.statusCode = 503;
            response.end('unavailable');
            return;
        }

        requestCounts.archive++;
        response.statusCode = 500;
        response.end('unexpected archive request');
    });
    await listen(server);
    return {
        apiUrl: baseUrl(server) + '/repos/example/hexana/releases',
        close: () => close(server),
        requestCounts,
        requestPaths: [],
    };
}

function listen(server: http.Server): Promise<void> {
    return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
}

function close(server: http.Server): Promise<void> {
    return new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

function baseUrl(server: http.Server): string {
    const address = server.address();
    if (!address || typeof address === 'string') {
        throw new Error('Test server did not bind to a TCP port');
    }
    return `http://127.0.0.1:${address.port}`;
}

function sha256(filePath: string): string {
    return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
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
