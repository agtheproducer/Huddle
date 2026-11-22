package com.agentboard.calc

/** The operations the frontend can ask for, by their names in the URL. */
enum class Op(val symbol: String) {
    ADD("+"), SUB("−"), MUL("×"), DIV("÷"), POW("^");

    companion object {
        fun parse(name: String?): Op =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw CalcException("op must be one of add, sub, mul, div, pow")
    }
}

/** A request the caller got wrong: answered with HTTP 400 and this message. */
class CalcException(message: String) : RuntimeException(message)

object Calculator {
    fun calculate(a: Double, op: Op, b: Double): Double = when (op) {
        Op.ADD -> a + b
        Op.SUB -> a - b
        Op.MUL -> a * b
        Op.DIV -> if (b == 0.0) throw CalcException("cannot divide by zero") else a / b
        Op.POW -> Math.pow(a, b).takeIf { it.isFinite() }
            ?: throw CalcException("a to the power of b is not a finite number")
    }

    /** Parses one number from the URL, with a message that names the field. */
    fun number(name: String, raw: String?): Double =
        raw?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw CalcException("$name must be a number")
}
