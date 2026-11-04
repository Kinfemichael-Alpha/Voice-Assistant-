package com.dimts.core.script

enum class T { NUM, STR, TIME, IDENT, OP, LP, RP, COMMA }

data class Token(val type: T, val text: String, val num: Double = 0.0)

/** Splits expression text into tokens. Understands time literals such as `10:30 pm`, `22:30`, `7am`. */
internal object Lexer {
    private val TIME = Regex("(\\d{1,2}):(\\d{2})(?:\\s*([aApP][mM])(?![A-Za-z0-9_]))?")
    private val HOUR = Regex("(\\d{1,2})\\s*([aApP][mM])(?![A-Za-z0-9_])")
    private val NUM = Regex("\\d+(?:\\.\\d+)?")
    private val TWO_CHAR_OPS = setOf("==", "!=", "<=", ">=")
    private const val ONE_CHAR_OPS = "<>+-*/%="

    fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) {
                i++
            } else if (c.isDigit()) {
                val t = TIME.matchAt(s, i)
                val h = if (t == null) HOUR.matchAt(s, i) else null
                val n = if (t == null && h == null) NUM.matchAt(s, i) else null
                when {
                    t != null -> {
                        out.add(timeToken(t.groupValues[1], t.groupValues[2], t.groupValues[3], t.value))
                        i += t.value.length
                    }
                    h != null -> {
                        out.add(timeToken(h.groupValues[1], "0", h.groupValues[2], h.value))
                        i += h.value.length
                    }
                    else -> {
                        val m = n!!
                        out.add(Token(T.NUM, m.value, m.value.toDouble()))
                        i += m.value.length
                    }
                }
            } else if (c == '"' || c == '\'') {
                val sb = StringBuilder()
                i++
                var closed = false
                while (i < s.length) {
                    val d = s[i]
                    if (d == '\\' && i + 1 < s.length) {
                        i++
                        sb.append(
                            when (s[i]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> s[i]
                            },
                        )
                    } else if (d == c) {
                        closed = true
                        i++
                        break
                    } else {
                        sb.append(d)
                    }
                    i++
                }
                if (!closed) throw ScriptError("missing closing quote ($c)")
                out.add(Token(T.STR, sb.toString()))
            } else if (c.isLetter() || c == '_') {
                val start = i
                i++
                while (i < s.length) {
                    val d = s[i]
                    if (d.isLetterOrDigit() || d == '_') {
                        i++
                    } else if (d == '.' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                        i++
                    } else {
                        break
                    }
                }
                out.add(Token(T.IDENT, s.substring(start, i)))
            } else if (c == '(') {
                out.add(Token(T.LP, "(")); i++
            } else if (c == ')') {
                out.add(Token(T.RP, ")")); i++
            } else if (c == ',') {
                out.add(Token(T.COMMA, ",")); i++
            } else if (i + 1 < s.length && s.substring(i, i + 2) in TWO_CHAR_OPS) {
                out.add(Token(T.OP, s.substring(i, i + 2))); i += 2
            } else if (c in ONE_CHAR_OPS) {
                out.add(Token(T.OP, c.toString())); i++
            } else {
                throw ScriptError("unexpected character '$c'")
            }
        }
        return out
    }

    private fun timeToken(hStr: String, mStr: String, ampm: String, text: String): Token {
        val h = hStr.toInt()
        val m = mStr.toInt()
        if (m > 59) throw ScriptError("'$text' is not a valid time")
        val minute = if (ampm.isEmpty()) {
            if (h > 23) throw ScriptError("'$text' is not a valid time")
            h * 60 + m
        } else {
            if (h !in 1..12) throw ScriptError("'$text' is not a valid time (use 1-12 with am/pm)")
            (h % 12 + if (ampm.lowercase() == "pm") 12 else 0) * 60 + m
        }
        return Token(T.TIME, text, minute.toDouble())
    }
}

/** Recursive-descent expression parser: or < and < not < comparison < + - < * / % < unary < primary. */
internal class ExprParser(private val toks: List<Token>, start: Int = 0) {
    var pos = start
        private set

    private fun peek(): Token? = toks.getOrNull(pos)

    private fun isKw(t: Token?, kw: String) = t != null && t.type == T.IDENT && t.text.equals(kw, ignoreCase = true)

    fun atEnd() = pos >= toks.size

    fun parseOr(): Expr {
        var left = parseAnd()
        while (isKw(peek(), "or")) {
            pos++
            left = Bin("or", left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        while (isKw(peek(), "and")) {
            pos++
            left = Bin("and", left, parseNot())
        }
        return left
    }

    private fun parseNot(): Expr {
        if (isKw(peek(), "not")) {
            pos++
            return Not(parseNot())
        }
        return parseCmp()
    }

    private fun parseCmp(): Expr {
        val left = parseAdd()
        val t = peek() ?: return left
        return when {
            t.type == T.OP && t.text in setOf("==", "!=", "<", "<=", ">", ">=") -> {
                pos++
                Bin(t.text, left, parseAdd())
            }
            isKw(t, "is") -> {
                pos++
                var op = "=="
                if (isKw(peek(), "not")) {
                    pos++
                    op = "!="
                }
                Bin(op, left, parseAdd())
            }
            isKw(t, "contains") -> {
                pos++
                Bin("contains", left, parseAdd())
            }
            isKw(t, "between") -> {
                pos++
                val lo = parseAdd()
                if (!isKw(peek(), "and")) throw ScriptError("'between' needs 'and': x between 1 and 5")
                pos++
                Between(left, lo, parseAdd())
            }
            else -> left
        }
    }

    private fun parseAdd(): Expr {
        var left = parseMul()
        while (true) {
            val t = peek()
            if (t != null && t.type == T.OP && (t.text == "+" || t.text == "-")) {
                pos++
                left = Bin(t.text, left, parseMul())
            } else {
                return left
            }
        }
    }

    private fun parseMul(): Expr {
        var left = parseUnary()
        while (true) {
            val t = peek()
            if (t != null && t.type == T.OP && (t.text == "*" || t.text == "/" || t.text == "%")) {
                pos++
                left = Bin(t.text, left, parseUnary())
            } else {
                return left
            }
        }
    }

    private fun parseUnary(): Expr {
        val t = peek()
        if (t != null && t.type == T.OP && t.text == "-") {
            pos++
            return Neg(parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        val t = peek() ?: throw ScriptError("the expression ends too early")
        pos++
        return when (t.type) {
            T.NUM -> Lit(Value.Num(t.num))
            T.STR -> Lit(Value.Str(t.text))
            T.TIME -> Lit(Value.Time(t.num.toInt()))
            T.IDENT -> when (t.text.lowercase()) {
                "true", "yes" -> Lit(Value.Bool(true))
                "false", "no" -> Lit(Value.Bool(false))
                "null", "none" -> Lit(Value.Null)
                "and", "or", "not", "is", "contains", "between" ->
                    throw ScriptError("unexpected '${t.text}' (a value was expected here)")
                else -> Name(t.text)
            }
            T.LP -> {
                val e = parseOr()
                if (peek()?.type != T.RP) throw ScriptError("missing ')'")
                pos++
                e
            }
            else -> throw ScriptError("unexpected '${t.text}'")
        }
    }

    companion object {
        fun parseAll(text: String): Expr {
            val toks = Lexer.tokenize(text)
            if (toks.isEmpty()) throw ScriptError("a condition is missing")
            val p = ExprParser(toks)
            val e = p.parseOr()
            if (!p.atEnd()) throw ScriptError("unexpected '${toks[p.pos].text}'")
            return e
        }
    }
}
