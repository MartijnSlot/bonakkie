package cli

import auction.Action
import auction.ActionType
import auction.RuleViolation
import model.Card
import model.Rank
import model.Shuffler
import model.Suit
import round.Round
import round.RoundPhase
import java.io.BufferedReader
import java.io.PrintStream

/**
 * A text version of dealing and bidding, played at one terminal. It shows every hand, then asks each player
 * in turn for a bid. All rules come from [Round]; this only parses input and prints state.
 */
class TextGame(
    private val input: BufferedReader,
    private val output: PrintStream,
    private val shuffler: Shuffler = Shuffler.random(),
    private val players: List<String> = listOf("Ann", "Bob", "Cas"),
    private val dealer: String = "Cas",
) {
    fun run() {
        var round = Round.deal(players, dealer, shuffler)
        printDeal(round)
        while (round.phase == RoundPhase.BIDDING) {
            val player = round.auction.currentPlayer!!
            output.println()
            output.println("Options: ${options(round)}")
            output.print("$player> ")
            output.flush()
            val line = input.readLine()?.trim() ?: return output.println("\nBye.")
            when (line.lowercase()) {
                "" -> continue
                "quit", "exit", "q" -> return output.println("Bye.")
                "help", "?" -> { printHelp(); continue }
                "hands" -> { printDeal(round); continue }
            }
            val action = parse(line) ?: run {
                output.println("Didn't understand \"$line\". Type help for the options.")
                null
            } ?: continue
            try {
                val next = round.bid(player, action)
                output.println("$player: ${action.describe()}")
                if (next.dealNumber != round.dealNumber) {
                    output.println()
                    output.println(next.redealReason)
                    printDeal(next)
                } else {
                    printStatus(next)
                }
                round = next
            } catch (e: RuleViolation) {
                output.println("Not allowed: ${e.message}")
            }
        }
        printOutcome(round)
    }

    /** Turns "60", "praat", "bonak", "bonak 40", "pass" or "rond" into an action; null if it isn't one. */
    fun parse(line: String): Action? {
        val words = line.trim().lowercase().split(Regex("\\s+"))
        val amount = words.getOrNull(1)?.toIntOrNull()
        return when {
            words.size == 1 && words[0].toIntOrNull() != null -> Action.points(words[0].toInt())
            words.size == 1 && words[0] in setOf("pass", "p") -> Action.PASS
            words.size == 1 && words[0] in setOf("rond", "r") -> Action.ROND
            words.size == 1 && words[0] == "praat" -> Action.PRAAT
            words.size == 1 && words[0] == "bonak" -> Action.BONAK
            words.size == 2 && words[0] == "bonak" && amount != null -> Action.bonakWithRoem(amount)
            else -> null
        }
    }

    private fun printDeal(round: Round) {
        output.println()
        output.println("Bonak - deal ${round.dealNumber}, ${round.dealer} deals")
        output.println()
        val width = (round.players.map { "$it:" } + "Stok:").maxOf { it.length }
        round.players.forEach { player ->
            val marker = if (player == round.dealer) " (dealer)" else ""
            output.println("  ${"$player:".padEnd(width)} ${round.hand(player).joinToString(" ") { text(it) }}$marker")
        }
        output.println("  ${"Stok:".padEnd(width)} ${text(round.originalFaceUpCard)} face up, one card face down")
        output.println()
        output.println("Bidding starts with ${round.auction.currentPlayer}. Type help for the options.")
    }

    private fun printStatus(round: Round) {
        val auction = round.auction
        auction.standingBid?.let { output.println("Standing bid: ${describe(round)}") }
        if (auction.passed.isNotEmpty()) output.println("Passed: ${round.players.filter { it in auction.passed }.joinToString(", ")}")
    }

    private fun printOutcome(round: Round) {
        output.println()
        output.println("Bidding is over: ${round.auction.contract!!.describe()}.")
        output.println("${round.phase.message} (The rest of the hand isn't part of the text game yet.)")
    }

    private fun describe(round: Round): String = when (val bid = round.auction.standingBid) {
        is auction.PointsBid -> "${bid.bidder} ${bid.amount} points"
        is auction.PraatBid -> "Praat by ${bid.participants.joinToString(", ")}"
        is auction.BonakBid -> "${bid.bidder} Bonak${bid.roem?.let { " with $it roem" } ?: ""}"
        null -> "none"
    }

    /** The legal options for the player to act, taken from the auction's own legal actions. */
    private fun options(round: Round): String = round.auction.legalActions().joinToString(", ") { legal ->
        when (legal.type) {
            ActionType.ROND -> "rond"
            ActionType.PASS -> "pass"
            ActionType.BID_POINTS -> legal.amounts!!.let { if (it.size == 1) "${it.first()}" else "${it.first()}-${it.last()} (steps of 5)" }
            ActionType.BID_PRAAT -> "praat"
            ActionType.BID_BONAK -> "bonak"
            ActionType.BID_BONAK_WITH_ROEM -> "bonak ${legal.amounts!!.joinToString("|")}"
        }
    }

    private fun printHelp() {
        output.println(
            """
            |  60          bid 60 points (50-125, steps of 5)
            |  praat       bid or join Praat
            |  bonak       bid Bonak
            |  bonak 40    bid Bonak with 40 roem (after an earlier Bonak)
            |  pass        pass for the rest of the auction
            |  rond        skip your turn (only while everyone before you said rond)
            |  hands       show the hands again
            |  quit        stop
            """.trimMargin()
        )
    }

    companion object {
        private val SUITS = mapOf(Suit.CLUBS to "♣", Suit.DIAMONDS to "♦", Suit.HEARTS to "♥", Suit.SPADES to "♠")
        private val RANKS = mapOf(
            Rank.SEVEN to "7", Rank.EIGHT to "8", Rank.NINE to "9", Rank.TEN to "10",
            Rank.JACK to "J", Rank.QUEEN to "Q", Rank.KING to "K", Rank.ACE to "A",
        )

        fun text(card: Card) = RANKS.getValue(card.rank) + SUITS.getValue(card.suit)
    }
}
