package api

import auction.BonakContract
import auction.LegalAction
import auction.PointsRequirements
import model.Card
import model.Suit
import round.ContractFailure
import round.Discarded
import round.FailureReason
import round.Round
import round.RoundPhase
import round.StokFaceDown
import round.StokFaceUp
import round.Unused

data class ExchangeView(val order: List<String>, val completed: List<String>, val currentPlayer: String?)

/** Public: the failure reason and the roem committed in (public) bidding. The actual roem stays with the declarer. */
data class FailureView(val reason: FailureReason, val declaredRoem: Int, val message: String) {
    companion object {
        fun of(failure: ContractFailure) = FailureView(failure.reason, failure.declaredRoem, failure.message)
    }
}

/**
 * What every player may know. Card identities here are limited to the original face-up stok card,
 * which was public during bidding. It says nothing about where that card went afterwards.
 */
data class RoundView(
    val dealNumber: Int,
    /** Send this back with every bid, discard and trump choice. */
    val version: Long,
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
    /** The declarer who must choose trumps now, if any. */
    val trumpChooser: String?,
    val trumpSuit: Suit?,
    val playsWithoutTrumps: Boolean,
    val failure: FailureView?,
) {
    companion object {
        fun of(round: Round) = RoundView(
            dealNumber = round.dealNumber,
            version = round.version,
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
            trumpChooser = round.trumpChooser,
            trumpSuit = round.trump,
            playsWithoutTrumps = round.playsWithoutTrumps,
            failure = round.failure?.let(FailureView::of),
        )
    }
}

data class FinalRoemView(
    val hand: List<Card>,
    val trump: Suit,
    val roem: RoemResultView,
    /** Bonak with roem: the minimum committed during bidding, kept next to the actual final roem. */
    val committedBonakRoem: Int?,
    val bonakCommitmentSatisfied: Boolean?,
    /** Points contracts: the trick requirements once the final roem is determined. */
    val pointsRequirements: PointsRequirements?,
)

/** What this player can do right now. Built from the same rules that accept the actions. */
data class AvailableActionsView(
    /** The legal bids, when it is this player's turn to bid. */
    val bids: List<LegalAction>,
    /** How many cards to discard, when this player must discard now. */
    val discardCount: Int?,
    /** The suits to choose from, when this player must choose trumps now. */
    val trumpSuits: List<Suit>,
) {
    companion object {
        fun of(round: Round, player: String) = AvailableActionsView(
            bids = if (round.phase == RoundPhase.BIDDING && round.auction.currentPlayer == player) {
                round.auction.legalActions()
            } else {
                emptyList()
            },
            discardCount = if (round.currentExchanger == player) Round.DISCARD_SIZE else null,
            trumpSuits = if (round.trumpChooser == player) Suit.entries else emptyList(),
        )
    }
}

/** One player's view: the public round plus their own cards. Never contains another player's hidden cards. */
data class PlayerView(
    val player: String,
    val round: RoundView,
    val hand: List<Card>,
    val receivedFromStok: List<Card>?,
    val discarded: List<Card>?,
    val availableActions: AvailableActionsView,
    val finalRoem: FinalRoemView?,
) {
    companion object {
        fun of(round: Round, player: String) = PlayerView(
            player = player,
            round = RoundView.of(round),
            hand = round.hand(player),
            receivedFromStok = round.received[player],
            discarded = round.discards[player],
            availableActions = AvailableActionsView.of(round, player),
            finalRoem = round.finalRoem?.takeIf { it.player == player }?.let { roem ->
                FinalRoemView(
                    hand = roem.hand,
                    trump = roem.trump,
                    roem = RoemResultView.of(roem.roem),
                    committedBonakRoem = (round.auction.contract as? BonakContract)?.declaredRoem,
                    bonakCommitmentSatisfied = round.bonakCommitmentSatisfied(),
                    pointsRequirements = round.pointsRequirements(),
                )
            },
        )
    }
}
