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

export function downloadedVsCodeVersionArguments(platform: NodeJS.Platform, uid?: number): string[] {
    return platform === 'linux' && uid === 0 ? ['--no-sandbox', '--version'] : ['--version'];
}
