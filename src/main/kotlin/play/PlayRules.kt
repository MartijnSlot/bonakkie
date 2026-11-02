package play

import auction.Contract
import model.Card
import model.Suit

data class FirstLeaderContext(val players: List<String>, val dealer: String, val contract: Contract)

/** Who leads the first trick. */
fun interface FirstLeaderPolicy {
    fun firstLeader(context: FirstLeaderContext): String
}

/** Which cards from [hand] may be played into [trick] (empty when leading). [trump] is null without trumps. */
fun interface PlayLegalityPolicy {
    fun legalCards(hand: List<Card>, trick: List<Play>, trump: Suit?): Set<Card>
}

enum class UnconfirmedRule(val description: String) {
    FIRST_LEADER("Who leads the first trick (including with several Praat players) has not been confirmed."),
    MOVE_LEGALITY(
        "Which cards may be played has not been confirmed: following suit, trumping while holding the suit led, " +
            "trumping when unable to follow, overtrumping, undertrumping, and withholding the trump Jack."
    ),
}

/**
 * The play rules that are not confirmed yet. A missing policy blocks play: there is deliberately no default,
 * so the game never falls back to invented rules or unrestricted card play.
 */
data class PlayRules(val firstLeader: FirstLeaderPolicy?, val legality: PlayLegalityPolicy?) {
    val unconfirmed: List<UnconfirmedRule> = listOfNotNull(
        UnconfirmedRule.FIRST_LEADER.takeIf { firstLeader == null },
        UnconfirmedRule.MOVE_LEGALITY.takeIf { legality == null },
    )

    companion object {
        /** The rules as confirmed today: neither policy is known, so play stays blocked. */
        val CONFIRMED = PlayRules(firstLeader = null, legality = null)
    }
}

data class CompletedTrick(val number: Int, val leader: String, val plays: List<Play>, val winner: String)

/** The play phase of one hand. */
data class PlayState(
    /** The trick in progress (1 to 6); stays at 6 once the hand is complete. */
    val trickNumber: Int,
    val leader: String,
    val currentTrick: List<Play>,
    val completedTricks: List<CompletedTrick>,
)
