package play

import model.Card
import model.Rank
import model.Suit

/** One card played into a trick, in playing order. */
data class Play(val player: String, val card: Card)

/** Card strength during play. Unrelated to roem sequence order and to card point values. */
object PlayRanking {
    /** Trumps, lowest to highest. The trump 9 is the Nel; the trump Jack is the strongest card. */
    val TRUMP_ORDER = listOf(
        Rank.SEVEN, Rank.EIGHT, Rank.TEN, Rank.QUEEN, Rank.KING, Rank.ACE, Rank.NINE, Rank.JACK,
    )

    /** Every non-trump suit, and every suit in a game without trumps, lowest to highest. */
    val NON_TRUMP_ORDER = listOf(
        Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE,
    )

    /** Strength of [rank] within its own suit; only meaningful when comparing cards of the same suit. */
    fun strength(rank: Rank, isTrump: Boolean): Int =
        (if (isTrump) TRUMP_ORDER else NON_TRUMP_ORDER).indexOf(rank)
}

/**
 * The winning play of a (complete or partial) trick. [trump] is null in a game without trumps.
 * Any trump beats any non-trump; otherwise the highest card of the suit led wins. Off-suit non-trumps never win,
 * and suits are never ranked against each other. This only compares cards; it does not check that they were legal.
 */
fun trickWinner(plays: List<Play>, trump: Suit?): Play {
    require(plays.isNotEmpty()) { "A trick needs at least one card." }
    val trumps = plays.filter { trump != null && it.card.suit == trump }
    if (trumps.isNotEmpty()) return trumps.maxBy { PlayRanking.strength(it.card.rank, isTrump = true) }
    val led = plays.first().card.suit
    return plays.filter { it.card.suit == led }.maxBy { PlayRanking.strength(it.card.rank, isTrump = false) }
}
