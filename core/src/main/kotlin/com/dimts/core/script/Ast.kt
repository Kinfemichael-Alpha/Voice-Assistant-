package com.dimts.core.script

sealed interface Expr
data class Lit(val value: Value) : Expr
data class Name(val name: String) : Expr
data class Not(val e: Expr) : Expr
data class Neg(val e: Expr) : Expr
data class Bin(val op: String, val l: Expr, val r: Expr) : Expr
data class Between(val e: Expr, val lo: Expr, val hi: Expr) : Expr

sealed interface Part {
    data class Text(val s: String) : Part
    data class Ex(val e: Expr) : Part
}

/** One argument of a command line: literal text with optional `{expr}` / `$name` pieces. */
data class Word(val parts: List<Part>)

sealed interface Stmt {
    val line: Int
}

data class CommandStmt(override val line: Int, val words: List<Word>) : Stmt
data class Branch(val cond: Expr, val body: List<Stmt>)
data class IfStmt(override val line: Int, val branches: List<Branch>, val elseBody: List<Stmt>?) : Stmt

enum class RuleKind { WHEN, TIMER }

/**
 * A registered, waiting block: `when`, `whenever`, `at`, `every`, `after`.
 * [source] is the de-indented original text, used to save and restore rules.
 */
data class RuleStmt(
    override val line: Int,
    val kind: RuleKind,
    val cond: Expr?,
    val repeat: Boolean,
    val delayMillis: Long,
    val body: List<Stmt>,
    val source: String,
    val label: String,
) : Stmt

data class WaitStmt(override val line: Int, val millis: Long) : Stmt
data class RepeatStmt(override val line: Int, val count: Expr, val body: List<Stmt>) : Stmt
data class SetStmt(override val line: Int, val name: String, val expr: Expr) : Stmt
data class PassStmt(override val line: Int) : Stmt
data class StopStmt(override val line: Int) : Stmt
data class WhileStmt(override val line: Int, val cond: Expr, val body: List<Stmt>) : Stmt
data class BreakStmt(override val line: Int) : Stmt
data class ContinueStmt(override val line: Int) : Stmt
data class DefStmt(override val line: Int, val name: String, val body: List<Stmt>, val source: String) : Stmt
data class StateDefStmt(override val line: Int, val name: String, val expr: Expr, val source: String) : Stmt

data class Program(val stmts: List<Stmt>)
