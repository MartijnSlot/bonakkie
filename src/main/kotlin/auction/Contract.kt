package auction

import roem.RoemCalculator

/** The final outcome of a finished auction. Trick play and evaluation of completed hands are out of scope. */
sealed interface Contract {
    val declarers: List<String>
    fun describe(): String
}

data class PointsContract(val declarer: String, val bidAmount: Int) : Contract {
    override val declarers get() = listOf(declarer)
    override fun describe() = "$declarer must make $bidAmount points (including roem) and win at least one trick"

    fun requirements(totalRoem: Int): PointsRequirements {
        if (totalRoem !in 0..RoemCalculator.MAX_ROEM) {
            throw RuleViolation("Total roem must be between 0 and ${RoemCalculator.MAX_ROEM}.")
        }
        return PointsRequirements(
            bidAmount = bidAmount,
            totalRoem = totalRoem,
            requiredTrickPoints = maxOf(bidAmount - totalRoem, 0),
            minimumTricks = 1,
        )
    }
}

/**
 * The bid is the total target. Roem lowers the points still needed from tricks,
 * but the declarer must always win at least one trick (a zero-point trick counts).
 */
data class PointsRequirements(
    val bidAmount: Int,
    val totalRoem: Int,
    val requiredTrickPoints: Int,
    val minimumTricks: Int,
)

data class PraatContract(override val declarers: List<String>) : Contract {
    override fun describe() = "Praat by ${declarers.joinToString(", ")}: take no tricks"
}

data class BonakContract(val declarer: String, val declaredRoem: Int?) : Contract {
    override val declarers get() = listOf(declarer)
    override fun describe() =
        "$declarer plays Bonak${declaredRoem?.let { " with $it roem" } ?: ""}: take every trick with trumps"
}
