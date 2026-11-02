package round

import auction.Action
import auction.Action.Companion.BONAK
import auction.Action.Companion.PASS
import auction.Action.Companion.PRAAT
import auction.Action.Companion.bonakWithRoem
import auction.Action.Companion.points
import auction.RuleViolation
import model.Card
import model.Deck
import model.Rank.ACE
import model.Rank.KING
import model.Rank.NINE
import model.Rank.SEVEN
import model.Rank.TEN
import model.Rank.EIGHT
import model.Rank.JACK
import model.Shuffler
import model.Suit
import model.Suit.CLUBS
import model.Suit.HEARTS
import model.Suit.SPADES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import play.FirstLeaderPolicy
import play.Play
import play.PlayLegalityPolicy
import play.PlayRules
import play.UnconfirmedRule
import play.trickWinner

/**
 * The play mechanics with test policies for the unconfirmed rules. The fixtures below are NOT game rules:
 * they only supply a first leader and an accept-anything legality so the confirmed mechanics can be tested.
 *
 * Seating A, B, C, D with D dealing; unshuffled deck: A = C7..CQ, B = CK, CA, D7..D10, C = DJ..DA, H7, H8,
 * D = H9..HA, stok S7 (up), S8 (down).
 */
class PlayTest {

    private val players = listOf("A", "B", "C", "D")
    private fun card(rank: model.Rank, suit: Suit) = Card(rank, suit)
    private val S7 = card(SEVEN, SPADES)
    private val S8 = card(EIGHT, SPADES)

    private fun leader(name: String) = FirstLeaderPolicy { name }
    private val anyCardFromHand = PlayLegalityPolicy { hand, _, _ -> hand.toSet() }
    private fun testRules(first: String = "C") = PlayRules(leader(first), anyCardFromHand)

    private fun Round.bids(vararg actions: Action): Round =
        actions.fold(this) { round, action -> round.bid(round.auction.currentPlayer!!, action) }

    /** A points 50, the others pass, A discards the stok and picks hearts: play starts with [rules]. */
    private fun playing(rules: PlayRules = testRules(), trump: Suit = HEARTS) =
        Round.deal(players, "D", { it }, rules = rules).bids(points(50), PASS, PASS, PASS)
            .discard("A", listOf(S7, S8)).selectTrump("A", trump)

    /** Plays the first legal card (in suit and rank order) for whoever's turn it is. */
    private fun Round.next(): Round {
        val player = currentPlayer!!
        return playCard(player, legalCards(player).sortedWith(compareBy({ it.suit }, { it.rank })).first())
    }

    private fun Round.nextPlays(count: Int): Round = (1..count).fold(this) { round, _ -> round.next() }

    private fun assertRejected(message: String, block: () -> Unit) {
        val error = assertThrows<RuleViolation> { block() }
        assertTrue(error.message!!.contains(message), "Unexpected message: ${error.message}")
    }

    private fun assertEveryCardOnce(round: Round) {
        assertEquals(Deck.cards.toSet(), round.locations.keys)
    }

    @Nested
    inner class Starting {
        @Test
        fun `play starts with the supplied leader once the hand is ready`() {
            val round = playing()
            assertEquals(RoundPhase.PLAYING, round.phase)
            assertEquals(1, round.play!!.trickNumber)
            assertEquals("C", round.play!!.leader)
            assertEquals("C", round.currentPlayer)
            players.forEach { assertEquals(6, round.hand(it).size) }
            assertEquals(Trumps(HEARTS), round.trumpMode)
        }

        @Test
        fun `the first leader policy sees the contract`() {
            val declarerLeads = FirstLeaderPolicy { context -> context.contract.declarers.first() }
            assertEquals("A", playing(PlayRules(declarerLeads, anyCardFromHand)).play!!.leader)
        }

        @Test
        fun `with the confirmed rules play stays blocked, naming both unconfirmed rules`() {
            val round = playing(PlayRules.CONFIRMED)
            assertEquals(RoundPhase.READY_TO_PLAY, round.phase)
            assertNull(round.play)
            assertEquals(listOf(UnconfirmedRule.FIRST_LEADER, UnconfirmedRule.MOVE_LEGALITY), round.playBlockedBy)
            assertRejected("blocked until these rules are confirmed") { round.playCard("A", card(SEVEN, CLUBS)) }
            assertTrue(round.legalCards("A").isEmpty())
        }

        @Test
        fun `a known leader alone does not unblock play, nor does legality alone`() {
            assertEquals(listOf(UnconfirmedRule.MOVE_LEGALITY), playing(PlayRules(leader("A"), null)).playBlockedBy)
            assertEquals(listOf(UnconfirmedRule.FIRST_LEADER), playing(PlayRules(null, anyCardFromHand)).playBlockedBy)
        }

        @Test
        fun `pending trump selection differs from explicitly no trumps`() {
            val choosing = Round.deal(players, "D", { it }, rules = testRules())
                .bids(points(50), PASS, PASS, PASS).discard("A", listOf(S7, S8))
            assertEquals(RoundPhase.TRUMP_SELECTION, choosing.phase)
            assertNull(choosing.trumpMode)

            // A, B and D play Praat together: no trumps, and play starts right after the exchange.
            val noTrumps = Round.deal(players, "D", { it }, rules = testRules())
                .bids(PRAAT, PRAAT, PASS, PRAAT)
                .discard("A", listOf(S7, S8)).discard("B", listOf(S7, S8)).discard("D", listOf(S7, S8))
            assertEquals(NoTrumps, noTrumps.trumpMode)
            assertEquals(RoundPhase.PLAYING, noTrumps.phase)
        }

        @Test
        fun `a failed bonak commitment never starts play`() {
            // B holds CK, CA, D7..D10, takes S7, S8 and discards them: D7..D10 is 50 roem, short of the promised 60.
            val failed = Round.deal(players, "D", { it }, rules = testRules())
                .bids(BONAK, bonakWithRoem(60), PASS, PASS, PASS)
                .discard("B", listOf(S7, S8)).selectTrump("B", SPADES)
            assertEquals(RoundPhase.CONTRACT_FAILED, failed.phase)
            assertNull(failed.play)
            assertRejected("failed before play") { failed.playCard("B", card(KING, CLUBS)) }
        }
    }

    @Nested
    inner class Tricks {
        @Test
        fun `turns go clockwise from the leader and every player, including passers, plays once`() {
            val round = playing()
            val order = generateSequence(round) { it.next() }.take(4).map { it.currentPlayer }.toList()
            assertEquals(listOf("C", "D", "A", "B"), order)
            val trick = round.nextPlays(4).play!!.completedTricks.single()
            assertEquals(listOf("C", "D", "A", "B"), trick.plays.map { it.player })
        }

        @Test
        fun `played cards leave the hand and an incomplete trick stays unresolved`() {
            val round = playing().nextPlays(3)
            assertEquals(listOf(5, 5, 5), listOf("C", "D", "A").map { round.hand(it).size })
            assertEquals(6, round.hand("B").size)
            assertEquals(3, round.play!!.currentTrick.size)
            assertTrue(round.play!!.completedTricks.isEmpty())
            round.play!!.currentTrick.forEach { assertEquals(Played(1), round.locations[it.card]) }
            assertEquals("B", round.currentPlayer)
            assertEveryCardOnce(round)
        }

        @Test
        fun `a completed trick is recorded once with its winner, who leads the next trick`() {
            // C leads H7, D plays the Nel (H9), A plays C7, B plays CK: the Nel wins.
            val round = playing()
                .playCard("C", card(SEVEN, HEARTS)).playCard("D", card(NINE, HEARTS))
                .playCard("A", card(SEVEN, CLUBS)).playCard("B", card(KING, CLUBS))
            val trick = round.play!!.completedTricks.single()
            assertEquals(1, trick.number)
            assertEquals("C", trick.leader)
            assertEquals("D", trick.winner)
            assertEquals(2, round.play!!.trickNumber)
            assertEquals("D", round.play!!.leader)
            assertEquals("D", round.currentPlayer)
            assertTrue(round.play!!.currentTrick.isEmpty())
            players.forEach { assertEquals(5, round.hand(it).size) }
            assertEquals(mapOf("A" to 0, "B" to 0, "C" to 0, "D" to 1), round.tricksWon)
        }

        @Test
        fun `each completed trick takes one card from every hand`() {
            var round = playing()
            (1..6).forEach { trick ->
                round = round.nextPlays(4)
                players.forEach { assertEquals(6 - trick, round.hand(it).size) }
                assertEquals(trick, round.play!!.completedTricks.size)
            }
        }

        @Test
        fun `the legality policy decides which cards are accepted and offered`() {
            // A test policy allowing only the lowest card of the hand.
            val lowestOnly = PlayLegalityPolicy { hand, _, _ -> setOf(hand.minWith(compareBy({ it.suit }, { it.rank }))) }
            val round = playing(PlayRules(leader("A"), lowestOnly))
            assertEquals(setOf(card(SEVEN, CLUBS)), round.legalCards("A"))
            assertRejected("may not be played now") { round.playCard("A", card(EIGHT, CLUBS)) }
            assertEquals(card(SEVEN, CLUBS), round.playCard("A", card(SEVEN, CLUBS)).play!!.currentTrick.single().card)
        }

        @Test
        fun `praat with several players plays without trumps`() {
            // A, B, D play Praat without trumps: the Jack of hearts is just an off-suit card, so the club Ace wins.
            val round = Round.deal(players, "D", { it }, rules = testRules("A"))
                .bids(PRAAT, PRAAT, PASS, PRAAT)
                .discard("A", listOf(S7, S8)).discard("B", listOf(S7, S8)).discard("D", listOf(S7, S8))
                .playCard("A", card(SEVEN, CLUBS)).playCard("B", card(ACE, CLUBS))
                .playCard("C", card(ACE, Suit.DIAMONDS)).playCard("D", card(JACK, HEARTS))
            assertEquals("B", round.play!!.completedTricks.single().winner)
        }

        @ParameterizedTest
        @ValueSource(ints = [3, 4, 5])
        fun `a full hand works with three, four and five players`(count: Int) {
            val names = (1..count).map { "P$it" }
            var round = Round.deal(names, names.last(), Shuffler.seeded(count.toLong()), rules = testRules("P2"))
                .let { r -> r.bids(points(50), *Array(count - 1) { PASS }) }
            round = round.discard("P1", round.received.getValue("P1")).selectTrump("P1", HEARTS)
            assertEquals(RoundPhase.PLAYING, round.phase)

            round = round.nextPlays(6 * count)
            assertEquals(RoundPhase.HAND_COMPLETE, round.phase)
            val tricks = round.play!!.completedTricks
            assertEquals(6, tricks.size)
            tricks.forEach { trick ->
                assertEquals(count, trick.plays.size)
                assertEquals(names.toSet(), trick.plays.map { it.player }.toSet())
                assertEquals(trickWinner(trick.plays, HEARTS).player, trick.winner)
            }
            tricks.zipWithNext().forEach { (previous, next) -> assertEquals(previous.winner, next.leader) }
            names.forEach { assertTrue(round.hand(it).isEmpty()) }
            assertEquals(6 * count, round.locations.values.count { it is Played })
            assertEquals(6, round.tricksWon.values.sum())
        }
    }

    @Nested
    inner class Completion {
        @Test
        fun `the hand stays active until the sixth trick completes`() {
            val almost = playing().nextPlays(23)
            assertEquals(RoundPhase.PLAYING, almost.phase)
            assertEquals(6, almost.play!!.trickNumber)
            assertEquals(5, almost.play!!.completedTricks.size)

            val complete = almost.next()
            assertEquals(RoundPhase.HAND_COMPLETE, complete.phase)
            assertEquals(6, complete.play!!.completedTricks.size)
            players.forEach { assertTrue(complete.hand(it).isEmpty()) }
            assertNull(complete.currentPlayer)
        }

        @Test
        fun `there is no seventh trick and no automatic redeal or dealer change`() {
            val before = playing()
            val complete = before.nextPlays(24)
            assertRejected("hand is complete") { complete.playCard("A", card(SEVEN, CLUBS)) }
            assertTrue(complete.legalCards("A").isEmpty())
            assertEquals(before.dealNumber, complete.dealNumber)
            assertEquals(before.handId, complete.handId)
            assertEquals("D", complete.dealer)
        }

        @Test
        fun `play does not stop early when the contract outcome is certain`() {
            // A plays Bonak (all six tricks) and loses the first trick: the outcome is certain, play continues.
            val round = Round.deal(players, "D", { it }, rules = testRules("A"))
                .bids(BONAK, PASS, PASS, PASS).discard("A", listOf(S7, S8)).selectTrump("A", HEARTS)
                .playCard("A", card(SEVEN, CLUBS)).playCard("B", card(ACE, CLUBS))
                .playCard("C", card(JACK, Suit.DIAMONDS)).playCard("D", card(TEN, Suit.HEARTS))
            assertEquals("D", round.play!!.completedTricks.single().winner)
            assertEquals(RoundPhase.PLAYING, round.phase)
            assertEquals(RoundPhase.HAND_COMPLETE, round.nextPlays(20).phase)
        }
    }

    @Nested
    inner class Validation {
        private val round = playing()

        @Test
        fun `cards cannot be played outside the play phase`() {
            val bidding = Round.deal(players, "D", { it }, rules = testRules())
            assertRejected("once the hand is being played") { bidding.playCard("A", card(SEVEN, CLUBS)) }
        }

        @Test
        fun `wrong player and out of turn plays are rejected`() {
            assertRejected("It is C's turn") { round.playCard("A", card(SEVEN, CLUBS)) }
            assertRejected("It is C's turn") { round.playCard("Nobody", card(SEVEN, CLUBS)) }
        }

        @Test
        fun `cards not held are rejected, including unused, discarded and already played cards`() {
            assertRejected("your own hand") { round.playCard("C", card(SEVEN, CLUBS)) }
            assertRejected("your own hand") { round.playCard("C", card(ACE, SPADES)) }
            assertRejected("your own hand") { round.playCard("C", S7) }
            val afterLead = round.playCard("C", card(SEVEN, HEARTS))
            assertRejected("It is D's turn") { afterLead.playCard("C", card(SEVEN, HEARTS)) }
            val nextTrick = afterLead.nextPlays(3)
            val winner = nextTrick.currentPlayer!!
            assertRejected("your own hand") { nextTrick.playCard(winner, card(SEVEN, HEARTS)) }
        }

        @Test
        fun `a repeated submission cannot add a second card for the same player`() {
            val once = round.playCard("C", card(SEVEN, HEARTS))
            assertRejected("It is D's turn") { once.playCard("C", card(EIGHT, HEARTS)) }
            assertEquals(1, once.play!!.currentTrick.size)
        }

        @Test
        fun `invalid plays leave the state unchanged`() {
            val locations = round.locations
            val play = round.play
            assertThrows<RuleViolation> { round.playCard("A", card(SEVEN, CLUBS)) }
            assertThrows<RuleViolation> { round.playCard("C", card(SEVEN, CLUBS)) }
            assertSame(locations, round.locations)
            assertSame(play, round.play)
            assertEquals(6, round.hand("C").size)
        }

        @Test
        fun `played cards are attributed in order`() {
            val trick = round.playCard("C", card(SEVEN, HEARTS)).playCard("D", card(ACE, HEARTS)).play!!.currentTrick
            assertEquals(listOf(Play("C", card(SEVEN, HEARTS)), Play("D", card(ACE, HEARTS))), trick)
        }
    }
}
