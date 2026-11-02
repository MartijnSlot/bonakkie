package api

import auction.BonakContract
import auction.PointsRequirements
import model.Card
import model.Suit
import round.Discarded
import round.Round
import round.RoundPhase
import round.StokFaceDown
import round.StokFaceUp
import round.Unused

const val TRUMP_NOTE =
    "Trump selection is not specified yet, so no trump suit is set. Final roem is given for every possible trump suit."

data class ExchangeView(val order: List<String>, val completed: List<String>, val currentPlayer: String?)

/**
 * What every player may know. Card identities here are limited to the original face-up stok card,
 * which was public during bidding. It says nothing about where that card went afterwards.
 */
data class RoundView(
    val dealNumber: Int,
    val redealReason: String?,
    val players: List<String>,
    val dealer: String,
    val phase: RoundPhase,
    val phaseMessage: String,
    val faceUpStokCard: Card,
    val stokCardsFaceUp: Int,
    val stokCardsFaceDown: Int,
    val handSizes: Map<String, Int>,
    val unusedCards: Int,
    val discardedCards: Int,
    val auction: AuctionView,
    val exchange: ExchangeView?,
    val trumpSuit: Suit?,
    val trumpNote: String,
) {
    companion object {
        fun of(round: Round) = RoundView(
            dealNumber = round.dealNumber,
            redealReason = round.redealReason,
            players = round.players,
            dealer = round.dealer,
            phase = round.phase,
            phaseMessage = round.phase.message,
            faceUpStokCard = round.originalFaceUpCard,
            stokCardsFaceUp = round.cardsAt(StokFaceUp).size,
            stokCardsFaceDown = round.cardsAt(StokFaceDown).size,
            handSizes = round.players.associateWith { round.hand(it).size },
            unusedCards = round.cardsAt(Unused).size,
            discardedCards = round.cardsAt(Discarded).size,
            auction = AuctionView.of(round.auction),
            exchange = round.exchangeOrder.takeIf { it.isNotEmpty() }?.let {
                ExchangeView(it, it.take(round.exchangesCompleted), round.currentExchanger)
            },
            trumpSuit = null,
            trumpNote = TRUMP_NOTE,
        )
    }
}

data class FinalRoemView(
    val hand: List<Card>,
    val roemByTrump: Map<Suit, RoemResultView>,
    /** Bonak with roem: the amount committed during bidding, kept next to the actual final roem. */
    val committedBonakRoem: Int?,
    /** Points contracts: trick requirements for each possible trump suit. */
    val pointsRequirementsByTrump: Map<Suit, PointsRequirements>?,
)

/** One player's view: the public round plus their own cards. Never contains another player's hidden cards. */
data class PlayerView(
    val player: String,
    val round: RoundView,
    val hand: List<Card>,
    val receivedFromStok: List<Card>?,
    val discarded: List<Card>?,
    val finalRoem: FinalRoemView?,
) {
    companion object {
        fun of(round: Round, player: String) = PlayerView(
            player = player,
            round = RoundView.of(round),
            hand = round.hand(player),
            receivedFromStok = round.received[player],
            discarded = round.discards[player],
            finalRoem = round.finalRoem[player]?.let { roem ->
                FinalRoemView(
                    hand = roem.hand.sortedWith(compareBy({ it.suit }, { it.rank })),
                    roemByTrump = roem.byTrump.mapValues { RoemResultView.of(it.value) },
                    committedBonakRoem = (round.auction.contract as? BonakContract)?.declaredRoem,
                    pointsRequirementsByTrump = round.pointsRequirements(),
                )
            },
        )
    }
}
