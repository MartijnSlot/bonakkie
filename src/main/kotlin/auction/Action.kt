package auction

enum class ActionType(val takesAmount: Boolean) {
    ROND(false),
    PASS(false),
    BID_POINTS(true),
    BID_PRAAT(false),
    BID_BONAK(false),
    BID_BONAK_WITH_ROEM(true),
}

data class Action(val type: ActionType, val amount: Int? = null) {
    fun describe() = when (type) {
        ActionType.ROND -> "Rond"
        ActionType.PASS -> "Pass"
        ActionType.BID_POINTS -> "$amount points"
        ActionType.BID_PRAAT -> "Praat"
        ActionType.BID_BONAK -> "Bonak"
        ActionType.BID_BONAK_WITH_ROEM -> "Bonak with $amount roem"
    }

    companion object {
        val ROND = Action(ActionType.ROND)
        val PASS = Action(ActionType.PASS)
        val PRAAT = Action(ActionType.BID_PRAAT)
        val BONAK = Action(ActionType.BID_BONAK)
        fun points(amount: Int) = Action(ActionType.BID_POINTS, amount)
        fun bonakWithRoem(roem: Int) = Action(ActionType.BID_BONAK_WITH_ROEM, roem)
    }
}

data class HistoryEntry(val player: String, val action: Action)

/** An action type the current player may take, with the amounts that are legal for it (if it takes an amount). */
data class LegalAction(val type: ActionType, val amounts: List<Int>?)

/** Thrown when a requested action or setup breaks the rules. State is never modified when this is thrown. */
class RuleViolation(message: String) : RuntimeException(message)
