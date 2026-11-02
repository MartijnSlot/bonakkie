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

data class RoemResult(val combinations: List<RoemCombination>) {
    /** Sum of all combinations before the cap is applied. */
    val uncappedTotal: Int = combinations.sumOf { it.value }
    val total: Int = minOf(uncappedTotal, RoemCalculator.MAX_ROEM)
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
     * Every total a player can declare: any sum of one or more combination values, capped at [MAX_ROEM].
     * Hand size is not part of the rules here, so it does not restrict which totals are achievable.
     */
    val achievableTotals: Set<Int> = run {
        val values = RoemCombinationType.entries.map { it.value }.toSet()
        val reachable = sortedSetOf<Int>()
        val frontier = ArrayDeque(values)
        while (frontier.isNotEmpty()) {
            val total = minOf(frontier.removeFirst(), MAX_ROEM)
            if (reachable.add(total) && total < MAX_ROEM) {
                values.forEach { frontier.addLast(total + it) }
            }
        }
        reachable
    }

    fun calculate(cards: Collection<Card>, trump: Suit): RoemResult {
        require(cards.toSet().size == cards.size) { "Each card may only be given once." }
        val combinations = mutableListOf<RoemCombination>()
        var stukInSequence = false

        Suit.entries.forEach { suit ->
            maximalRuns(cards.filter { it.suit == suit }).filter { it.size >= 3 }.forEach { run ->
                val hasStuk = suit == trump && run.any { it.rank == Rank.KING } && run.any { it.rank == Rank.QUEEN }
                stukInSequence = stukInSequence || hasStuk
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
                        if (hasStuk) combinations += RoemCombination(RoemCombinationType.STUK, stukCards(trump))
                    }
                }
            }
        }

        val trumpKingAndQueen = stukCards(trump)
        if (!stukInSequence && cards.containsAll(trumpKingAndQueen)) {
            combinations += RoemCombination(RoemCombinationType.STUK, trumpKingAndQueen)
        }

        FOUR_OF_A_KIND.forEach { (rank, type) ->
            val ofRank = cards.filter { it.rank == rank }
            if (ofRank.size == Suit.entries.size) combinations += RoemCombination(type, ofRank.sortedBy { it.suit })
        }

        return RoemResult(combinations)
    }

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
