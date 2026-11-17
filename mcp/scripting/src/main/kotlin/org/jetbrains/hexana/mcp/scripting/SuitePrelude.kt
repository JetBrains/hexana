package org.jetbrains.hexana.mcp.scripting

/**
 * JS prelude for the suite profile: the WinterTC-flavoured globals GraalJS 25
 * does not provide natively. `TextEncoder`/`TextDecoder` come from
 * `js.text-encoding` and are not shimmed here.
 *
 * The text is a single function expression. The host evaluates it and calls the
 * resulting function with one argument, the host bridge object exposing
 * `randomBytes(n)` and `digest(algorithm, bytes)`, so nothing host-side leaks
 * into the global scope.
 */
internal object SuitePrelude {

    val SOURCE_NAME: String = "hexana:suite-prelude"

    // language=JavaScript
    val TEXT: String = """
(function (host) {
  "use strict";

  // ---- queueMicrotask ------------------------------------------------------
  globalThis.queueMicrotask = function queueMicrotask(callback) {
    if (typeof callback !== "function") {
      throw new TypeError("queueMicrotask: callback must be a function");
    }
    Promise.resolve().then(callback);
  };

  // ---- atob / btoa ---------------------------------------------------------
  const B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  globalThis.btoa = function btoa(input) {
    const s = String(input);
    let out = "";
    for (let i = 0; i < s.length; i += 3) {
      const c1 = s.charCodeAt(i);
      const c2 = i + 1 < s.length ? s.charCodeAt(i + 1) : -1;
      const c3 = i + 2 < s.length ? s.charCodeAt(i + 2) : -1;
      if (c1 > 255 || c2 > 255 || c3 > 255) {
        throw new Error("btoa: the string contains characters outside the Latin1 range");
      }
      out += B64_ALPHABET[c1 >> 2];
      out += B64_ALPHABET[((c1 & 3) << 4) | (c2 >= 0 ? c2 >> 4 : 0)];
      out += c2 >= 0 ? B64_ALPHABET[((c2 & 15) << 2) | (c3 >= 0 ? c3 >> 6 : 0)] : "=";
      out += c3 >= 0 ? B64_ALPHABET[c3 & 63] : "=";
    }
    return out;
  };
  globalThis.atob = function atob(input) {
    let s = String(input).replace(/[\t\n\f\r ]+/g, "");
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(s) || s.length % 4 === 1) {
      throw new Error("atob: invalid base64 input");
    }
    s = s.replace(/=+$/, "");
    let out = "";
    let buffer = 0;
    let bits = 0;
    for (let i = 0; i < s.length; i++) {
      buffer = (buffer << 6) | B64_ALPHABET.indexOf(s[i]);
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out += String.fromCharCode((buffer >> bits) & 255);
      }
    }
    return out;
  };

  // ---- structuredClone -----------------------------------------------------
  globalThis.structuredClone = function structuredClone(value) {
    const seen = new Map();
    function fail(what) {
      const error = new Error("DataCloneError: " + what + " cannot be cloned");
      error.name = "DataCloneError";
      throw error;
    }
    function clone(v) {
      const t = typeof v;
      if (v === null || t === "number" || t === "string" || t === "boolean" ||
          t === "undefined" || t === "bigint") {
        return v;
      }
      if (t === "function") fail("a function");
      if (t === "symbol") fail("a symbol");
      if (seen.has(v)) return seen.get(v);
      if (v instanceof Date) return new Date(v.getTime());
      if (v instanceof ArrayBuffer) {
        const c = v.slice(0);
        seen.set(v, c);
        return c;
      }
      if (ArrayBuffer.isView(v)) {
        const c = v instanceof DataView
          ? new DataView(v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength))
          : v.slice();
        seen.set(v, c);
        return c;
      }
      if (v instanceof Map) {
        const m = new Map();
        seen.set(v, m);
        v.forEach((val, key) => m.set(clone(key), clone(val)));
        return m;
      }
      if (v instanceof Set) {
        const set = new Set();
        seen.set(v, set);
        v.forEach((val) => set.add(clone(val)));
        return set;
      }
      if (Array.isArray(v)) {
        const a = [];
        seen.set(v, a);
        for (const el of v) a.push(clone(el));
        return a;
      }
      const o = {};
      seen.set(v, o);
      for (const key of Object.keys(v)) o[key] = clone(v[key]);
      return o;
    }
    return clone(value);
  };

  // ---- URLSearchParams / URL (minimal) -------------------------------------
  class URLSearchParams {
    constructor(init) {
      this._pairs = [];
      if (init === undefined || init === null) return;
      if (typeof init === "string") {
        const query = init.startsWith("?") ? init.slice(1) : init;
        if (query.length > 0) {
          for (const part of query.split("&")) {
            if (part.length === 0) continue;
            const eq = part.indexOf("=");
            const rawKey = eq < 0 ? part : part.slice(0, eq);
            const rawValue = eq < 0 ? "" : part.slice(eq + 1);
            this._pairs.push([decodeURIComponent(rawKey.replace(/\+/g, " ")),
                              decodeURIComponent(rawValue.replace(/\+/g, " "))]);
          }
        }
      } else if (init instanceof URLSearchParams) {
        this._pairs = init._pairs.map((p) => [p[0], p[1]]);
      } else if (Array.isArray(init)) {
        for (const pair of init) this._pairs.push([String(pair[0]), String(pair[1])]);
      } else if (typeof init === "object") {
        for (const key of Object.keys(init)) this._pairs.push([key, String(init[key])]);
      }
    }
    append(key, value) { this._pairs.push([String(key), String(value)]); }
    delete(key) { this._pairs = this._pairs.filter((p) => p[0] !== String(key)); }
    get(key) { const p = this._pairs.find((p) => p[0] === String(key)); return p ? p[1] : null; }
    getAll(key) { return this._pairs.filter((p) => p[0] === String(key)).map((p) => p[1]); }
    has(key) { return this._pairs.some((p) => p[0] === String(key)); }
    set(key, value) {
      const k = String(key);
      const index = this._pairs.findIndex((p) => p[0] === k);
      this._pairs = this._pairs.filter((p, i) => p[0] !== k || i === index);
      if (index < 0) this._pairs.push([k, String(value)]);
      else this._pairs[index] = [k, String(value)];
    }
    forEach(callback, thisArg) {
      for (const p of this._pairs) callback.call(thisArg, p[1], p[0], this);
    }
    *entries() { for (const p of this._pairs) yield [p[0], p[1]]; }
    *keys() { for (const p of this._pairs) yield p[0]; }
    *values() { for (const p of this._pairs) yield p[1]; }
    [Symbol.iterator]() { return this.entries(); }
    get size() { return this._pairs.length; }
    toString() {
      return this._pairs
        .map((p) => encodeURIComponent(p[0]) + "=" + encodeURIComponent(p[1]))
        .join("&");
    }
  }

  const URL_PATTERN = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/(?:([^:@/?#]*)(?::([^@/?#]*))?@)?([^:/?#]*)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/;
  class URL {
    constructor(url, base) {
      let input = String(url);
      if (!URL_PATTERN.test(input) && base !== undefined) {
        const b = base instanceof URL ? base : new URL(String(base));
        if (input.startsWith("//")) {
          input = b.protocol + input;
        } else if (input.startsWith("/")) {
          input = b.origin + input;
        } else if (input.startsWith("?")) {
          input = b.origin + b.pathname + input;
        } else if (input.startsWith("#")) {
          input = b.origin + b.pathname + b.search + input;
        } else {
          const dir = b.pathname.slice(0, b.pathname.lastIndexOf("/") + 1);
          input = b.origin + dir + input;
        }
      }
      const match = URL_PATTERN.exec(input);
      if (!match) throw new TypeError("Invalid URL: " + String(url));
      this.protocol = match[1].toLowerCase() + ":";
      this.username = match[2] || "";
      this.password = match[3] || "";
      this.hostname = match[4].toLowerCase();
      this.port = match[5] || "";
      this.pathname = match[6] || "/";
      if (this.pathname === "") this.pathname = "/";
      this.search = match[7] || "";
      this.hash = match[8] || "";
      this.host = this.hostname + (this.port ? ":" + this.port : "");
      this.origin = this.protocol + "//" + this.host;
      this.searchParams = new URLSearchParams(this.search);
    }
    get href() {
      const auth = this.username
        ? this.username + (this.password ? ":" + this.password : "") + "@"
        : "";
      return this.protocol + "//" + auth + this.host + this.pathname + this.search + this.hash;
    }
    toString() { return this.href; }
    toJSON() { return this.href; }
  }
  globalThis.URLSearchParams = URLSearchParams;
  globalThis.URL = URL;

  // ---- describe / test / test.skip: call-site capture ---------------------
  // Wrap the host-registered globals so each JS call captures `new Error().stack`
  // and passes it as an extra trailing string argument to the host function.
  // The host parses the stack to extract (file, line) for navigation.
  (function wrapSuiteRegistrations() {
    const _describe = globalThis.describe;
    const _test = globalThis.test;
    const _testSkip = globalThis.test.skip;

    function capture() { return new Error().stack || ""; }

    globalThis.describe = function describe(name, fn) {
      return _describe(name, fn, capture());
    };

    const testWrapped = function test(name, fnOrOptions, fn) {
      if (arguments.length <= 2) {
        return _test(name, fnOrOptions, capture());
      }
      return _test(name, fnOrOptions, fn, capture());
    };
    testWrapped.skip = function skip(name, fnOrOptions, fn) {
      if (arguments.length <= 2) {
        return _testSkip(name, fnOrOptions, capture());
      }
      return _testSkip(name, fnOrOptions, fn, capture());
    };
    globalThis.test = testWrapped;
  })();

  // ---- crypto (seeded, deterministic) --------------------------------------
  function fillBytes(view) {
    const bytes = host.randomBytes(view.length);
    for (let i = 0; i < view.length; i++) view[i] = bytes[i];
  }
  const HEX = "0123456789abcdef";
  globalThis.crypto = {
    getRandomValues(array) {
      if (!ArrayBuffer.isView(array) || array instanceof DataView) {
        throw new TypeError("crypto.getRandomValues: expected an integer typed array");
      }
      fillBytes(new Uint8Array(array.buffer, array.byteOffset, array.byteLength));
      return array;
    },
    randomUUID() {
      const b = new Uint8Array(16);
      fillBytes(b);
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      let hex = "";
      for (let i = 0; i < 16; i++) hex += HEX[b[i] >> 4] + HEX[b[i] & 15];
      return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" +
             hex.slice(16, 20) + "-" + hex.slice(20);
    },
    subtle: {
      digest(algorithm, data) {
        return new Promise((resolve, reject) => {
          try {
            const name = typeof algorithm === "string" ? algorithm : algorithm && algorithm.name;
            let view;
            if (data instanceof ArrayBuffer) {
              view = new Uint8Array(data);
            } else if (ArrayBuffer.isView(data)) {
              view = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
            } else {
              throw new TypeError("crypto.subtle.digest: data must be an ArrayBuffer or a typed array");
            }
            const bytes = host.digest(String(name), view);
            const out = new Uint8Array(bytes.length);
            for (let i = 0; i < bytes.length; i++) out[i] = bytes[i];
            resolve(out.buffer);
          } catch (error) {
            reject(error instanceof Error ? error : new Error(String(error)));
          }
        });
      }
    }
  };
})
""".trimIndent()
}
