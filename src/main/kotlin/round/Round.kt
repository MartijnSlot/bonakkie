package round

import auction.Action
import auction.Auction
import auction.AuctionStatus
import auction.BonakContract
import auction.Contract
import auction.PointsContract
import auction.PointsRequirements
import auction.PraatContract
import auction.RuleViolation
import model.Card
import model.Deck
import model.Shuffler
import model.Suit
import roem.RoemCalculator
import roem.RoemResult

/** Where a card currently is. Every card of the deck has exactly one location. */
sealed interface CardLocation

data class InHand(val player: String) : CardLocation

data object StokFaceUp : CardLocation

data object StokFaceDown : CardLocation

/** Not dealt; out of the current hand. */
data object Unused : CardLocation

/** The final two discards of the stok exchange; out of the current hand. */
data object Discarded : CardLocation

enum class RoundPhase(val message: String) {
    BIDDING("Cards are dealt; bidding is in progress."),
    STOK_EXCHANGE("The declarer takes the stok and discards two cards."),
    TRUMP_SELECTION("The declarer chooses trumps."),
    READY_TO_PLAY("Ready to play."),
    CONTRACT_FAILED("The contract failed before play."),
}

enum class FailureReason { INSUFFICIENT_BONAK_ROEM }

/** A contract that failed before play. Only records the failure: no penalty or score change is defined. */
data class ContractFailure(val reason: FailureReason, val declaredRoem: Int, val message: String)

/** The declarer's six cards after their discard and the roem they hold with the chosen trumps. */
data class FinalRoem(val player: String, val hand: List<Card>, val trump: Suit, val roem: RoemResult)

/**
 * One deal of Bonak: dealing, bidding, the mandatory stok exchange, trump selection and final roem,
 * up to "ready to play". Immutable: every transition returns a new round or throws [RuleViolation],
 * leaving this one untouched.
 */
class Round private constructor(
    val dealNumber: Int,
    /** Increases with every accepted transition, so clients can make their requests conditional on the state they saw. */
    val version: Long,
    private val shuffler: Shuffler,
    val locations: Map<Card, CardLocation>,
    /** The stok card dealt face up. Everyone saw it during bidding, so it stays public knowledge. */
    val originalFaceUpCard: Card,
    /** Never exposed publicly; only the player who takes it into their hand sees it. */
    val originalFaceDownCard: Card,
    val auction: Auction,
    val phase: RoundPhase,
    /** Declarers in exchange order: the finalized contract's declarers, i.e. Praat in order of first bidding. */
    val exchangeOrder: List<String>,
    val exchangesCompleted: Int,
    /** The two cards each declarer took into their hand during the exchange. Private to that declarer. */
    val received: Map<String, List<Card>>,
    /** The two cards each declarer discarded. Private to that declarer (and the next Praat player as received). */
    val discards: Map<String, List<Card>>,
    /** Chosen by the declarer after the exchange. Stays null for Praat with several players, which has no trumps. */
    val trump: Suit?,
    /** Only for the declarer of a points or Bonak-with-roem contract. Other players' roem is never calculated. */
    val finalRoem: FinalRoem?,
    val failure: ContractFailure?,
    /** Why this deal replaced the previous one, if it did. */
    val redealReason: String?,
) {
    val players: List<String> get() = auction.players
    val dealer: String get() = auction.dealer

    val currentExchanger: String?
        get() = if (phase == RoundPhase.STOK_EXCHANGE) exchangeOrder[exchangesCompleted] else null

    /** The declarer who must choose trumps now, if any. */
    val trumpChooser: String?
        get() = if (phase == RoundPhase.TRUMP_SELECTION) auction.contract!!.declarers.single() else null

    /** Praat with several players is played without trumps. */
    val playsWithoutTrumps: Boolean
        get() = (auction.contract as? PraatContract)?.let { it.declarers.size > 1 } ?: false

    init {
        check(locations.keys == Deck.cards.toSet()) { "Every card must have exactly one location." }
    }

    companion object {
        const val HAND_SIZE = 6
        const val STOK_SIZE = 2
        const val DISCARD_SIZE = 2
        const val MIN_PLAYERS = 3
        const val MAX_PLAYERS = 5

        /** [firstVersion] lets a new round continue numbering after the previous one, so versions never repeat. */
        fun deal(players: List<String>, dealer: String, shuffler: Shuffler, firstVersion: Long = 1): Round =
            deal(players, dealer, shuffler, dealNumber = 1, version = firstVersion, redealReason = null)

        private fun deal(
            players: List<String>,
            dealer: String,
            shuffler: Shuffler,
            dealNumber: Int,
            version: Long,
            redealReason: String?,
        ): Round {
            if (players.size !in MIN_PLAYERS..MAX_PLAYERS) {
                throw RuleViolation("Bonak is played with $MIN_PLAYERS to $MAX_PLAYERS players.")
            }
            val auction = Auction.start(players, dealer)
            // Always shuffle the complete deck, so nothing from a previous deal carries over.
            val deck = shuffler.shuffle(Deck.cards)
            check(deck.size == Deck.cards.size && deck.toSet() == Deck.cards.toSet()) { "The shuffle lost cards." }

            // Deal from the player left of the dealer, clockwise; the deal order does not matter after shuffling.
            val firstSeat = (players.indexOf(dealer) + 1) % players.size
            val seating = players.indices.map { players[(firstSeat + it) % players.size] }
            val locations = LinkedHashMap<Card, CardLocation>()
            seating.forEachIndexed { seat, player ->
                deck.subList(seat * HAND_SIZE, (seat + 1) * HAND_SIZE).forEach { locations[it] = InHand(player) }
            }
            val stokStart = players.size * HAND_SIZE
            locations[deck[stokStart]] = StokFaceUp
            locations[deck[stokStart + 1]] = StokFaceDown
            deck.drop(stokStart + STOK_SIZE).forEach { locations[it] = Unused }

            return Round(
                dealNumber = dealNumber,
                version = version,
                shuffler = shuffler,
                locations = locations,
                originalFaceUpCard = deck[stokStart],
                originalFaceDownCard = deck[stokStart + 1],
                auction = auction,
                phase = RoundPhase.BIDDING,
                exchangeOrder = emptyList(),
                exchangesCompleted = 0,
                received = emptyMap(),
                discards = emptyMap(),
                trump = null,
                finalRoem = null,
                failure = null,
                redealReason = redealReason,
            )
        }
    }

    /** The player's cards, sorted by suit and rank. */
    fun hand(player: String): List<Card> = cardsAt(InHand(player))

    fun cardsAt(location: CardLocation): List<Card> =
        locations.filterValues { it == location }.keys.sortedWith(compareBy({ it.suit }, { it.rank }))

    fun bid(player: String, action: Action): Round {
        if (phase != RoundPhase.BIDDING) throw RuleViolation("Bidding is not in progress.")
        val next = auction.apply(player, action)
        return when (next.status) {
            AuctionStatus.IN_PROGRESS -> copy(auction = next)
            AuctionStatus.REDEAL_REQUIRED -> redeal(
                dealer,
                "Every player said Rond on the opening circuit of deal $dealNumber; $dealer deals again.",
            )
            AuctionStatus.ALL_PASSED -> {
                val nextDealer = players[(players.indexOf(dealer) + 1) % players.size]
                redeal(nextDealer, "Every player passed in deal $dealNumber; the deal passes to $nextDealer.")
            }
            AuctionStatus.CONTRACT_FINALIZED -> {
                val order = next.contract!!.declarers
                val stok = listOf(originalFaceUpCard, originalFaceDownCard)
                copy(
                    auction = next,
                    phase = RoundPhase.STOK_EXCHANGE,
                    exchangeOrder = order,
                    // Taking the stok is mandatory, so the first declarer picks it up straight away.
                    locations = locations + stok.associateWith { InHand(order.first()) },
                    received = mapOf(order.first() to stok),
                )
            }
        }
    }

    fun discard(player: String, cards: List<Card>): Round {
        if (phase != RoundPhase.STOK_EXCHANGE) throw RuleViolation("Discarding is only allowed during the stok exchange.")
        val exchanger = currentExchanger!!
        if (player != exchanger) throw RuleViolation("It is $exchanger's turn to exchange, not $player's.")
        if (cards.size != DISCARD_SIZE) throw RuleViolation("Discard exactly $DISCARD_SIZE cards.")
        if (cards.toSet().size != cards.size) throw RuleViolation("Discard $DISCARD_SIZE different cards.")
        if (cards.any { locations[it] != InHand(player) }) throw RuleViolation("You can only discard cards from your own hand.")

        // The discards become the stok of the next Praat player, who must take them; the last discards go out of play.
        val next = exchangeOrder.getOrNull(exchangesCompleted + 1)
        val done = next == null
        return copy(
            locations = locations + cards.associateWith { if (next != null) InHand(next) else Discarded },
            phase = when {
                !done -> RoundPhase.STOK_EXCHANGE
                playsWithoutTrumps -> RoundPhase.READY_TO_PLAY
                else -> RoundPhase.TRUMP_SELECTION
            },
            exchangesCompleted = exchangesCompleted + 1,
            received = if (next != null) received + (next to cards) else received,
            discards = discards + (player to cards),
        )
    }

    fun selectTrump(player: String, suit: Suit): Round {
        if (phase != RoundPhase.TRUMP_SELECTION) {
            throw RuleViolation(
                if (playsWithoutTrumps) "Praat with several players is played without trumps."
                else "Trumps can only be chosen by the declarer after the stok exchange."
            )
        }
        val declarer = trumpChooser!!
        if (player != declarer) throw RuleViolation("Only $declarer may choose trumps.")

        val contract = auction.contract!!
        if (!roemCounts(contract)) return copy(trump = suit, phase = RoundPhase.READY_TO_PLAY)

        val roem = FinalRoem(declarer, hand(declarer), suit, RoemCalculator.calculate(hand(declarer), suit))
        val committed = (contract as? BonakContract)?.declaredRoem
        return if (committed != null && roem.roem.total < committed) {
            copy(
                trump = suit,
                finalRoem = roem,
                phase = RoundPhase.CONTRACT_FAILED,
                failure = ContractFailure(
                    FailureReason.INSUFFICIENT_BONAK_ROEM,
                    committed,
                    "$declarer committed to at least $committed roem with Bonak, but the final hand holds less.",
                ),
            )
        } else {
            copy(trump = suit, finalRoem = roem, phase = RoundPhase.READY_TO_PLAY)
        }
    }

    /** For a points contract with a determined final roem: the trick requirements. */
    fun pointsRequirements(): PointsRequirements? {
        val contract = auction.contract as? PointsContract ?: return null
        val roem = finalRoem ?: return null
        return contract.requirements(roem.roem.total)
    }

    /** For Bonak with roem: whether the final roem meets the commitment, or null if not (yet) determined. */
    fun bonakCommitmentSatisfied(): Boolean? {
        val committed = (auction.contract as? BonakContract)?.declaredRoem ?: return null
        val roem = finalRoem ?: return null
        return roem.roem.total >= committed
    }

    /** Roem matters only for points contracts and Bonak with roem; never for Praat or plain Bonak. */
    private fun roemCounts(contract: Contract) =
        contract is PointsContract || (contract is BonakContract && contract.declaredRoem != null)

    /** Starts a fresh deal: hands, stok, discards and the auction are all replaced. */
    private fun redeal(newDealer: String, reason: String) =
        deal(players, newDealer, shuffler, dealNumber + 1, version + 1, reason)

    private fun copy(
        locations: Map<Card, CardLocation> = this.locations,
        auction: Auction = this.auction,
        phase: RoundPhase = this.phase,
        exchangeOrder: List<String> = this.exchangeOrder,
        exchangesCompleted: Int = this.exchangesCompleted,
        received: Map<String, List<Card>> = this.received,
        discards: Map<String, List<Card>> = this.discards,
        trump: Suit? = this.trump,
        finalRoem: FinalRoem? = this.finalRoem,
        failure: ContractFailure? = this.failure,
    ) = Round(
        dealNumber, version + 1, shuffler, locations, originalFaceUpCard, originalFaceDownCard, auction, phase,
        exchangeOrder, exchangesCompleted, received, discards, trump, finalRoem, failure, redealReason,
    )
}
