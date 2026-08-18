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
    return path.join(codeFolder, ...appRoot, 'product.json');
}

export function parseDownloadedVsCodeVersion(productMetadata: string): string {
    const version = (JSON.parse(productMetadata) as { version?: unknown }).version;
    if (typeof version !== 'string') {
        throw new Error('Downloaded VS Code product metadata does not contain a string version');
    }
    return version;
}
import * as path from 'path';
