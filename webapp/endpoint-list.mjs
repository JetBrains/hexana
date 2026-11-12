export function parseEndpointList(value, name) {
    const endpoints = String(value ?? "").split(",").map(item => item.trim()).filter(Boolean);
    if (endpoints.length === 0) throw new Error(`${name} is required`);
    for (const endpoint of endpoints) {
        let url;
        try { url = new URL(endpoint); } catch { throw new Error(`${name} contains an invalid URL`); }
        if (url.protocol !== "https:" || url.pathname !== "/" || url.search || url.hash || url.username || url.password || endpoint !== url.href) {
            throw new Error(`${name} must list canonical https origins with a trailing slash`);
        }
    }
    return endpoints;
}
