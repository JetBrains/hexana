import * as crypto from 'crypto';
import * as fs from 'fs';
import * as http from 'http';
import * as https from 'https';
import * as os from 'os';
import * as path from 'path';
import * as semver from 'semver';
import {expect} from 'chai';
import {installVscodeMock, VscodeMock} from './vscode-mock';

interface TestReleaseServer {
    apiUrl: string;
    close(): Promise<void>;
    requestCounts: {
        metadata: number;
        archive: number;
    };
    requestPaths: string[];
}

interface TestRelease {
    version: string;
    archivePath?: string;
    digest?: string | null;
    assets?: TestReleaseAsset[];
}

interface TestReleaseAsset {
    name: string;
    archivePath?: string;
    digest?: string | null;
}

interface LiveGitHubRelease {
    tag_name: string;
    assets: LiveGitHubReleaseAsset[];
}

interface LiveGitHubReleaseAsset {
    name: string;
    digest?: string;
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
            expect(server.requestCounts.metadata, 'production must hit the release list metadata endpoint').to.equal(1);
            expect(server.requestPaths[0]).to.equal('/repos/example/hexana/releases?per_page=100&page=1');
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

    it('downloads the newest semver-tagged MCP server release', async function () {
        const archivePath090 = createServerArchive(tempRoot, '0.9.0');
        const archivePath0100 = createServerArchive(tempRoot, '0.10.0');
        const server = await startReleasesServer([
            {version: '0.9.0', archivePath: archivePath090},
            {version: '0.10.0', archivePath: archivePath0100},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot, '0.2.0');

            const result = await downloader.prepareHexanaMcpServer(context);

            expect(result?.fsPath).to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
            expect(server.requestPaths[0]).to.equal('/repos/example/hexana/releases?per_page=100&page=1');
            expect(server.requestPaths).to.contain('/download/server-0.10.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('0.10.0');
        } finally {
            await server.close();
        }
    });

    it('continues to later GitHub release pages before falling back', async function () {
        const invalidPage = Array.from({length: 100}, (_, index) => ({
            version: `nightly-${index}`,
            archivePath: createServerArchive(tempRoot, `nightly-${index}`),
        }));
        const archivePath = createServerArchive(tempRoot, '1.0.0');
        const server = await startPagedReleasesServer([
            invalidPage,
            [{version: '1.0.0', archivePath}],
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/repos/example/hexana/releases?per_page=100&page=1');
            expect(server.requestPaths).to.contain('/repos/example/hexana/releases?per_page=100&page=2');
            expect(server.requestPaths).to.contain('/download/server-1.0.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.0.0');
        } finally {
            await server.close();
        }
    });

    it('selects a valid same-release server asset after an invalid matching asset', async function () {
        const invalidArchivePath = createServerArchive(tempRoot, '2.1.0-invalid');
        const validArchivePath = createServerArchive(tempRoot, '2.1.0-valid');
        const server = await startReleasesServer([
            {
                version: '2.1.0',
                assets: [
                    {
                        name: 'server-2.1.0-invalid.zip',
                        archivePath: invalidArchivePath,
                        digest: 'not-a-sha',
                    },
                    {
                        name: 'server-2.1.0-valid.zip',
                        archivePath: validArchivePath,
                    },
                ],
            },
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-2.1.0-valid.zip');
            expect(server.requestPaths).not.to.contain('/download/server-2.1.0-invalid.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('2.1.0');
        } finally {
            await server.close();
        }
    });

    it('uses numeric semver ordering across major, minor, and patch segments', async function () {
        const archivePath1999 = createServerArchive(tempRoot, '1.99.99');
        const archivePath200 = createServerArchive(tempRoot, '2.0.0');
        const archivePath11100 = createServerArchive(tempRoot, '1.110.0');
        const server = await startReleasesServer([
            {version: '1.99.99', archivePath: archivePath1999},
            {version: '2.0.0', archivePath: archivePath200},
            {version: '1.110.0', archivePath: archivePath11100},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-2.0.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('2.0.0');
        } finally {
            await server.close();
        }
    });

    it('understands v-prefixed semver tags', async function () {
        const archivePath099 = createServerArchive(tempRoot, '0.9.9');
        const archivePathV100 = createServerArchive(tempRoot, 'v1.0.0');
        const server = await startReleasesServer([
            {version: '0.9.9', archivePath: archivePath099},
            {version: 'v1.0.0', archivePath: archivePathV100},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-v1.0.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('v1.0.0');
        } finally {
            await server.close();
        }
    });

    it('prefers a stable release over a prerelease with the same version core', async function () {
        const archivePathRc = createServerArchive(tempRoot, '1.2.3-rc.1');
        const archivePathStable = createServerArchive(tempRoot, '1.2.3');
        const server = await startReleasesServer([
            {version: '1.2.3-rc.1', archivePath: archivePathRc},
            {version: '1.2.3', archivePath: archivePathStable},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.2.3.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.2.3');
        } finally {
            await server.close();
        }
    });

    it('orders prerelease identifiers according to semver precedence', async function () {
        const archivePathBeta2 = createServerArchive(tempRoot, '1.2.3-beta.2');
        const archivePathBeta11 = createServerArchive(tempRoot, '1.2.3-beta.11');
        const archivePathAlpha = createServerArchive(tempRoot, '1.2.3-alpha');
        const server = await startReleasesServer([
            {version: '1.2.3-beta.2', archivePath: archivePathBeta2},
            {version: '1.2.3-beta.11', archivePath: archivePathBeta11},
            {version: '1.2.3-alpha', archivePath: archivePathAlpha},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.2.3-beta.11.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.2.3-beta.11');
        } finally {
            await server.close();
        }
    });

    it('allows a higher prerelease to beat a lower stable release', async function () {
        const archivePathStable = createServerArchive(tempRoot, '1.2.9');
        const archivePathPrerelease = createServerArchive(tempRoot, '1.3.0-beta.1');
        const server = await startReleasesServer([
            {version: '1.2.9', archivePath: archivePathStable},
            {version: '1.3.0-beta.1', archivePath: archivePathPrerelease},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.3.0-beta.1.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.3.0-beta.1');
        } finally {
            await server.close();
        }
    });

    it('ignores build metadata when selecting the newest semver core', async function () {
        const archivePathBuild = createServerArchive(tempRoot, '1.2.3+build.5');
        const archivePathNextPatch = createServerArchive(tempRoot, '1.2.4');
        const server = await startReleasesServer([
            {version: '1.2.3+build.5', archivePath: archivePathBuild},
            {version: '1.2.4', archivePath: archivePathNextPatch},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.2.4.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.2.4');
        } finally {
            await server.close();
        }
    });

    it('ignores non-semver release tags even when they have server assets', async function () {
        const archivePathDate = createServerArchive(tempRoot, '2026-05-28');
        const archivePathLatest = createServerArchive(tempRoot, 'latest');
        const archivePathSemver = createServerArchive(tempRoot, '0.8.0');
        const server = await startReleasesServer([
            {version: '2026-05-28', archivePath: archivePathDate},
            {version: 'latest', archivePath: archivePathLatest},
            {version: '0.8.0', archivePath: archivePathSemver},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-0.8.0.zip');
            expect(server.requestPaths).not.to.contain('/download/server-2026-05-28.zip');
            expect(server.requestPaths).not.to.contain('/download/server-latest.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('0.8.0');
        } finally {
            await server.close();
        }
    });

    it('ignores newer semver releases that do not contain a server zip asset', async function () {
        const archivePathPluginOnly = createServerArchive(tempRoot, '2.0.0');
        const archivePathServer = createServerArchive(tempRoot, '1.9.0');
        const server = await startReleasesServer([
            {
                version: '2.0.0',
                assets: [
                    {
                        name: 'hexana-codex-plugin-2.0.0.zip',
                        archivePath: archivePathPluginOnly,
                    },
                ],
            },
            {version: '1.9.0', archivePath: archivePathServer},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.9.0.zip');
            expect(server.requestPaths).not.to.contain('/download/hexana-codex-plugin-2.0.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.9.0');
        } finally {
            await server.close();
        }
    });

    it('ignores newer server assets with missing or invalid SHA-256 digests', async function () {
        const archivePathMissingDigest = createServerArchive(tempRoot, '3.0.0');
        const archivePathInvalidDigest = createServerArchive(tempRoot, '2.0.0');
        const archivePathValid = createServerArchive(tempRoot, '1.0.0');
        const server = await startReleasesServer([
            {
                version: '3.0.0',
                assets: [
                    {
                        name: 'server-3.0.0.zip',
                        archivePath: archivePathMissingDigest,
                        digest: null,
                    },
                ],
            },
            {version: '2.0.0', archivePath: archivePathInvalidDigest, digest: 'not-a-sha'},
            {version: '1.0.0', archivePath: archivePathValid},
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.0.0.zip');
            expect(server.requestPaths).not.to.contain('/download/server-3.0.0.zip');
            expect(server.requestPaths).not.to.contain('/download/server-2.0.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.0.0');
        } finally {
            await server.close();
        }
    });

    it('accepts uppercase SHA-256 digest metadata from GitHub', async function () {
        const archivePath = createServerArchive(tempRoot, '1.4.0');
        const server = await startReleaseServer(archivePath, '1.4.0', sha256(archivePath).toUpperCase());
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.4.0');
        } finally {
            await server.close();
        }
    });

    it('uses the first valid server zip when a release has multiple assets', async function () {
        const pluginArchivePath = createServerArchive(tempRoot, '1.5.0-plugin');
        const serverArchivePath = createServerArchive(tempRoot, '1.5.0');
        const server = await startReleasesServer([
            {
                version: '1.5.0',
                assets: [
                    {
                        name: 'hexana-claude-plugin-1.5.0.zip',
                        archivePath: pluginArchivePath,
                    },
                    {
                        name: 'server-1.5.0.zip',
                        archivePath: serverArchivePath,
                    },
                ],
            },
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            await downloader.prepareHexanaMcpServer(context);

            expect(server.requestPaths).to.contain('/download/server-1.5.0.zip');
            expect(server.requestPaths).not.to.contain('/download/hexana-claude-plugin-1.5.0.zip');
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('1.5.0');
        } finally {
            await server.close();
        }
    });

    it('reports fallback as latest known version when no listed release qualifies and nothing is installed', async function () {
        const archivePathPluginOnly = createServerArchive(tempRoot, '9.0.0');
        const server = await startReleasesServer([
            {
                version: 'not-semver',
                archivePath: createServerArchive(tempRoot, 'not-semver'),
            },
            {
                version: '9.0.0',
                assets: [
                    {
                        name: 'hexana-codex-plugin-9.0.0.zip',
                        archivePath: archivePathPluginOnly,
                    },
                ],
            },
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);
            downloader.registerHexanaMcpServerCommands(context);

            await vscode.commands.get('hexana.mcp.showServerStatus')?.();

            expect(vscode.messages).to.contain('Hexana MCP server is not downloaded. Latest known version: v0.1.0.');
            expect(server.requestCounts.archive).to.equal(0);
        } finally {
            await server.close();
        }
    });

    it('does not downgrade an installed server when release metadata only falls back', async function () {
        const server = await startReleasesServer([
            {
                version: 'not-semver',
                archivePath: createServerArchive(tempRoot, 'not-semver'),
            },
        ]);
        process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL = server.apiUrl;
        createInstalledServer(process.env.HEXANA_MCP_STORAGE_DIR!, '5.0.0');

        try {
            const downloader = requireExtensionModule('mcpServerDownloader');
            const context = extensionContext(tempRoot);

            const result = await downloader.prepareHexanaMcpServer(context, {
                showErrorNotification: false,
            });

            expect(result?.fsPath).to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
            expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal('5.0.0');
            expect(server.requestCounts.archive).to.equal(0);
        } finally {
            await server.close();
        }
    });

    it('downloads the newest real GitHub MCP server release without mocks', async function () {
        if (process.env.HEXANA_RUN_LIVE_MCP_RELEASE_TESTS !== 'true') {
            this.skip();
        }

        this.timeout(180_000);
        delete process.env.HEXANA_MCP_GITHUB_RELEASE_API_URL;
        delete process.env.HEXANA_MCP_RELEASES_PAGE_URL;

        const expectedVersion = await fetchLiveLatestMcpServerReleaseVersion();
        const downloader = requireExtensionModule('mcpServerDownloader');
        const context = extensionContext(tempRoot, 'live-test');

        const result = await downloader.prepareHexanaMcpServer(context, {
            showErrorNotification: false,
        });

        expect(result?.fsPath).to.equal(path.join(process.env.HEXANA_MCP_STORAGE_DIR!, 'server'));
        expect(await downloader.getDownloadedHexanaMcpServerVersion(context)).to.equal(expectedVersion);
        expect(fs.existsSync(downloader.getHexanaMcpServerLauncherPath(context))).to.equal(true);
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
    return startReleasesServer([{version, archivePath, digest}]);
}

async function startReleasesServer(releases: TestRelease[]): Promise<TestReleaseServer> {
    return startPagedReleasesServer([releases]);
}

async function startPagedReleasesServer(pages: TestRelease[][]): Promise<TestReleaseServer> {
    const requestCounts = { metadata: 0, archive: 0 };
    const requestPaths: string[] = [];
    const server = http.createServer((request, response) => {
        requestPaths.push(request.url ?? '');
        if (request.url?.startsWith('/repos/example/hexana/releases?')) {
            requestCounts.metadata++;
            const requestUrl = new URL(request.url, baseUrl(server));
            const page = Number(requestUrl.searchParams.get('page') ?? '1');
            const releases = Number.isInteger(page) && page > 0 ? pages[page - 1] ?? [] : [];
            response.setHeader('Content-Type', 'application/json');
            response.end(JSON.stringify(releases.map((release) => releaseJson(server, release))));
            return;
        }

        const matchingAsset = releaseDownloadAssets(pages.flat()).find(
            (asset) => request.url === `/download/${encodeURIComponent(asset.name)}`,
        );
        if (matchingAsset?.archivePath) {
            requestCounts.archive++;
            response.setHeader('Content-Type', 'application/zip');
            fs.createReadStream(matchingAsset.archivePath).pipe(response);
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

function releaseJson(server: http.Server, release: TestRelease): any {
    const assets = release.assets ?? [
        {
            name: `server-${release.version}.zip`,
            archivePath: release.archivePath,
            digest: release.digest,
        },
    ];

    return {
        tag_name: release.version,
        assets: assets.map((asset) => releaseAssetJson(server, asset)),
    };
}

function releaseAssetJson(server: http.Server, asset: TestReleaseAsset): any {
    const result: any = {
        name: asset.name,
        browser_download_url: baseUrl(server) + `/download/${encodeURIComponent(asset.name)}`,
    };
    const digest = asset.digest ?? (asset.archivePath ? sha256(asset.archivePath) : undefined);
    if (digest !== undefined && asset.digest !== null) {
        result.digest = `sha256:${digest}`;
    }
    return result;
}

function releaseDownloadAssets(releases: TestRelease[]): TestReleaseAsset[] {
    return releases.flatMap((release) => release.assets ?? [
        {
            name: `server-${release.version}.zip`,
            archivePath: release.archivePath,
            digest: release.digest,
        },
    ]);
}

async function startBrokenDownloadReleaseServer(version: string): Promise<TestReleaseServer> {
    const requestCounts = { metadata: 0, archive: 0 };
    const requestPaths: string[] = [];
    const server = http.createServer((request, response) => {
        requestPaths.push(request.url ?? '');
        if (request.url?.startsWith('/repos/example/hexana/releases?')) {
            requestCounts.metadata++;
            response.setHeader('Content-Type', 'application/json');
            response.end(JSON.stringify([{
                tag_name: version,
                assets: [
                    {
                        name: `server-${version}.zip`,
                        browser_download_url: baseUrl(server) + `/download/server-${version}.zip`,
                        digest: `sha256:${'0'.repeat(64)}`,
                    },
                ],
            }]));
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
        requestPaths,
    };
}

async function startUnavailableMetadataReleaseServer(): Promise<TestReleaseServer> {
    const requestCounts = { metadata: 0, archive: 0 };
    const requestPaths: string[] = [];
    const server = http.createServer((request, response) => {
        requestPaths.push(request.url ?? '');
        if (request.url?.startsWith('/repos/example/hexana/releases?')) {
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
        requestPaths,
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

async function fetchLiveLatestMcpServerReleaseVersion(): Promise<string> {
    const releases: LiveGitHubRelease[] = [];
    let page = 1;
    while (true) {
        const nextPage = await getLiveJson<LiveGitHubRelease[]>(`https://api.github.com/repos/JetBrains/hexana/releases?per_page=100&page=${page}`);
        releases.push(...nextPage);
        if (nextPage.length < 100) {
            break;
        }
        page++;
    }

    const candidates = releases
        .map((release) => {
            const version = semver.valid(release.tag_name);
            const hasServerAsset = release.assets.some((asset) =>
                /^server-.+\.zip$/.test(asset.name) && parseGitHubSha256Digest(asset.digest) !== undefined,
            );
            return version && hasServerAsset ? {normalizedVersion: version, version: release.tag_name} : undefined;
        })
        .filter((candidate): candidate is { normalizedVersion: string; version: string } => candidate !== undefined)
        .sort((left, right) => semver.rcompare(left.normalizedVersion, right.normalizedVersion));

    const latest = candidates[0]?.version;
    if (!latest) {
        throw new Error('Real GitHub releases do not contain a semver-tagged server-*.zip asset with a SHA-256 digest');
    }
    return latest;
}

function getLiveJson<T>(url: string): Promise<T> {
    return new Promise((resolve, reject) => {
        const request = https.get(
            url,
            {
                headers: {
                    Accept: 'application/vnd.github+json',
                    'User-Agent': 'Hexana-VSCode-Live-Test',
                    'X-GitHub-Api-Version': '2022-11-28',
                },
            },
            (response) => {
                const statusCode = response.statusCode ?? 0;
                const location = response.headers.location;
                if (statusCode >= 300 && statusCode < 400 && location) {
                    response.resume();
                    getLiveJson<T>(new URL(location, url).toString()).then(resolve, reject);
                    return;
                }

                const chunks: Buffer[] = [];
                response.on('data', (chunk: Buffer) => chunks.push(chunk));
                response.on('end', () => {
                    const body = Buffer.concat(chunks).toString('utf8');
                    if (statusCode < 200 || statusCode >= 300) {
                        reject(new Error(`Live GitHub release metadata request failed with HTTP ${statusCode}: ${body}`));
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
            request.destroy(new Error('Live GitHub release metadata request timed out'));
        });
        request.on('error', reject);
        request.end();
    });
}

function parseGitHubSha256Digest(digest: string | undefined): string | undefined {
    const prefix = 'sha256:';
    if (!digest?.startsWith(prefix)) {
        return undefined;
    }

    const value = digest.slice(prefix.length);
    return /^[a-fA-F0-9]{64}$/.test(value) ? value.toLowerCase() : undefined;
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
