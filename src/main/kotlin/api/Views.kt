package api

import auction.ActionType
import auction.Auction
import auction.AuctionStatus
import auction.BonakBid
import auction.BonakContract
import auction.Contract
import auction.LegalAction
import auction.PointsBid
import auction.PointsContract
import auction.PraatBid
import auction.PraatContract
import auction.StandingBid
import model.Card
import model.Suit
import roem.RoemCombinationType
import roem.RoemResult

data class StartAuctionRequest(val players: List<String>, val dealer: String)

data class ActionRequest(val player: String, val type: ActionType, val amount: Int? = null)

data class RoemRequest(val trump: Suit, val cards: List<Card>)

data class HistoryView(val player: String, val type: ActionType, val amount: Int?, val description: String)

data class StandingBidView(
    val type: String,
    val bidders: List<String>,
    val amount: Int?,
    val description: String,
)

data class ContractView(
    val type: String,
    val declarers: List<String>,
    val bidAmount: Int?,
    val bonakRoem: Int?,
    val description: String,
)

data class AuctionView(
    val players: List<String>,
    val dealer: String,
    val currentPlayer: String?,
    val status: AuctionStatus,
    val statusMessage: String,
    val rondAllowed: Boolean,
    val passedPlayers: List<String>,
    val standingBid: StandingBidView?,
    val history: List<HistoryView>,
    val contract: ContractView?,
    val legalActions: List<LegalAction>,
) {
    companion object {
        fun of(auction: Auction) = AuctionView(
            players = auction.players,
            dealer = auction.dealer,
            currentPlayer = auction.currentPlayer,
            status = auction.status,
            statusMessage = auction.status.message,
            rondAllowed = auction.rondAllowed,
            passedPlayers = auction.players.filter { it in auction.passed },
            standingBid = auction.standingBid?.let(::bidView),
            history = auction.history.map { HistoryView(it.player, it.action.type, it.action.amount, it.action.describe()) },
            contract = auction.contract?.let(::contractView),
            legalActions = auction.legalActions(),
        )

        private fun bidView(bid: StandingBid) = when (bid) {
            is PointsBid -> StandingBidView("POINTS", listOf(bid.bidder), bid.amount, "${bid.bidder}: ${bid.amount} points")
            is PraatBid -> StandingBidView("PRAAT", bid.participants, null, "Praat: ${bid.participants.joinToString(", ")}")
            is BonakBid -> StandingBidView(
                "BONAK", listOf(bid.bidder), bid.roem,
                "${bid.bidder}: Bonak${bid.roem?.let { " with $it roem" } ?: ""}",
            )
        }

        private fun contractView(contract: Contract) = when (contract) {
            is PointsContract -> ContractView("POINTS", contract.declarers, contract.bidAmount, null, contract.describe())
            is PraatContract -> ContractView("PRAAT", contract.declarers, null, null, contract.describe())
            is BonakContract -> ContractView("BONAK", contract.declarers, null, contract.declaredRoem, contract.describe())
        }
    }
}

data class RoemCombinationTypeView(val type: RoemCombinationType, val label: String, val value: Int)

data class RoemCombinationView(val type: RoemCombinationType, val label: String, val value: Int, val cards: List<Card>)

data class RoemResultView(val combinations: List<RoemCombinationView>, val uncappedTotal: Int, val total: Int) {
    companion object {
        fun of(result: RoemResult) = RoemResultView(
            combinations = result.combinations.map { RoemCombinationView(it.type, it.type.label, it.value, it.cards) },
            uncappedTotal = result.uncappedTotal,
            total = result.total,
        )
    }
}
