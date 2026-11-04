package com.dimts.core.script

/**
 * Line-by-line input for the terminal. A line ending in ':' starts a block, and following lines
 * are collected until an empty line, exactly like the Python prompt:
 *
 *     > at 10:30 pm:
 *     ...     call mom
 *     ...     when call.answered:
 *     ...         hangup
 *     ...
 *
 * Pasted text containing newlines is run as one script.
 */
class Repl(private val execute: (String) -> Unit) {
    private val buffer = ArrayList<String>()

    val inBlock: Boolean get() = buffer.isNotEmpty()

    val prompt: String get() = if (inBlock) "... " else "> "

    /** Feeds one typed line. Returns true if something was executed. */
    fun line(text: String): Boolean {
        if (text.contains('\n')) {
            var ran = false
            for (l in text.split('\n')) ran = line(l.trimEnd('\r')) || ran
            return flush() || ran
        }
        if (inBlock) {
            if (text.isBlank()) return flush()
            buffer.add(text)
            return false
        }
        if (text.isBlank()) return false
        if (Parser.stripComment(text).trimEnd().endsWith(":")) {
            buffer.add(text)
            return false
        }
        execute(text)
        return true
    }

    /** Runs whatever is buffered (empty line, or end of pasted text). */
    fun flush(): Boolean {
        if (buffer.isEmpty()) return false
        val script = buffer.joinToString("\n")
        buffer.clear()
        execute(script)
        return true
    }

    /** Throws away a half-typed block (like Ctrl-C). */
    fun abort() = buffer.clear()
}
