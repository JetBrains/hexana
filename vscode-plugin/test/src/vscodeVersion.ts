import * as fs from 'fs';
import * as path from 'path';

export function assertDownloadedVsCodeVersion(requestedVersion: string, actualVersion: string): void {
    if (!/^\d+\.\d+\.\d+$/.test(actualVersion)) {
        throw new Error(`Downloaded VS Code executable reported an invalid version: ${actualVersion}`);
    }
    if (requestedVersion !== 'max' && actualVersion !== requestedVersion) {
        throw new Error(
            `Requested VS Code ${requestedVersion}, but downloaded executable reports ${actualVersion}. Refusing a cache/downloader fallback.`,
        );
    }
}

export function downloadedVsCodeProductMetadataPath(codeFolder: string, platform: NodeJS.Platform): string {
    const appRoot = platform === 'darwin'
        ? ['Contents', 'Resources', 'app']
        : ['resources', 'app'];
    const legacyPath = path.join(codeFolder, ...appRoot, 'product.json');
    if (platform !== 'win32' || fs.existsSync(legacyPath) || !fs.existsSync(codeFolder)) {
        return legacyPath;
    }

    const nestedCandidates = fs.readdirSync(codeFolder, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => path.join(codeFolder, entry.name, 'resources', 'app', 'product.json'))
        .filter(fs.existsSync);
    if (nestedCandidates.length > 1) {
        throw new Error(
            `Ambiguous Windows VS Code product metadata under ${codeFolder}: ${nestedCandidates.join(', ')}`,
        );
    }
    return nestedCandidates[0] ?? legacyPath;
}

export function parseDownloadedVsCodeVersion(productMetadata: string): string {
    const version = (JSON.parse(productMetadata) as { version?: unknown }).version;
    if (typeof version !== 'string') {
        throw new Error('Downloaded VS Code product metadata does not contain a string version');
    }
    return version;
}
