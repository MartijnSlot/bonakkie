package api

import auction.BonakContract
import auction.LegalAction
import auction.PointsRequirements
import model.Card
import model.Suit
import round.ContractFailure
import play.Play
import play.UnconfirmedRule
import round.Discarded
import round.NoTrumps
import round.Trumps
import round.FailureReason
import round.Round
import round.RoundPhase
import round.StokFaceDown
import round.StokFaceUp
import round.Unused

enum class TrumpStatus {
    /** Trumps are not determined yet (before or during trump selection). */
    NOT_CHOSEN,
    CHOSEN,
    /** Explicitly no trumps: Praat with several players. */
    NO_TRUMPS,
}

data class BlockedRuleView(val rule: UnconfirmedRule, val description: String)

/** A completed trick. [plays] is only filled for the most recent one: earlier tricks may not be looked back at. */
data class CompletedTrickView(val number: Int, val leader: String, val winner: String, val plays: List<Play>?)

/**
 * The play phase, as everyone at the table sees it: the cards in the current trick and in the last completed
 * trick are face up. Earlier tricks only show who led and who won them.
 */
data class PlayView(
    val trickNumber: Int,
    val leader: String,
    val currentPlayer: String?,
    val currentTrick: List<Play>,
    val completedTricks: List<CompletedTrickView>,
    val tricksWon: Map<String, Int>,
) {
    companion object {
        fun of(round: Round) = round.play?.let { state ->
            val last = state.completedTricks.lastOrNull()
            PlayView(
                trickNumber = state.trickNumber,
                leader = state.leader,
                currentPlayer = round.currentPlayer,
                currentTrick = state.currentTrick,
                completedTricks = state.completedTricks.map { trick ->
                    CompletedTrickView(trick.number, trick.leader, trick.winner, trick.plays.takeIf { trick == last })
                },
                tricksWon = round.tricksWon,
            )
        }
    }
}

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
    val handId: String,
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
    val trumpStatus: TrumpStatus,
    val playsWithoutTrumps: Boolean,
    val failure: FailureView?,
    /** Rules that must be confirmed before this hand can be played; play stays blocked until then. */
    val playBlockedBy: List<BlockedRuleView>,
    val play: PlayView?,
) {
    companion object {
        fun of(round: Round) = RoundView(
            handId = round.handId,
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
            trumpStatus = when (round.trumpMode) {
                is Trumps -> TrumpStatus.CHOSEN
                NoTrumps -> TrumpStatus.NO_TRUMPS
                null -> TrumpStatus.NOT_CHOSEN
            },
            playsWithoutTrumps = round.playsWithoutTrumps,
            failure = round.failure?.let(FailureView::of),
            playBlockedBy = round.playBlockedBy.map { BlockedRuleView(it, it.description) },
            play = PlayView.of(round),
        )
    }
}

data class FinalRoemView(
    /** The declarer's cards after the exchange; null once play has started (see [PlayerView]). */
    val hand: List<Card>?,
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
    /** The cards this player may play now, from the same policy that validates plays. */
    val playableCards: List<Card>,
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
            playableCards = round.legalCards(player).sortedWith(compareBy({ it.suit }, { it.rank })),
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
        /**
         * Only the last completed trick may be looked back at. Once play starts, cards known from before play
         * (stok cards received, the hand behind the roem) are no longer shown, as they may have been played since.
         * Discards are kept: they are out of play.
         */
        private fun beforePlay(round: Round) = round.play == null

        fun of(round: Round, player: String) = PlayerView(
            player = player,
            round = RoundView.of(round),
            hand = round.hand(player),
            receivedFromStok = round.received[player]?.takeIf { beforePlay(round) },
            discarded = round.discards[player],
            availableActions = AvailableActionsView.of(round, player),
            finalRoem = round.finalRoem?.takeIf { it.player == player }?.let { roem ->
                FinalRoemView(
                    hand = roem.hand.takeIf { beforePlay(round) },
                    trump = roem.trump,
                    roem = RoemResultView.of(roem.roem, showCards = beforePlay(round)),
                    committedBonakRoem = (round.auction.contract as? BonakContract)?.declaredRoem,
                    bonakCommitmentSatisfied = round.bonakCommitmentSatisfied(),
                    pointsRequirements = round.pointsRequirements(),
                )
            },
        )
    }
}
