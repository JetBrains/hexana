// The extension-lib bundle runs inside VS Code's Node.js extension host,
// so Node.js built-in modules must be resolved at runtime, not bundled.
// posthog-node transitively depends on several Node built-ins (both
// prefixed `node:fs` and bare `path` / `fs` forms).
const nodeBuiltins = new Set([
    'assert', 'async_hooks', 'buffer', 'child_process', 'cluster',
    'console', 'constants', 'crypto', 'dgram', 'dns', 'domain',
    'events', 'fs', 'http', 'http2', 'https', 'inspector', 'module',
    'net', 'os', 'path', 'perf_hooks', 'process', 'punycode',
    'querystring', 'readline', 'repl', 'stream', 'string_decoder',
    'sys', 'timers', 'tls', 'trace_events', 'tty', 'url', 'util',
    'v8', 'vm', 'worker_threads', 'zlib',
]);

config.externals = [
    ...(Array.isArray(config.externals) ? config.externals : config.externals ? [config.externals] : []),
    function ({ request }, callback) {
        // node:-prefixed imports (e.g. node:fs)
        if (/^node:/.test(request)) {
            return callback(null, 'commonjs ' + request);
        }
        // bare Node.js built-in names (e.g. path, fs)
        if (nodeBuiltins.has(request)) {
            return callback(null, 'commonjs ' + request);
        }
        callback();
    },
];
