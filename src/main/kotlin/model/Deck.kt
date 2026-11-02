package model

import kotlin.random.Random

object Deck {
    /** The 32-card deck: 7 through Ace in each suit. */
    val cards: List<Card> = Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit) } }
}

/** Puts the deck in dealing order. Injectable so that dealing can be made deterministic. */
fun interface Shuffler {
    fun shuffle(cards: List<Card>): List<Card>

    companion object {
        fun random(random: Random = Random.Default) = Shuffler { it.shuffled(random) }
        fun seeded(seed: Long) = random(Random(seed))
    }
}
