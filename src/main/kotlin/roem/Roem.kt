package roem

import model.Card
import model.Rank
import model.Suit

enum class RoemCombinationType(val value: Int, val label: String) {
    THREE_SEQUENCE(20, "Three consecutive cards of one suit"),
    STUK(20, "King and Queen of trumps (stuk)"),
    THREE_SEQUENCE_WITH_STUK(40, "Three consecutive cards including stuk"),
    FOUR_SEQUENCE(50, "Four consecutive cards of one suit"),
    FOUR_SEQUENCE_WITH_STUK(70, "Four consecutive cards including stuk"),
    FIVE_OR_SIX_SEQUENCE(100, "Five or six consecutive cards of one suit"),
    FOUR_ACES(100, "Four Aces"),
    FOUR_KINGS(100, "Four Kings"),
    FOUR_QUEENS(100, "Four Queens"),
    FOUR_JACKS(100, "Four Jacks"),
    FOUR_TENS(100, "Four Tens"),
}

data class RoemCombination(val type: RoemCombinationType, val cards: List<Card>) {
    val value: Int get() = type.value
}

/** One valid way to score a set of cards. */
data class RoemAlternative(val combinations: List<RoemCombination>) {
    /** Sum of all combinations before the cap is applied. */
    val uncappedTotal: Int = combinations.sumOf { it.value }
    val total: Int = minOf(uncappedTotal, RoemCalculator.MAX_ROEM)
}

/**
 * Every valid way to score a set of cards, and the one that counts. Usually there is exactly one. There are several
 * when a card could score in a sequence or in a four-of-a-kind, but not both; then the highest total counts.
 */
data class RoemResult(val alternatives: List<RoemAlternative>) {
    /** The alternative that counts: the highest total (ties broken by the higher uncapped total). */
    val best: RoemAlternative = alternatives.maxWith(compareBy({ it.total }, { it.uncappedTotal }))
    val total: Int = best.total
    val uncappedTotal: Int = best.uncappedTotal
    val combinations: List<RoemCombination> = best.combinations
    val possibleTotals: List<Int> = alternatives.map { it.total }.distinct().sorted()
}

object RoemCalculator {
    const val MAX_ROEM = 100

    private val FOUR_OF_A_KIND = mapOf(
        Rank.ACE to RoemCombinationType.FOUR_ACES,
        Rank.KING to RoemCombinationType.FOUR_KINGS,
        Rank.QUEEN to RoemCombinationType.FOUR_QUEENS,
        Rank.JACK to RoemCombinationType.FOUR_JACKS,
        Rank.TEN to RoemCombinationType.FOUR_TENS,
    )

    /**
     * Every roem total a final six-card hand can hold, and so every amount Bonak with roem may declare.
     * 30, 80 and 90 cannot be held: `RoemCalculatorTest` checks this set against every possible six-card hand.
     */
    val achievableTotals: Set<Int> = sortedSetOf(20, 40, 50, 60, 70, 100)

    /**
     * A card scores in at most one combination, except that the trump King and Queen also count for stuk.
     * Sequences are scored whole: shorter sequences inside a longer one are never scored separately.
     */
    fun calculate(cards: Collection<Card>, trump: Suit): RoemResult {
        require(cards.toSet().size == cards.size) { "Each card may only be given once." }
        val hand = cards.toSet()
        val fourOfAKinds = FOUR_OF_A_KIND.keys.filter { rank -> Suit.entries.all { Card(rank, it) in hand } }
        val alternatives = subsets(fourOfAKinds).map { chosen -> score(hand, trump, chosen) }
        return RoemResult(withoutDominated(alternatives))
    }

    /** Scores [hand] with the [chosen] four-of-a-kinds; their cards are then unavailable for sequences. */
    private fun score(hand: Set<Card>, trump: Suit, chosen: List<Rank>): RoemAlternative {
        val kindCards = chosen.flatMap { rank -> Suit.entries.map { Card(rank, it) } }.toSet()
        val stuk = stukCards(trump)
        val combinations = mutableListOf<RoemCombination>()
        var stukScored = false

        Suit.entries.forEach { suit ->
            maximalRuns(hand.filter { it.suit == suit && it !in kindCards }).filter { it.size >= 3 }.forEach { run ->
                val hasStuk = suit == trump && run.containsAll(stuk)
                stukScored = stukScored || hasStuk
                when (run.size) {
                    3 -> combinations += RoemCombination(
                        if (hasStuk) RoemCombinationType.THREE_SEQUENCE_WITH_STUK else RoemCombinationType.THREE_SEQUENCE, run
                    )
                    4 -> combinations += RoemCombination(
                        if (hasStuk) RoemCombinationType.FOUR_SEQUENCE_WITH_STUK else RoemCombinationType.FOUR_SEQUENCE, run
                    )
                    else -> {
                        // Longer sequences score as a five-card sequence; stuk still adds 20 (the cap applies later).
                        combinations += RoemCombination(RoemCombinationType.FIVE_OR_SIX_SEQUENCE, run)
                        if (hasStuk) combinations += RoemCombination(RoemCombinationType.STUK, stuk)
                    }
                }
            }
        }

        // Stuk may share its cards with another combination, but is never counted twice.
        if (!stukScored && hand.containsAll(stuk)) combinations += RoemCombination(RoemCombinationType.STUK, stuk)

        chosen.forEach { rank ->
            combinations += RoemCombination(FOUR_OF_A_KIND.getValue(rank), Suit.entries.map { Card(rank, it) })
        }
        return RoemAlternative(combinations)
    }

    /**
     * Drops alternatives that only leave out combinations another alternative also scores
     * (e.g. not claiming four Jacks that conflict with nothing), and duplicates.
     */
    private fun withoutDominated(alternatives: List<RoemAlternative>): List<RoemAlternative> {
        val counts = alternatives.map { alternative -> alternative.combinations.groupingBy { it }.eachCount() }.distinct()
        fun contains(big: Map<RoemCombination, Int>, small: Map<RoemCombination, Int>) =
            small.all { (combination, count) -> (big[combination] ?: 0) >= count }
        return counts
            .filter { candidate -> counts.none { other -> other != candidate && contains(other, candidate) } }
            .map { kept -> alternatives.first { it.combinations.groupingBy { c -> c }.eachCount() == kept } }
    }

    private fun <T> subsets(items: List<T>): List<List<T>> =
        items.fold(listOf(emptyList())) { acc, item -> acc + acc.map { it + item } }

    private fun stukCards(trump: Suit) = listOf(Card(Rank.QUEEN, trump), Card(Rank.KING, trump))

    private fun maximalRuns(cardsOfOneSuit: List<Card>): List<List<Card>> {
        val runs = mutableListOf<MutableList<Card>>()
        cardsOfOneSuit.sortedBy { it.rank.ordinal }.forEach { card ->
            val last = runs.lastOrNull()
            if (last != null && last.last().rank.ordinal + 1 == card.rank.ordinal) last += card else runs += mutableListOf(card)
        }
        return runs
    }
}
