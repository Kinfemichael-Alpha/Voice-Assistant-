package com.dimts.core.script

/**
 * Parser for the Dimts shell language: a small Python-like language where blocks are
 * made by indentation and every block header ends with ':'.
 *
 *     at 10:30 pm:                      # wait, then run once
 *         call mom
 *         when call.answered:           # nested: waits for the event
 *             hangup
 *     if battery.level < 20: say "low"  # inline body is allowed
 *
 * Anything that is not a keyword statement is a *command line*: whitespace separated words,
 * `"quoted text"`, `$variable`, and `{expression}` pieces inside words.
 */
object Parser {
    private val HEADERS = setOf("if", "elif", "else", "when", "whenever", "at", "every", "after", "repeat", "while", "def")
    private val KEYWORDS = HEADERS + setOf("pass", "stop", "wait", "set", "break", "continue", "state")
    private val ACTION_NAME = Regex("^[\\p{L}_][\\p{L}\\p{N}_]*$")
    private val ASSIGN = Regex("^([\\p{L}_][\\p{L}\\p{N}_.]*)\\s*=(?!=)\\s*(.+)$")
    private val COMPOUND = Regex("^([\\p{L}_][\\p{L}\\p{N}_.]*)\\s*([+\\-*/%])=(?!=)\\s*(.+)$")
    private const val ESCAPED_BRACE = '\u0000'

    private val UNITS = mapOf(
        "ms" to 1L, "millisecond" to 1L, "milliseconds" to 1L,
        "s" to 1_000L, "sec" to 1_000L, "secs" to 1_000L, "second" to 1_000L, "seconds" to 1_000L,
        "m" to 60_000L, "min" to 60_000L, "mins" to 60_000L, "minute" to 60_000L, "minutes" to 60_000L,
        "h" to 3_600_000L, "hr" to 3_600_000L, "hrs" to 3_600_000L, "hour" to 3_600_000L, "hours" to 3_600_000L,
        "d" to 86_400_000L, "day" to 86_400_000L, "days" to 86_400_000L,
    )

    fun parse(source: String): Program {
        val lines = ArrayList<Line>()
        source.split('\n').forEachIndexed { idx, rawLine ->
            val raw = rawLine.trimEnd('\r').replace("\t", "    ")
            val text = stripComment(raw).trim()
            if (text.isNotEmpty()) {
                lines.add(Line(idx + 1, raw.length - raw.trimStart().length, text, raw))
            }
        }
        if (lines.isEmpty()) return Program(emptyList())
        val parser = BlockParser(lines)
        val stmts = parser.parseBlock(lines[0].indent)
        if (parser.pos < lines.size) throw ScriptError("unexpected indentation", lines[parser.pos].line)
        return Program(stmts)
    }

    /** Removes a trailing `# comment` (a '#' outside quotes). */
    fun stripComment(line: String): String {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else if (c == '"' || (c == '\'' && (i == 0 || line[i - 1].isWhitespace()))) {
                quote = c
            } else if (c == '#') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    fun parseExpression(text: String): Expr = ExprParser.parseAll(text)

    /** Collects every [Name] used anywhere in [stmts] (conditions, assignments, `{...}` pieces). */
    fun namesUsed(stmts: List<Stmt>, out: MutableSet<String> = LinkedHashSet()): Set<String> {
        fun expr(e: Expr) {
            when (e) {
                is Name -> out.add(e.name)
                is Not -> expr(e.e)
                is Neg -> expr(e.e)
                is Bin -> { expr(e.l); expr(e.r) }
                is Between -> { expr(e.e); expr(e.lo); expr(e.hi) }
                is Lit -> {}
            }
        }
        for (s in stmts) {
            when (s) {
                is CommandStmt -> s.words.forEach { w -> w.parts.forEach { if (it is Part.Ex) expr(it.e) } }
                is IfStmt -> {
                    s.branches.forEach { expr(it.cond); namesUsed(it.body, out) }
                    s.elseBody?.let { namesUsed(it, out) }
                }
                is RuleStmt -> { s.cond?.let { expr(it) }; namesUsed(s.body, out) }
                is RepeatStmt -> { expr(s.count); namesUsed(s.body, out) }
                is WhileStmt -> { expr(s.cond); namesUsed(s.body, out) }
                is DefStmt -> namesUsed(s.body, out)
                is StateDefStmt -> expr(s.expr)
                is SetStmt -> expr(s.expr)
                else -> {}
            }
        }
        return out
    }

    fun assignedNames(stmts: List<Stmt>, out: MutableSet<String> = LinkedHashSet()): Set<String> {
        for (s in stmts) {
            when (s) {
                is SetStmt -> out.add(s.name)
                is IfStmt -> { s.branches.forEach { assignedNames(it.body, out) }; s.elseBody?.let { assignedNames(it, out) } }
                is RuleStmt -> assignedNames(s.body, out)
                is RepeatStmt -> assignedNames(s.body, out)
                is WhileStmt -> assignedNames(s.body, out)
                is DefStmt -> assignedNames(s.body, out)
                is StateDefStmt -> out.add(s.name)
                else -> {}
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------

    private class Line(val line: Int, val indent: Int, val text: String, val raw: String)

    private fun firstWord(text: String): String {
        val t = text.trimStart()
        var i = 0
        while (i < t.length && !t[i].isWhitespace() && t[i] != ':') i++
        return t.substring(0, i)
    }

    /** Splits `head: inline` at the first header colon (not in quotes, not inside `10:30`). */
    private fun splitHeader(text: String): Pair<String, String>? {
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else if (c == '"' || (c == '\'' && (i == 0 || text[i - 1].isWhitespace()))) {
                quote = c
            } else if (c == ':') {
                val timeColon = i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit()
                if (!timeColon) return text.substring(0, i) to text.substring(i + 1)
            }
            i++
        }
        return null
    }

    private fun splitTopLevel(text: String, sep: Char): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { i++; sb.append(text[i]) } else if (c == quote) quote = null
            } else if (c == '"' || (c == '\'' && (i == 0 || text[i - 1].isWhitespace()))) {
                quote = c; sb.append(c)
            } else if (c == sep) {
                parts.add(sb.toString()); sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        parts.add(sb.toString())
        return parts
    }

    private class BlockParser(val lines: List<Line>) {
        var pos = 0

        fun parseBlock(indent: Int): List<Stmt> {
            val out = ArrayList<Stmt>()
            while (pos < lines.size) {
                val line = lines[pos]
                if (line.indent < indent) break
                if (line.indent > indent) throw ScriptError("unexpected indentation", line.line)
                out.add(parseLine(line))
            }
            return out
        }

        fun parseLine(line: Line): Stmt {
            try {
                val first = firstWord(line.text).lowercase()
                if (first in HEADERS) return parseHeader(line, first)
                pos++
                return parseSimple(line.text, line.line)
            } catch (e: ScriptError) {
                throw e.withLine(line.line)
            }
        }

        private fun bodyFor(header: Line, inline: String, what: String): List<Stmt> {
            if (inline.isNotEmpty()) return parseInline(inline, header.line)
            if (pos >= lines.size || lines[pos].indent <= header.indent) {
                throw ScriptError("expected an indented block after '$what:'", header.line)
            }
            return parseBlock(lines[pos].indent)
        }

        private fun parseInline(text: String, no: Int): List<Stmt> {
            val parts = if (firstWord(text).lowercase() in HEADERS) listOf(text) else splitTopLevel(text, ';')
            val out = ArrayList<Stmt>()
            for (p in parts) {
                val t = p.trim()
                if (t.isEmpty()) continue
                val sub = BlockParser(listOf(Line(no, 0, t, t)))
                out.add(sub.parseLine(sub.lines[0]))
            }
            return out
        }

        private fun headOf(line: Line, kw: String): Pair<String, String> {
            val split = splitHeader(line.text)
                ?: throw ScriptError("'$kw' must end with ':'  (example: $kw ...: command)", line.line)
            val head = split.first.trim().substring(kw.length).trim()
            return head to split.second.trim()
        }

        private fun sourceOf(line: Line, startPos: Int): String =
            lines.subList(startPos, pos).joinToString("\n") { it.raw.drop(line.indent) }.trimEnd()

        private fun parseHeader(line: Line, kw: String): Stmt {
            val start = pos
            pos++
            val (head, inline) = headOf(line, kw)
            when (kw) {
                "elif", "else" -> throw ScriptError("'$kw' without a matching 'if'", line.line)
                "if" -> {
                    val branches = ArrayList<Branch>()
                    branches.add(Branch(ExprParser.parseAll(head), bodyFor(line, inline, "if")))
                    var elseBody: List<Stmt>? = null
                    while (pos < lines.size && lines[pos].indent == line.indent) {
                        val next = lines[pos]
                        val nk = firstWord(next.text).lowercase()
                        if (nk == "elif") {
                            pos++
                            val (h2, i2) = headOf(next, "elif")
                            branches.add(Branch(ExprParser.parseAll(h2), bodyFor(next, i2, "elif")))
                        } else if (nk == "else") {
                            pos++
                            val (h2, i2) = headOf(next, "else")
                            if (h2.isNotEmpty()) throw ScriptError("'else' takes no condition (write 'else:')", next.line)
                            elseBody = bodyFor(next, i2, "else")
                            break
                        } else {
                            break
                        }
                    }
                    return IfStmt(line.line, branches, elseBody)
                }
                "when", "whenever" -> {
                    val cond = ExprParser.parseAll(head)
                    val body = bodyFor(line, inline, kw)
                    return RuleStmt(
                        line.line, RuleKind.WHEN, cond, kw == "whenever", 0L, body,
                        sourceOf(line, start), "$kw $head",
                    )
                }
                "at" -> {
                    val cond = parseAt(head)
                    val body = bodyFor(line, inline, "at")
                    return RuleStmt(line.line, RuleKind.WHEN, cond, false, 0L, body, sourceOf(line, start), "at $head")
                }
                "every" -> {
                    val toks = Lexer.tokenize(head)
                    val body = bodyFor(line, inline, "every")
                    val src = sourceOf(line, start)
                    return if (toks.firstOrNull()?.type == T.NUM) {
                        val ms = parseDuration(toks)
                        if (ms < 1_000) throw ScriptError("'every' needs at least 1 second")
                        RuleStmt(line.line, RuleKind.TIMER, null, true, ms, body, src, "every $head")
                    } else {
                        RuleStmt(line.line, RuleKind.WHEN, parseEvery(toks), true, 0L, body, src, "every $head")
                    }
                }
                "after" -> {
                    val ms = parseDuration(Lexer.tokenize(head))
                    val body = bodyFor(line, inline, "after")
                    return RuleStmt(line.line, RuleKind.TIMER, null, false, ms, body, sourceOf(line, start), "after $head")
                }
                "repeat" -> {
                    val countText = head.replace(Regex("\\s+times?$", RegexOption.IGNORE_CASE), "")
                    val count = ExprParser.parseAll(countText)
                    return RepeatStmt(line.line, count, bodyFor(line, inline, "repeat"))
                }
                "while" -> {
                    val cond = ExprParser.parseAll(head)
                    return WhileStmt(line.line, cond, bodyFor(line, inline, "while"))
                }
                "def" -> {
                    if (!ACTION_NAME.matches(head)) throw ScriptError("'def' needs a name:  def night_mode:", line.line)
                    if (head.lowercase() in KEYWORDS) throw ScriptError("'$head' is a reserved word - choose another name", line.line)
                    val body = bodyFor(line, inline, "def")
                    return DefStmt(line.line, head, body, sourceOf(line, start))
                }
            }
            throw ScriptError("unknown keyword '$kw'", line.line)
        }

        private fun parseSimple(text: String, no: Int): Stmt {
            val w = firstWord(text).lowercase()
            if (w == "pass" && text.trim().equals("pass", true)) return PassStmt(no)
            if (w == "stop" && text.trim().equals("stop", true)) return StopStmt(no)
            if (w == "break" && text.trim().equals("break", true)) return BreakStmt(no)
            if (w == "continue" && text.trim().equals("continue", true)) return ContinueStmt(no)
            if (w == "state") {
                val rest = text.trim().substring(5).trim()
                val sm = ASSIGN.matchEntire(rest)
                    ?: throw ScriptError("usage: state NAME = CONDITION   (example: state night = time between 10 pm and 6 am)")
                return StateDefStmt(no, sm.groupValues[1], ExprParser.parseAll(sm.groupValues[2]), text.trim())
            }
            if (w == "wait") {
                return WaitStmt(no, parseDuration(Lexer.tokenize(text.trim().substring(4))))
            }
            val assignText = if (w == "set") text.trim().substring(3).trim() else text.trim()
            val cm = COMPOUND.matchEntire(assignText)
            if (cm != null) {
                val target = cm.groupValues[1]
                return SetStmt(no, target, Bin(cm.groupValues[2], Name(target), ExprParser.parseAll(cm.groupValues[3])))
            }
            val m = ASSIGN.matchEntire(assignText)
            if (m != null) return SetStmt(no, m.groupValues[1], ExprParser.parseAll(m.groupValues[2]))
            return CommandStmt(no, parseWords(text))
        }
    }

    // ---- time / duration / day helpers ----------------------------------------------------

    private fun parseDuration(toks: List<Token>): Long {
        if (toks.isEmpty()) throw ScriptError("a duration is missing (examples: 30 s, 5 min, 2 h)")
        var total = 0L
        var i = 0
        while (i < toks.size) {
            val n = toks[i]
            if (n.type != T.NUM) throw ScriptError("expected a number in the duration, found '${n.text}'")
            val unit = toks.getOrNull(i + 1)
            if (unit == null || unit.type != T.IDENT) {
                throw ScriptError("add a unit after '${n.text}' (examples: 30 s, 5 min, 2 h)")
            }
            val factor = UNITS[unit.text.lowercase()]
                ?: throw ScriptError("unknown time unit '${unit.text}' (use s, min, h, d)")
            total += (n.num * factor).toLong()
            i += 2
        }
        return total
    }

    private fun timeCond(minute: Int): Expr = Bin("==", Name("time"), Lit(Value.Time(minute)))

    private fun dayOr(days: List<String>): Expr? {
        if (days.isEmpty()) return null
        var e: Expr? = null
        for (d in days) {
            val c = Bin("==", Name("day"), Lit(Value.Str(d)))
            e = if (e == null) c else Bin("or", e, c)
        }
        return e
    }

    private fun andAll(a: Expr, b: Expr?): Expr = if (b == null) a else Bin("and", a, b)

    private fun dayWord(tok: Token): List<String> {
        val t = tok.text.lowercase()
        return when (t) {
            "day", "days", "daily", "everyday" -> emptyList()
            "weekday", "weekdays" -> Days.NAMES.take(5)
            "weekend", "weekends" -> Days.NAMES.drop(5)
            else -> listOf(Days.normalize(t) ?: throw ScriptError("'${tok.text}' is not a day (try: mon, tue, weekdays, day)"))
        }
    }

    /** `10:30 pm` or `10:30 pm on mon, wed`. */
    private fun parseAt(head: String): Expr {
        val toks = Lexer.tokenize(head)
        val time = toks.firstOrNull()
        if (time == null || time.type != T.TIME) throw ScriptError("'at' needs a time, for example: at 10:30 pm:")
        if (toks.size == 1) return timeCond(time.num.toInt())
        val on = toks[1]
        if (on.type != T.IDENT || !on.text.equals("on", true)) throw ScriptError("after the time use 'on <days>' (at 7 am on mon, fri:)")
        val days = ArrayList<String>()
        for (t in toks.drop(2)) if (t.type == T.IDENT) days.addAll(dayWord(t)) else if (t.type != T.COMMA) {
            throw ScriptError("unexpected '${t.text}' in the day list")
        }
        return andAll(timeCond(time.num.toInt()), dayOr(days.distinct()))
    }

    /** `day at 7:00 am`, `mon wed fri at 7 am`, `weekdays at 8 am`. */
    private fun parseEvery(toks: List<Token>): Expr {
        val atIdx = toks.indexOfFirst { it.type == T.IDENT && it.text.equals("at", true) }
        if (atIdx < 0) throw ScriptError("'every' needs 'at <time>' (every day at 7 am:) or an interval (every 5 min:)")
        val days = ArrayList<String>()
        for (t in toks.subList(0, atIdx)) if (t.type == T.IDENT) days.addAll(dayWord(t)) else if (t.type != T.COMMA) {
            throw ScriptError("unexpected '${t.text}' in the day list")
        }
        val time = toks.getOrNull(atIdx + 1)
        if (time == null || time.type != T.TIME || toks.size > atIdx + 2) throw ScriptError("expected one time after 'at' (for example 7:00 am)")
        return andAll(timeCond(time.num.toInt()), dayOr(days.distinct()))
    }

    // ---- command lines ---------------------------------------------------------------------

    fun parseWords(text: String): List<Word> {
        val words = ArrayList<Word>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val sb = StringBuilder()
            var quoted = false
            val dollar = text[i] == '$'
            while (i < n && !text[i].isWhitespace()) {
                val c = text[i]
                if (c == '"' || (c == '\'' && sb.isEmpty())) {
                    quoted = true
                    i++
                    while (i < n && text[i] != c) {
                        if (text[i] == '\\' && i + 1 < n) {
                            i++
                            sb.append(
                                when (text[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    '{' -> ESCAPED_BRACE
                                    else -> text[i]
                                },
                            )
                        } else {
                            sb.append(text[i])
                        }
                        i++
                    }
                    if (i >= n) throw ScriptError("missing closing quote ($c)")
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            val raw = sb.toString()
            if (dollar && !quoted) {
                val name = raw.substring(1)
                if (!Regex("[\\p{L}_][\\p{L}\\p{N}_.]*").matches(name)) throw ScriptError("'$raw' is not a variable name")
                words.add(Word(listOf(Part.Ex(Name(name)))))
            } else {
                words.add(Word(interpolate(raw)))
            }
        }
        return words
    }

    private fun interpolate(raw: String): List<Part> {
        val parts = ArrayList<Part>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '{') {
                val end = raw.indexOf('}', i + 1)
                if (end < 0) throw ScriptError("missing '}' (use \\{ for a literal brace)")
                if (sb.isNotEmpty()) { parts.add(Part.Text(sb.toString().replace(ESCAPED_BRACE, '{'))); sb.setLength(0) }
                parts.add(Part.Ex(ExprParser.parseAll(raw.substring(i + 1, end))))
                i = end + 1
            } else {
                sb.append(c)
                i++
            }
        }
        if (sb.isNotEmpty() || parts.isEmpty()) parts.add(Part.Text(sb.toString().replace(ESCAPED_BRACE, '{')))
        return parts
    }
}
