package roem

import model.Card
import model.Deck
import model.Rank
import model.Rank.ACE
import model.Rank.EIGHT
import model.Rank.JACK
import model.Rank.KING
import model.Rank.NINE
import model.Rank.QUEEN
import model.Rank.SEVEN
import model.Rank.TEN
import model.Suit
import model.Suit.CLUBS
import model.Suit.DIAMONDS
import model.Suit.HEARTS
import model.Suit.SPADES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import roem.RoemCombinationType.FIVE_OR_SIX_SEQUENCE
import roem.RoemCombinationType.FOUR_ACES
import roem.RoemCombinationType.FOUR_JACKS
import roem.RoemCombinationType.FOUR_KINGS
import roem.RoemCombinationType.FOUR_SEQUENCE
import roem.RoemCombinationType.FOUR_SEQUENCE_WITH_STUK
import roem.RoemCombinationType.STUK
import roem.RoemCombinationType.THREE_SEQUENCE
import roem.RoemCombinationType.THREE_SEQUENCE_WITH_STUK

class RoemCalculatorTest {

    private fun cards(suit: Suit, vararg ranks: Rank) = ranks.map { Card(it, suit) }
    private fun fourOf(rank: Rank) = Suit.entries.map { Card(rank, it) }
    private fun types(result: RoemResult) = result.combinations!!.map { it.type }

    @Test
    fun `three consecutive cards score 20`() {
        val result = RoemCalculator.calculate(cards(CLUBS, SEVEN, EIGHT, NINE), trump = HEARTS)
        assertEquals(listOf(THREE_SEQUENCE), types(result))
        assertEquals(20, result.total)
    }

    @Test
    fun `stuk on its own scores 20`() {
        val result = RoemCalculator.calculate(cards(HEARTS, QUEEN, KING) + Card(SEVEN, CLUBS), trump = HEARTS)
        assertEquals(listOf(STUK), types(result))
        assertEquals(20, result.total)
    }

    @Test
    fun `King and Queen outside trumps are not stuk`() {
        assertEquals(0, RoemCalculator.calculate(cards(CLUBS, QUEEN, KING), trump = HEARTS).total)
        assertEquals(listOf(THREE_SEQUENCE), types(RoemCalculator.calculate(cards(CLUBS, QUEEN, KING, ACE), HEARTS)))
    }

    @Test
    fun `three consecutive cards including stuk score 40 without counting stuk twice`() {
        val result = RoemCalculator.calculate(cards(HEARTS, JACK, QUEEN, KING), trump = HEARTS)
        assertEquals(listOf(THREE_SEQUENCE_WITH_STUK), types(result))
        assertEquals(40, result.total)
    }

    @Test
    fun `four consecutive cards score 50 without counting shorter subsequences`() {
        val result = RoemCalculator.calculate(cards(SPADES, SEVEN, EIGHT, NINE, TEN), trump = HEARTS)
        assertEquals(listOf(FOUR_SEQUENCE), types(result))
        assertEquals(50, result.total)
    }

    @Test
    fun `four consecutive cards including stuk score 70`() {
        val result = RoemCalculator.calculate(cards(HEARTS, JACK, QUEEN, KING, ACE), trump = HEARTS)
        assertEquals(listOf(FOUR_SEQUENCE_WITH_STUK), types(result))
        assertEquals(70, result.total)
    }

    @Test
    fun `five consecutive cards score 100`() {
        val result = RoemCalculator.calculate(cards(DIAMONDS, SEVEN, EIGHT, NINE, TEN, JACK), trump = HEARTS)
        assertEquals(listOf(FIVE_OR_SIX_SEQUENCE), types(result))
        assertEquals(100, result.total)
    }

    @Test
    fun `six consecutive cards score 100 as a five card sequence`() {
        val result = RoemCalculator.calculate(cards(DIAMONDS, SEVEN, EIGHT, NINE, TEN, JACK, QUEEN), trump = HEARTS)
        assertEquals(listOf(FIVE_OR_SIX_SEQUENCE), types(result))
        assertEquals(100, result.total)
    }

    @Test
    fun `five card sequence including stuk is capped at 100`() {
        val result = RoemCalculator.calculate(cards(HEARTS, TEN, JACK, QUEEN, KING, ACE), trump = HEARTS)
        assertEquals(listOf(FIVE_OR_SIX_SEQUENCE, STUK), types(result))
        assertEquals(120, result.uncappedTotal)
        assertEquals(100, result.total)
    }

    @ParameterizedTest
    @EnumSource(value = Rank::class, names = ["ACE", "KING", "QUEEN", "JACK", "TEN"])
    fun `four Aces, Kings, Queens, Jacks and Tens score 100`(rank: Rank) {
        val result = RoemCalculator.calculate(fourOf(rank), trump = HEARTS)
        assertEquals(1, result.combinations!!.size)
        assertEquals(100, result.total)
    }

    @Test
    fun `four Jacks score 100, never 200`() {
        val result = RoemCalculator.calculate(fourOf(JACK), trump = CLUBS)
        assertEquals(100, result.uncappedTotal)
        assertEquals(100, result.total)
    }

    @ParameterizedTest
    @EnumSource(value = Rank::class, names = ["SEVEN", "EIGHT", "NINE"])
    fun `four Sevens, Eights and Nines do not score`(rank: Rank) {
        assertEquals(0, RoemCalculator.calculate(fourOf(rank), trump = HEARTS).total)
    }

    @Test
    fun `two separate three card sequences add up to 40`() {
        val result = RoemCalculator.calculate(
            cards(CLUBS, SEVEN, EIGHT, NINE) + cards(SPADES, TEN, JACK, QUEEN), trump = HEARTS,
        )
        assertEquals(listOf(THREE_SEQUENCE, THREE_SEQUENCE), types(result))
        assertEquals(40, result.total)
    }

    @Test
    fun `two separate three card sequences in the same suit add up to 40`() {
        val result = RoemCalculator.calculate(cards(CLUBS, SEVEN, EIGHT, NINE, QUEEN, KING, ACE), trump = HEARTS)
        assertEquals(40, result.total)
    }

    @Test
    fun `separate combinations are added together`() {
        val result = RoemCalculator.calculate(cards(CLUBS, SEVEN, EIGHT, NINE) + fourOf(ACE), trump = HEARTS)
        assertEquals(listOf(THREE_SEQUENCE, FOUR_ACES), types(result))
        assertEquals(120, result.uncappedTotal)
        assertEquals(100, result.total)
    }

    @Test
    fun `a card cannot score in both a sequence and a four of a kind, and the highest alternative counts`() {
        val result = RoemCalculator.calculate(fourOf(JACK) + cards(HEARTS, TEN, QUEEN), trump = CLUBS)
        assertEquals(
            setOf(listOf(FOUR_JACKS), listOf(THREE_SEQUENCE)),
            result.alternatives.map { alternative -> alternative.combinations.map { it.type } }.toSet(),
        )
        assertEquals(listOf(20, 100), result.possibleTotals)
        assertEquals(100, result.total)
        assertEquals(listOf(FOUR_JACKS), types(result))
    }

    @Test
    fun `stuk may share cards with a four of a kind`() {
        val result = RoemCalculator.calculate(fourOf(KING) + Card(QUEEN, HEARTS), trump = HEARTS)
        assertEquals(listOf(STUK, FOUR_KINGS), types(result))
        assertEquals(120, result.uncappedTotal)
        assertEquals(100, result.total)
    }

    @Test
    fun `stuk shares cards while the sequence and four Kings compete`() {
        val result = RoemCalculator.calculate(cards(HEARTS, JACK, QUEEN) + fourOf(KING), trump = HEARTS)
        assertEquals(
            setOf(listOf(STUK, FOUR_KINGS), listOf(THREE_SEQUENCE_WITH_STUK)),
            result.alternatives.map { alternative -> alternative.combinations.map { it.type } }.toSet(),
        )
        assertEquals(listOf(40, 100), result.possibleTotals)
        assertEquals(100, result.total)
    }

    @Test
    fun `stuk is counted only once`() {
        val result = RoemCalculator.calculate(fourOf(KING) + fourOf(QUEEN), trump = HEARTS)
        assertEquals(1, result.combinations!!.count { it.type == STUK })
        assertEquals(100, result.total)
    }

    @Test
    fun `four of a kind not conflicting with anything is always scored`() {
        val result = RoemCalculator.calculate(fourOf(ACE) + cards(CLUBS, SEVEN), trump = HEARTS)
        assertEquals(1, result.alternatives.size)
        assertEquals(listOf(FOUR_ACES), types(result))
    }

    @Test
    fun `combinations worth 70 plus 40 are capped at 100`() {
        val result = RoemCalculator.calculate(
            cards(HEARTS, JACK, QUEEN, KING, ACE) + cards(CLUBS, SEVEN, EIGHT, NINE) + cards(SPADES, SEVEN, EIGHT, NINE),
            trump = HEARTS,
        )
        assertEquals(110, result.uncappedTotal)
        assertEquals(100, result.total)
    }

    @Test
    fun `duplicate cards are rejected`() {
        assertThrows<IllegalArgumentException> {
            RoemCalculator.calculate(listOf(Card(ACE, CLUBS), Card(ACE, CLUBS)), trump = HEARTS)
        }
    }

    @Test
    fun `achievable totals are exactly the roem some six card hand can hold`() {
        // Suits are interchangeable, so fixing hearts as trumps covers every trump suit.
        val deck = Deck.cards
        val held = sortedSetOf<Int>()
        val hand = arrayOfNulls<Card>(6)
        fun choose(from: Int, depth: Int) {
            if (depth == 6) {
                held += RoemCalculator.calculate(hand.map { it!! }, trump = HEARTS).total
                return
            }
            for (i in from..deck.size - (6 - depth)) {
                hand[depth] = deck[i]
                choose(i + 1, depth + 1)
            }
        }
        choose(0, 0)
        assertEquals(setOf(0, 20, 40, 50, 60, 70, 100), held)
        assertEquals(held - 0, RoemCalculator.achievableTotals)
    }
}
