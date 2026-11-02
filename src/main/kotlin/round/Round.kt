package round

import auction.Action
import auction.Auction
import auction.AuctionStatus
import auction.PointsContract
import auction.PointsRequirements
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
    READY_TO_PLAY("The stok exchange is complete and final roem is determined. Ready to play."),
    BIDDING_UNRESOLVED(
        "Every player passed without a bid. The rules do not define an outcome for this; start a fresh round."
    ),
}

/** A declarer's six cards after their own discard, with the roem they hold for each possible trump suit. */
data class FinalRoem(val player: String, val hand: List<Card>, val byTrump: Map<Suit, RoemResult>) {
    companion object {
        fun of(player: String, hand: List<Card>) =
            FinalRoem(player, hand, Suit.entries.associateWith { RoemCalculator.calculate(hand, it) })
    }
}

/**
 * One deal of Bonak: dealing, bidding, the mandatory stok exchange and final roem, up to "ready to play".
 * Immutable: every transition returns a new round or throws [RuleViolation], leaving this one untouched.
 */
class Round private constructor(
    val dealNumber: Int,
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
    val finalRoem: Map<String, FinalRoem>,
    /** Why this deal replaced the previous one, if it did. */
    val redealReason: String?,
) {
    val players: List<String> get() = auction.players
    val dealer: String get() = auction.dealer

    val currentExchanger: String?
        get() = if (phase == RoundPhase.STOK_EXCHANGE) exchangeOrder[exchangesCompleted] else null

    init {
        check(locations.keys == Deck.cards.toSet()) { "Every card must have exactly one location." }
    }

    companion object {
        const val HAND_SIZE = 6
        const val STOK_SIZE = 2
        const val DISCARD_SIZE = 2
        val MAX_PLAYERS = (Deck.cards.size - STOK_SIZE) / HAND_SIZE

        fun deal(players: List<String>, dealer: String, shuffler: Shuffler): Round =
            deal(players, dealer, shuffler, dealNumber = 1, redealReason = null)

        private fun deal(
            players: List<String>,
            dealer: String,
            shuffler: Shuffler,
            dealNumber: Int,
            redealReason: String?,
        ): Round {
            if (players.size > MAX_PLAYERS) {
                throw RuleViolation(
                    "A ${Deck.cards.size}-card deck can deal $HAND_SIZE cards each plus a $STOK_SIZE-card stok " +
                        "to at most $MAX_PLAYERS players."
                )
            }
            val auction = Auction.start(players, dealer)
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
                finalRoem = emptyMap(),
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
            AuctionStatus.UNRESOLVED_ALL_PASSED -> copy(auction = next, phase = RoundPhase.BIDDING_UNRESOLVED)
            AuctionStatus.REDEAL_REQUIRED -> deal(
                players, dealer, shuffler, dealNumber + 1,
                redealReason = "Every player said Rond on the opening circuit of deal $dealNumber; the cards were redealt.",
            )
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
        val newLocations = locations + cards.associateWith { if (next != null) InHand(next) else Discarded }
        val retained = newLocations.filterValues { it == InHand(player) }.keys.toList()

        return copy(
            locations = newLocations,
            phase = if (next != null) RoundPhase.STOK_EXCHANGE else RoundPhase.READY_TO_PLAY,
            exchangesCompleted = exchangesCompleted + 1,
            received = if (next != null) received + (next to cards) else received,
            discards = discards + (player to cards),
            finalRoem = finalRoem + (player to FinalRoem.of(player, retained)),
        )
    }

    /** For a points contract with final roem: the trick requirements for each possible trump suit. */
    fun pointsRequirements(): Map<Suit, PointsRequirements>? {
        val contract = auction.contract as? PointsContract ?: return null
        val roem = finalRoem[contract.declarer] ?: return null
        return roem.byTrump.mapValues { contract.requirements(it.value.total) }
    }

    private fun copy(
        locations: Map<Card, CardLocation> = this.locations,
        auction: Auction = this.auction,
        phase: RoundPhase = this.phase,
        exchangeOrder: List<String> = this.exchangeOrder,
        exchangesCompleted: Int = this.exchangesCompleted,
        received: Map<String, List<Card>> = this.received,
        discards: Map<String, List<Card>> = this.discards,
        finalRoem: Map<String, FinalRoem> = this.finalRoem,
    ) = Round(
        dealNumber, shuffler, locations, originalFaceUpCard, originalFaceDownCard, auction, phase,
        exchangeOrder, exchangesCompleted, received, discards, finalRoem, redealReason,
    )
}
