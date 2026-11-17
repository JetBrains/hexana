// helpers/expect.mjs
// Tiny assertion helpers for Hexana test suites.
// Import with: import { assertEquals, assertTrue, assertNull } from "./helpers/expect.mjs";

/**
 * Throws if actual !== expected (strict equality).
 * @param {*} actual
 * @param {*} expected
 * @param {string} [message]
 */
export function assertEquals(actual, expected, message) {
    if (actual !== expected) {
        const label = message ? message + ": " : "";
        throw new Error(
            label + "assertEquals failed: expected " + JSON.stringify(expected) +
            ", got " + JSON.stringify(actual)
        );
    }
}

/**
 * Throws if condition is not truthy.
 * @param {*} condition
 * @param {string} [message]
 */
export function assertTrue(condition, message) {
    if (!condition) {
        throw new Error(
            (message ? message : "assertTrue failed") +
            " (got: " + JSON.stringify(condition) + ")"
        );
    }
}

/**
 * Throws if value is not null.
 * @param {*} value
 * @param {string} [message]
 */
export function assertNull(value, message) {
    if (value !== null) {
        const label = message ? message + ": " : "";
        throw new Error(label + "assertNull failed: got " + JSON.stringify(value));
    }
}
