package play

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
import roem.RoemCalculator
import roem.RoemCombinationType

class TricksTest {

    private fun play(player: String, rank: Rank, suit: Suit) = Play(player, Card(rank, suit))

    /** Plays [cards] as players P1, P2, ... and returns the winning card. */
    private fun winner(trump: Suit?, vararg cards: Card): Card =
        trickWinner(cards.mapIndexed { i, card -> Play("P${i + 1}", card) }, trump).card

    @Test
    fun `trump order is 7, 8, 10, Queen, King, Ace, 9, Jack`() {
        assertEquals(listOf(SEVEN, EIGHT, TEN, QUEEN, KING, ACE, NINE, JACK), PlayRanking.TRUMP_ORDER)
    }

    @Test
    fun `every adjacent pair in the trump order is won by the higher card, whichever is led`() {
        PlayRanking.TRUMP_ORDER.zipWithNext().forEach { (lower, higher) ->
            val low = Card(lower, HEARTS)
            val high = Card(higher, HEARTS)
            assertEquals(high, winner(HEARTS, low, high), "$higher should beat $lower")
            assertEquals(high, winner(HEARTS, high, low), "$higher should beat $lower")
        }
    }

    @Test
    fun `every adjacent pair in the non trump order is won by the higher card, whichever is led`() {
        PlayRanking.NON_TRUMP_ORDER.zipWithNext().forEach { (lower, higher) ->
            val low = Card(lower, CLUBS)
            val high = Card(higher, CLUBS)
            assertEquals(high, winner(HEARTS, low, high), "$higher should beat $lower")
            assertEquals(high, winner(HEARTS, high, low), "$higher should beat $lower")
        }
    }

    @Test
    fun `named trump comparisons`() {
        assertEquals(Card(JACK, HEARTS), winner(HEARTS, Card(NINE, HEARTS), Card(JACK, HEARTS)))
        assertEquals(Card(NINE, HEARTS), winner(HEARTS, Card(ACE, HEARTS), Card(NINE, HEARTS)))
        assertEquals(Card(QUEEN, HEARTS), winner(HEARTS, Card(TEN, HEARTS), Card(QUEEN, HEARTS)))
    }

    @Test
    fun `non trump 10 loses to non trump Jack`() {
        assertEquals(Card(JACK, CLUBS), winner(HEARTS, Card(TEN, CLUBS), Card(JACK, CLUBS)))
    }

    @Test
    fun `the lowest trump beats the highest non trump`() {
        assertEquals(Card(SEVEN, HEARTS), winner(HEARTS, Card(ACE, CLUBS), Card(SEVEN, HEARTS)))
    }

    @Test
    fun `without a trump the highest card of the suit led wins and off suit Aces do not`() {
        val winning = winner(HEARTS, Card(EIGHT, CLUBS), Card(ACE, SPADES), Card(TEN, CLUBS), Card(ACE, DIAMONDS))
        assertEquals(Card(TEN, CLUBS), winning)
        assertEquals(Card(SEVEN, CLUBS), winner(HEARTS, Card(SEVEN, CLUBS), Card(ACE, SPADES), Card(ACE, DIAMONDS)))
    }

    @Test
    fun `the highest trump wins regardless of the suit led`() {
        val winning = winner(HEARTS, Card(ACE, CLUBS), Card(EIGHT, HEARTS), Card(KING, CLUBS), Card(NINE, HEARTS))
        assertEquals(Card(NINE, HEARTS), winning)
    }

    @Test
    fun `games without trumps use the non trump order for every suit`() {
        assertEquals(Card(ACE, HEARTS), winner(null, Card(NINE, HEARTS), Card(ACE, HEARTS)))
        assertEquals(Card(TEN, HEARTS), winner(null, Card(NINE, HEARTS), Card(TEN, HEARTS)))
        assertEquals(Card(KING, HEARTS), winner(null, Card(JACK, HEARTS), Card(KING, HEARTS)))
        // No suit is special without trumps: an off-suit card never wins.
        assertEquals(Card(SEVEN, CLUBS), winner(null, Card(SEVEN, CLUBS), Card(JACK, HEARTS), Card(ACE, SPADES)))
    }

    @ParameterizedTest
    @EnumSource(Suit::class)
    fun `every suit behaves the same as trumps`(trump: Suit) {
        val other = Suit.entries.first { it != trump }
        assertEquals(Card(JACK, trump), winner(trump, Card(NINE, trump), Card(JACK, trump)))
        assertEquals(Card(NINE, trump), winner(trump, Card(ACE, trump), Card(NINE, trump)))
        assertEquals(Card(SEVEN, trump), winner(trump, Card(ACE, other), Card(SEVEN, trump)))
        assertEquals(Card(ACE, other), winner(trump, Card(NINE, other), Card(ACE, other)))
    }

    @Test
    fun `the winner identifies the player`() {
        val plays = listOf(play("Ann", SEVEN, CLUBS), play("Bob", SEVEN, HEARTS), play("Cas", ACE, CLUBS))
        assertEquals("Bob", trickWinner(plays, HEARTS).player)
    }

    @Test
    fun `an empty trick has no winner`() {
        assertThrows<IllegalArgumentException> { trickWinner(emptyList(), HEARTS) }
    }

    @Test
    fun `roem still uses the sequence order 7 to Ace, not the trump play order`() {
        // 8, 9, 10 of trumps is a sequence for roem although 9 ranks above 10 in play.
        val result = RoemCalculator.calculate(listOf(Card(EIGHT, HEARTS), Card(NINE, HEARTS), Card(TEN, HEARTS)), HEARTS)
        assertEquals(listOf(RoemCombinationType.THREE_SEQUENCE), result.combinations.map { it.type })
        // 10, Queen, King of trumps are adjacent in play but not a roem sequence.
        assertEquals(20, RoemCalculator.calculate(listOf(Card(TEN, HEARTS), Card(QUEEN, HEARTS), Card(KING, HEARTS)), HEARTS).total)
        assertEquals(listOf(RoemCombinationType.STUK), RoemCalculator.calculate(
            listOf(Card(TEN, HEARTS), Card(QUEEN, HEARTS), Card(KING, HEARTS)), HEARTS,
        ).combinations.map { it.type })
    }
}
