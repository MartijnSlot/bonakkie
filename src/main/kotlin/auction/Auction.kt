package auction

import roem.RoemCalculator

/** The standing bid that the next action has to respond to. */
sealed interface StandingBid

data class PointsBid(val bidder: String, val amount: Int) : StandingBid

data class PraatBid(val participants: List<String>) : StandingBid

/** [roem] is null for plain Bonak. */
data class BonakBid(val bidder: String, val roem: Int?) : StandingBid

enum class AuctionStatus(val message: String) {
    IN_PROGRESS("Bidding is in progress."),
    CONTRACT_FINALIZED("The bidding contract is final."),
    REDEAL_REQUIRED("Every player said Rond on the opening circuit: the same dealer deals again."),
    ALL_PASSED("Every player passed without a bid: the next player clockwise deals again."),
}

/**
 * An immutable bidding auction. [apply] returns a new auction or throws [RuleViolation],
 * so a rejected action can never leave a half-updated state behind.
 */
class Auction private constructor(
    /** Players in clockwise seating order. */
    val players: List<String>,
    val dealer: String,
    val currentPlayer: String?,
    val status: AuctionStatus,
    val rondAllowed: Boolean,
    val passed: Set<String>,
    val standingBid: StandingBid?,
    val history: List<HistoryEntry>,
    val contract: Contract?,
) {
    companion object {
        const val MIN_POINTS = 50
        const val MAX_POINTS = 125
        const val POINTS_STEP = 5
        const val MAX_POINTS_FOR_NEW_PRAAT = 75
        const val MIN_POINTS_OVER_PRAAT = 100

        fun start(players: List<String>, dealer: String): Auction {
            if (players.size < 2) throw RuleViolation("An auction needs at least two players.")
            if (players.any { it.isBlank() }) throw RuleViolation("Player names must not be blank.")
            if (players.toSet().size != players.size) throw RuleViolation("Player names must be unique.")
            if (dealer !in players) throw RuleViolation("The dealer must be one of the players.")
            return Auction(
                players = players.toList(),
                dealer = dealer,
                currentPlayer = players[(players.indexOf(dealer) + 1) % players.size],
                status = AuctionStatus.IN_PROGRESS,
                rondAllowed = true,
                passed = emptySet(),
                standingBid = null,
                history = emptyList(),
                contract = null,
            )
        }
    }

    fun apply(player: String, action: Action): Auction {
        validate(player, action)?.let { throw RuleViolation(it) }

        val history = history + HistoryEntry(player, action)
        val passed = if (action.type == ActionType.PASS) passed + player else passed
        val rondAllowed = rondAllowed && action.type == ActionType.ROND
        val bid = when (action.type) {
            ActionType.ROND, ActionType.PASS -> standingBid
            ActionType.BID_POINTS -> PointsBid(player, action.amount!!)
            ActionType.BID_PRAAT -> PraatBid(((standingBid as? PraatBid)?.participants ?: emptyList()) + player)
            ActionType.BID_BONAK -> BonakBid(player, null)
            ActionType.BID_BONAK_WITH_ROEM -> BonakBid(player, action.amount!!)
        }

        val remaining = players.filter { it !in passed }
        val (status, contract) = when {
            rondAllowed && history.size == players.size -> AuctionStatus.REDEAL_REQUIRED to null
            bid == null && remaining.isEmpty() -> AuctionStatus.ALL_PASSED to null
            bid is PointsBid && remaining == listOf(bid.bidder) ->
                AuctionStatus.CONTRACT_FINALIZED to PointsContract(bid.bidder, bid.amount)
            bid is BonakBid && remaining == listOf(bid.bidder) ->
                AuctionStatus.CONTRACT_FINALIZED to BonakContract(bid.bidder, bid.roem)
            bid is PraatBid && bid.participants.containsAll(remaining) ->
                AuctionStatus.CONTRACT_FINALIZED to PraatContract(bid.participants)
            else -> AuctionStatus.IN_PROGRESS to null
        }

        return Auction(
            players = players,
            dealer = dealer,
            currentPlayer = if (status == AuctionStatus.IN_PROGRESS) nextPlayer(player, passed, bid) else null,
            status = status,
            rondAllowed = rondAllowed,
            passed = passed,
            standingBid = bid,
            history = history,
            contract = contract,
        )
    }

    /** Returns why [action] by [player] is illegal, or null when it is legal. */
    fun validate(player: String, action: Action): String? {
        if (status != AuctionStatus.IN_PROGRESS) return "The auction has ended; start a fresh auction."
        if (player != currentPlayer) return "It is $currentPlayer's turn, not $player's."
        if (action.type.takesAmount && action.amount == null) return "${action.type} requires an amount."
        if (!action.type.takesAmount && action.amount != null) return "${action.type} does not take an amount."

        val bid = standingBid
        return when (action.type) {
            ActionType.PASS -> null
            ActionType.ROND ->
                if (rondAllowed) null else "Rond is only allowed while every previous action has been Rond."
            ActionType.BID_POINTS -> {
                val amount = action.amount!!
                when {
                    amount !in MIN_POINTS..MAX_POINTS || amount % POINTS_STEP != 0 ->
                        "A points bid must be between $MIN_POINTS and $MAX_POINTS in steps of $POINTS_STEP."
                    bid is BonakBid -> "A points bid cannot beat Bonak."
                    bid is PraatBid && amount < MIN_POINTS_OVER_PRAAT ->
                        "A points bid over Praat must be at least $MIN_POINTS_OVER_PRAAT."
                    bid is PointsBid && amount <= bid.amount -> "A points bid must be higher than ${bid.amount}."
                    else -> null
                }
            }
            ActionType.BID_PRAAT -> when {
                bid is BonakBid -> "Praat cannot beat Bonak."
                bid is PointsBid && bid.amount > MAX_POINTS_FOR_NEW_PRAAT ->
                    "Praat cannot be bid once the points bid is above $MAX_POINTS_FOR_NEW_PRAAT."
                bid is PraatBid && player in bid.participants -> "$player has already joined the Praat."
                else -> null
            }
            ActionType.BID_BONAK ->
                if (bid is BonakBid) "Plain Bonak cannot beat an existing Bonak; bid Bonak with higher roem." else null
            ActionType.BID_BONAK_WITH_ROEM -> {
                val roem = action.amount!!
                when {
                    bid !is BonakBid -> "Bonak with roem may only be bid after an earlier Bonak bid."
                    roem !in RoemCalculator.achievableTotals ->
                        "Roem must be an achievable total: ${RoemCalculator.achievableTotals.joinToString(", ")}."
                    roem <= (bid.roem ?: 0) -> "Bonak with roem must declare more than ${bid.roem} roem."
                    else -> null
                }
            }
        }
    }

    /** The legal actions for the current player, found by running every candidate through [validate]. */
    fun legalActions(): List<LegalAction> {
        val player = currentPlayer ?: return emptyList()
        return ActionType.entries.mapNotNull { type ->
            if (type.takesAmount) {
                val candidates = if (type == ActionType.BID_POINTS) MIN_POINTS..MAX_POINTS else 1..RoemCalculator.MAX_ROEM
                val amounts = candidates.filter { validate(player, Action(type, it)) == null }
                if (amounts.isEmpty()) null else LegalAction(type, amounts)
            } else {
                if (validate(player, Action(type)) == null) LegalAction(type, null) else null
            }
        }
    }

    /** The next player clockwise who still has to respond: not passed and not holding the standing bid. */
    private fun nextPlayer(after: String, passed: Set<String>, bid: StandingBid?): String {
        val holders = when (bid) {
            is PointsBid -> setOf(bid.bidder)
            is BonakBid -> setOf(bid.bidder)
            is PraatBid -> bid.participants.toSet()
            null -> emptySet()
        }
        val start = players.indexOf(after)
        return (1..players.size)
            .map { players[(start + it) % players.size] }
            .first { it !in passed && it !in holders }
    }
}
