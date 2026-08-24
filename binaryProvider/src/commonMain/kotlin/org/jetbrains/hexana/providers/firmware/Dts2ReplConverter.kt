package org.jetbrains.hexana.providers.firmware

/**
 * Pure contract for converting a `zephyr.dts` text to a Renode `.repl` platform description.
 *
 * The interface is defined in commonMain so it can be golden-tested against fixture `.repl`
 * output without invoking the Python `dts2repl` process. The IntelliJ-side implementation
 * (`Dts2ReplRunner` in `idea-plugin/.../firmware/`) executes the actual converter.
 */
public interface Dts2ReplConverter {
    /**
     * Converts DTS text to a Renode `.repl` platform description.
     *
     * @param dtsText The full text of a merged `zephyr.dts` file.
     * @param boardHint Optional board identifier hint passed to the converter
     *   (may improve output quality for known boards).
     * @return [ReplResult.Ok] with the `.repl` text, or [ReplResult.Failed] with a
     *   human-readable error message.
     */
    public fun convert(dtsText: String, boardHint: String?): ReplResult
}

/** Result of a [Dts2ReplConverter.convert] call. */
public sealed class ReplResult {
    /** Successful conversion; [replText] is the content of the generated `.repl` file. */
    public data class Ok(val replText: String) : ReplResult()

    /** Conversion failed. [message] describes the error for display to the user. */
    public data class Failed(val message: String) : ReplResult()
}
