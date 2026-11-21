package com.agentboard.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalculatorTest {
    @Test
    fun `the four operations`() {
        assertEquals(15.0, Calculator.calculate(12.0, Op.ADD, 3.0))
        assertEquals(9.0, Calculator.calculate(12.0, Op.SUB, 3.0))
        assertEquals(36.0, Calculator.calculate(12.0, Op.MUL, 3.0))
        assertEquals(4.0, Calculator.calculate(12.0, Op.DIV, 3.0))
    }

    @Test
    fun `operation names are case-insensitive`() {
        assertEquals(Op.MUL, Op.parse("MUL"))
    }

    @Test
    fun `division by zero and bad input are refused`() {
        assertFailsWith<CalcException> { Calculator.calculate(1.0, Op.DIV, 0.0) }
        assertFailsWith<CalcException> { Op.parse("pow") }
        assertFailsWith<CalcException> { Calculator.number("a", "twelve") }
        assertFailsWith<CalcException> { Calculator.number("b", null) }
    }
}
