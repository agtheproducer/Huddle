package com.impactledger.app

import kotlin.math.round

/** How much a number can be trusted, shown next to every KPI. */
enum class TrustLevel(val label: String, val explanation: String) {
    MEASURED("Measured", "Collected directly, for example GPS-checked photos or distribution records"),
    REPORTED("Reported", "Taken from the NGO's published reports and audited statements"),
    SELF_REPORTED("Self-reported", "Submitted by NGO staff, not yet checked"),
}

/** One result an NGO delivers, for example "children who learned to read". */
data class Kpi(
    val name: String,
    val total: Double,
    val unit: String,
    val trust: TrustLevel,
)

/** An education project funded by donors. */
data class Project(
    val id: String,
    val name: String,
    val ngo: String,
    val country: String,
    val cause: String,
    val fundedChf: Double,
    val promise: String,
    val kpis: List<Kpi>,
    val updates: List<String>,
)

object ImpactMath {
    /**
     * A donor's estimated share of a KPI: their gift as a share of the project's total funding.
     * This is an estimate, never an exact one-to-one link between a franc and a result.
     */
    fun estimatedShare(kpi: Kpi, donationChf: Double, fundedChf: Double): Double =
        if (fundedChf <= 0.0) 0.0 else kpi.total * (donationChf / fundedChf)

    /** Estimated cost per result, in CHF. */
    fun costPerResult(kpi: Kpi, fundedChf: Double): Double =
        if (kpi.total <= 0.0) 0.0 else fundedChf / kpi.total
}

/** Formats a number for display: one decimal under 10, whole numbers with separators above. */
fun formatNumber(value: Double): String {
    if (value > 0.0 && value < 10.0) {
        val tenths = round(value * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
    val whole = round(value).toLong()
    return whole.toString().reversed().chunked(3).joinToString(",").reversed()
}

fun formatChf(value: Double): String = "CHF ${formatNumber(value)}"
