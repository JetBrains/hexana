import * as crypto from "crypto";
import * as fs from "fs";
import * as https from "https";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";

const mcpJarVersion = "0.1.0-example";
const mcpJarFileName = `hexana-vscode-mcp-server-${mcpJarVersion}.jar`;
const mcpJarDownloadUrl = `https://example.com/hexana/mcp/${mcpJarFileName}`;
const expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000";

const storageDirName = "mcp";
const versionMarkerFileName = "server.version";
const lockFileName = ".download.lock";

export async function prepareHexanaMcpJar(context: vscode.ExtensionContext): Promise<vscode.Uri | undefined> {
    try {
        return await ensureHexanaMcpJar(context);
    } catch (error) {
        console.warn("[Hexana] MCP server JAR was not prepared", error);
        void vscode.window.showInformationMessage(
            "Hexana MCP server could not be downloaded automatically.",
            "Retry",
            "Open Download Page",
        ).then((action) => {
            if (action === "Retry") {
                void prepareHexanaMcpJar(context);
            } else if (action === "Open Download Page") {
                void vscode.env.openExternal(vscode.Uri.parse("https://example.com/hexana/mcp"));
            }
        });
        return undefined;
    }
}

async function ensureHexanaMcpJar(context: vscode.ExtensionContext): Promise<vscode.Uri> {
    const storageDir = path.join(context.globalStorageUri.fsPath, storageDirName);
    const jarPath = path.join(storageDir, mcpJarFileName);
    const markerPath = path.join(storageDir, versionMarkerFileName);

    await fs.promises.mkdir(storageDir, { recursive: true });

    if (await isPrepared(jarPath, markerPath)) {
        return vscode.Uri.file(jarPath);
    }

    return withDownloadLock(storageDir, async () => {
        if (await isPrepared(jarPath, markerPath)) {
            return vscode.Uri.file(jarPath);
        }

        await vscode.window.withProgress(
            {
                location: vscode.ProgressLocation.Notification,
                title: "Getting Hexana MCP ready...",
                cancellable: false,
            },
            async (progress) => {
                const tempJarPath = path.join(
                    os.tmpdir(),
                    `hexana-mcp-${process.pid}-${Date.now()}-${crypto.randomBytes(6).toString("hex")}.jar`,
                );

                try {
                    progress.report({ increment: 5, message: "Downloading..." });
                    await downloadFile(mcpJarDownloadUrl, tempJarPath);

                    progress.report({ increment: 70, message: "Verifying integrity..." });
                    await verifySha256(tempJarPath);

                    await fs.promises.mkdir(storageDir, { recursive: true });
                    await fs.promises.rename(tempJarPath, jarPath);
                    await fs.promises.writeFile(markerPath, `${mcpJarVersion}\n`, "utf8");
                    progress.report({ increment: 25 });
                } finally {
                    await fs.promises.rm(tempJarPath, { force: true });
                }
            },
        );

        return vscode.Uri.file(jarPath);
    });
}

async function isPrepared(jarPath: string, markerPath: string): Promise<boolean> {
    try {
        const [jarStat, marker] = await Promise.all([
            fs.promises.stat(jarPath),
            fs.promises.readFile(markerPath, "utf8"),
        ]);
        return jarStat.isFile() && marker.trim() === mcpJarVersion;
    } catch {
        return false;
    }
}

async function withDownloadLock<T>(storageDir: string, action: () => Promise<T>): Promise<T> {
    const lockPath = path.join(storageDir, lockFileName);
    const deadline = Date.now() + 120_000;
    let lockHandle: fs.promises.FileHandle | undefined;

    while (!lockHandle) {
        try {
            lockHandle = await fs.promises.open(lockPath, "wx");
        } catch (error) {
            if (!isFileExistsError(error) || Date.now() >= deadline) {
                throw error;
            }
            await delay(500);
        }
    }

    try {
        return await action();
    } finally {
        await lockHandle.close();
        await fs.promises.rm(lockPath, { force: true });
    }
}

function downloadFile(url: string, targetPath: string, redirectsLeft = 5): Promise<void> {
    return new Promise((resolve, reject) => {
        const request = https.get(url, (response) => {
            const statusCode = response.statusCode ?? 0;
            const location = response.headers.location;

            if (statusCode >= 300 && statusCode < 400 && location && redirectsLeft > 0) {
                response.resume();
                const nextUrl = new URL(location, url).toString();
                downloadFile(nextUrl, targetPath, redirectsLeft - 1).then(resolve, reject);
                return;
            }

            if (statusCode < 200 || statusCode >= 300) {
                response.resume();
                reject(new Error(`Download failed with HTTP ${statusCode}`));
                return;
            }

            const file = fs.createWriteStream(targetPath, { flags: "w" });
            response.pipe(file);
            file.on("finish", () => {
                file.close((error) => {
                    if (error) {
                        reject(error);
                    } else {
                        resolve();
                    }
                });
            });
            file.on("error", reject);
        });

        request.setTimeout(60_000, () => {
            request.destroy(new Error("Download timed out"));
        });
        request.on("error", reject);
        request.end();
    });
}

async function verifySha256(filePath: string): Promise<void> {
    const actualSha256 = await sha256(filePath);
    if (actualSha256 !== expectedSha256) {
        throw new Error(`Downloaded MCP server checksum mismatch: expected ${expectedSha256}, got ${actualSha256}`);
    }
}

function sha256(filePath: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const hash = crypto.createHash("sha256");
        const stream = fs.createReadStream(filePath);
        stream.on("data", (chunk) => hash.update(chunk));
        stream.on("end", () => resolve(hash.digest("hex")));
        stream.on("error", reject);
    });
}

function delay(milliseconds: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isFileExistsError(error: unknown): boolean {
    return typeof error === "object" && error !== null && "code" in error && error.code === "EEXIST";
}
