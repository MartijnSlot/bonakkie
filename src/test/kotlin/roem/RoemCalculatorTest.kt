package roem

import model.Card
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
import roem.RoemCombinationType.FOUR_SEQUENCE
import roem.RoemCombinationType.FOUR_SEQUENCE_WITH_STUK
import roem.RoemCombinationType.STUK
import roem.RoemCombinationType.THREE_SEQUENCE
import roem.RoemCombinationType.THREE_SEQUENCE_WITH_STUK

class RoemCalculatorTest {

    private fun cards(suit: Suit, vararg ranks: Rank) = ranks.map { Card(it, suit) }
    private fun fourOf(rank: Rank) = Suit.entries.map { Card(rank, it) }
    private fun types(result: RoemResult) = result.combinations.map { it.type }

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
        assertEquals(1, result.combinations.size)
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
    fun `combinations sharing cards are added together`() {
        val result = RoemCalculator.calculate(cards(CLUBS, SEVEN, EIGHT, NINE) + cards(SPADES, SEVEN, EIGHT, NINE), HEARTS)
        assertEquals(40, result.total)
        val shared = RoemCalculator.calculate(cards(HEARTS, JACK, QUEEN) + fourOf(KING), trump = HEARTS)
        assertEquals(140, shared.uncappedTotal)
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
    fun `achievable totals are capped sums of combination values`() {
        assertEquals(setOf(20, 40, 50, 60, 70, 80, 90, 100), RoemCalculator.achievableTotals)
    }
}
