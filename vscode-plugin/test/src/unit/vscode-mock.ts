import Module = require('module');

type CommandHandler = (...args: unknown[]) => unknown;

export interface MockConfiguration {
    values: Record<string, unknown>;
    get<T>(key: string, defaultValue: T): T;
}

export class MockMcpStdioServerDefinition {
    public cwd?: unknown;

    constructor(
        public readonly label: string,
        public command: string,
        public args: string[] = [],
        public env: Record<string, string | number | null> = {},
        public version?: string,
    ) {}
}

export interface VscodeMock {
    commands: Map<string, CommandHandler>;
    messages: string[];
    messageActions: Array<string | undefined>;
    openedExternal: string[];
    providers: Map<string, unknown>;
    settings: Record<string, unknown>;
    module: unknown;
    restore(): void;
}

export function installVscodeMock(): VscodeMock {
    const commands = new Map<string, CommandHandler>();
    const messages: string[] = [];
    const messageActions: Array<string | undefined> = [];
    const openedExternal: string[] = [];
    const providers = new Map<string, unknown>();
    const settings: Record<string, unknown> = {};

    class EventEmitter<T> {
        private listeners: Array<(event: T) => unknown> = [];

        public event = (listener: (event: T) => unknown): { dispose(): void } => {
            this.listeners.push(listener);
            return {
                dispose: () => {
                    this.listeners = this.listeners.filter((candidate) => candidate !== listener);
                },
            };
        };

        public fire(event: T): void {
            for (const listener of this.listeners) {
                listener(event);
            }
        }

        public dispose(): void {
            this.listeners = [];
        }
    }

    const vscodeModule = {
        commands: {
            registerCommand: (name: string, handler: CommandHandler) => {
                commands.set(name, handler);
                return { dispose: () => commands.delete(name) };
            },
            executeCommand: async (name: string, ...args: unknown[]) => {
                const handler = commands.get(name);
                return handler?.(...args);
            },
        },
        env: {
            isTelemetryEnabled: true,
            onDidChangeTelemetryEnabled: () => ({ dispose: () => undefined }),
            openExternal: (uri: { toString(): string }) => {
                openedExternal.push(uri.toString());
                return Promise.resolve(true);
            },
        },
        EventEmitter,
        lm: {
            registerMcpServerDefinitionProvider: (id: string, provider: unknown) => {
                providers.set(id, provider);
                return { dispose: () => providers.delete(id) };
            },
        },
        McpStdioServerDefinition: MockMcpStdioServerDefinition,
        ProgressLocation: {
            Notification: 15,
        },
        Uri: {
            file: (fsPath: string) => ({
                fsPath,
                path: fsPath,
                toString: () => fsPath,
            }),
            parse: (value: string) => ({
                fsPath: value,
                path: value,
                toString: () => value,
            }),
        },
        window: {
            showInformationMessage: async (message: string) => {
                messages.push(message);
                return messageActions.shift();
            },
            withProgress: async (_options: unknown, task: (progress: { report(): void }) => Promise<unknown>) => {
                return task({ report: () => undefined });
            },
        },
        workspace: {
            getConfiguration: (section: string): MockConfiguration => ({
                values: settings,
                get: <T>(key: string, defaultValue: T): T => {
                    const fullKey = section ? `${section}.${key}` : key;
                    if (Object.prototype.hasOwnProperty.call(settings, fullKey)) {
                        return settings[fullKey] as T;
                    }
                    if (Object.prototype.hasOwnProperty.call(settings, key)) {
                        return settings[key] as T;
                    }
                    return defaultValue;
                },
            }),
            onDidChangeConfiguration: () => ({ dispose: () => undefined }),
        },
    };

    const moduleWithLoader = Module as unknown as { _load: (...args: unknown[]) => unknown };
    const originalLoad = moduleWithLoader._load;
    moduleWithLoader._load = function patchedLoad(
        request: string,
        parent: NodeModule | null,
        isMain: boolean,
    ): unknown {
        if (request === 'vscode') {
            return vscodeModule;
        }
        return originalLoad(request, parent, isMain);
    } as (...args: unknown[]) => unknown;

    return {
        commands,
        messages,
        messageActions,
        openedExternal,
        providers,
        settings,
        module: vscodeModule,
        restore: () => {
            moduleWithLoader._load = originalLoad;
        },
    };
}
