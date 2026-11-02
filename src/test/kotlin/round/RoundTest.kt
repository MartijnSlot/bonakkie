package round

import auction.Action.Companion.BONAK
import auction.Action.Companion.PASS
import auction.Action.Companion.PRAAT
import auction.Action.Companion.ROND
import auction.Action.Companion.bonakWithRoem
import auction.Action.Companion.points
import auction.Action
import auction.AuctionStatus
import auction.BonakContract
import auction.PointsContract
import auction.PointsRequirements
import auction.PraatContract
import auction.RuleViolation
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
import model.Shuffler
import model.Suit
import model.Suit.CLUBS
import model.Suit.DIAMONDS
import model.Suit.HEARTS
import model.Suit.SPADES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Seating is clockwise A, B, C, D with D dealing, so A is dealt first and bids first.
 * With the unshuffled deck: A = C7..CQ, B = CK, CA, D7..D10, C = DJ..DA, H7, H8, D = H9..HA,
 * stok face up = S7, face down = S8, unused = S9..SA.
 */
class RoundTest {

    private val players = listOf("A", "B", "C", "D")

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)
    private val S7 = card(SEVEN, SPADES)
    private val S8 = card(EIGHT, SPADES)

    /** Deals [top] first (A's six, B's six, ... then face-up and face-down stok), then the rest in deck order. */
    private fun stacked(vararg top: Card) = Shuffler { top.toList() + Deck.cards.filter { it !in top } }

    private fun deal(shuffler: Shuffler = Shuffler { it }, players: List<String> = this.players) =
        Round.deal(players, players.last(), shuffler)

    private fun Round.bids(vararg actions: Action): Round =
        actions.fold(this) { round, action -> round.bid(round.auction.currentPlayer!!, action) }

    /** A deal of [aHand] to A and [faceUp]/[faceDown] in the stok; other hands are filled from the rest of the deck. */
    private fun dealWithA(aHand: List<Card>, faceUp: Card, faceDown: Card): Round {
        val rest = Deck.cards.filter { it !in aHand && it != faceUp && it != faceDown }
        return deal(stacked(*(aHand + rest.take(18) + listOf(faceUp, faceDown)).toTypedArray()))
    }

    private fun assertRejected(message: String, block: () -> Unit) {
        val error = assertThrows<RuleViolation> { block() }
        assertTrue(error.message!!.contains(message), "Unexpected message: ${error.message}")
    }

    private fun assertEveryCardOnce(round: Round) {
        assertEquals(Deck.cards.toSet(), round.locations.keys)
        val sizes = round.players.sumOf { round.hand(it).size } +
            listOf(StokFaceUp, StokFaceDown, Unused, Discarded).sumOf { round.cardsAt(it).size }
        assertEquals(32, sizes)
    }

    @Nested
    inner class Dealing {
        @ParameterizedTest
        @ValueSource(ints = [2, 3, 4, 5])
        fun `deals six cards each, a two card stok and sets the rest aside`(count: Int) {
            val names = (1..count).map { "P$it" }
            val round = deal(Shuffler.seeded(count.toLong()), names)
            names.forEach { assertEquals(6, round.hand(it).size) }
            assertEquals(listOf(round.originalFaceUpCard), round.cardsAt(StokFaceUp))
            assertEquals(listOf(round.originalFaceDownCard), round.cardsAt(StokFaceDown))
            assertEquals(32 - 6 * count - 2, round.cardsAt(Unused).size)
            assertEveryCardOnce(round)
        }

        @Test
        fun `rejects player counts the deck cannot accommodate`() {
            assertRejected("at most 5 players") { deal(players = listOf("A", "B", "C", "D", "E", "F")) }
            assertRejected("at least two players") { deal(players = listOf("A")) }
        }

        @Test
        fun `a seeded shuffle deals reproducibly`() {
            val first = deal(Shuffler.seeded(7))
            assertEquals(first.locations, deal(Shuffler.seeded(7)).locations)
            assertNotEquals(first.locations, deal(Shuffler.seeded(8)).locations)
        }

        @Test
        fun `bidding starts once dealing is complete`() {
            val round = deal()
            assertEquals(RoundPhase.BIDDING, round.phase)
            assertEquals("A", round.auction.currentPlayer)
            assertEquals(listOf(S7), round.cardsAt(StokFaceUp))
            assertEquals(listOf(S8), round.cardsAt(StokFaceDown))
        }
    }

    @Nested
    inner class SingleDeclarerExchange {
        private val original = deal()
        private val aOriginalHand = original.hand("A")
        private val exchanging = original.bids(points(50), PASS, PASS, PASS)

        @Test
        fun `the declarer must take both stok cards and holds eight`() {
            assertEquals(RoundPhase.STOK_EXCHANGE, exchanging.phase)
            assertEquals("A", exchanging.currentExchanger)
            assertEquals(8, exchanging.hand("A").size)
            assertTrue(exchanging.hand("A").containsAll(listOf(S7, S8)))
            assertEquals(listOf(S7, S8), exchanging.received["A"])
            assertTrue(exchanging.cardsAt(StokFaceUp).isEmpty() && exchanging.cardsAt(StokFaceDown).isEmpty())
            listOf("B", "C", "D").forEach { assertEquals(6, exchanging.hand(it).size) }
            assertEveryCardOnce(exchanging)
        }

        @Test
        fun `discarding the cards just picked up returns to the original six`() {
            val ready = exchanging.discard("A", listOf(S7, S8))
            assertEquals(aOriginalHand, ready.hand("A"))
            assertEquals(listOf(S7, S8), ready.cardsAt(Discarded))
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertNull(ready.currentExchanger)
            players.forEach { assertEquals(6, ready.hand(it).size) }
            assertEveryCardOnce(ready)
        }

        @Test
        fun `discarding own cards keeps the stok cards`() {
            val ready = exchanging.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            assertTrue(ready.hand("A").containsAll(listOf(S7, S8)))
            assertEquals(6, ready.hand("A").size)
            assertEquals(listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)), ready.cardsAt(Discarded))
        }

        @Test
        fun `invalid discards are rejected and leave state unchanged`() {
            val before = exchanging.locations
            assertRejected("exactly 2") { exchanging.discard("A", listOf(S7)) }
            assertRejected("exactly 2") { exchanging.discard("A", listOf(S7, S8, card(SEVEN, CLUBS))) }
            assertRejected("exactly 2") { exchanging.discard("A", emptyList()) }
            assertRejected("different cards") { exchanging.discard("A", listOf(S7, S7)) }
            assertRejected("your own hand") { exchanging.discard("A", listOf(S7, card(KING, CLUBS))) }
            assertRejected("your own hand") { exchanging.discard("A", listOf(S7, card(ACE, SPADES))) }
            assertRejected("A's turn to exchange") { exchanging.discard("B", listOf(card(KING, CLUBS), card(ACE, CLUBS))) }
            assertRejected("Bidding is not in progress") { exchanging.bid("A", PASS) }
            assertEquals(before, exchanging.locations)
            assertEquals(8, exchanging.hand("A").size)
            assertEquals(RoundPhase.STOK_EXCHANGE, exchanging.phase)
            assertEquals(0, exchanging.exchangesCompleted)
        }

        @Test
        fun `discarding is rejected while bidding`() {
            assertRejected("only allowed during the stok exchange") {
                original.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            }
        }

        @Test
        fun `a repeated discard is rejected once ready to play`() {
            val ready = exchanging.discard("A", listOf(S7, S8))
            assertRejected("only allowed during the stok exchange") {
                ready.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            }
        }

        @Test
        fun `the finalized bidding contract is preserved`() {
            val ready = exchanging.discard("A", listOf(S7, S8))
            assertEquals(PointsContract("A", 50), ready.auction.contract)
            assertEquals(AuctionStatus.CONTRACT_FINALIZED, ready.auction.status)
        }
    }

    @Nested
    inner class MultiplePraat {
        // Praat bid order B, D, A differs from seating order A, B, D.
        private val exchanging = deal().bids(ROND, PRAAT, PASS, PRAAT, PRAAT)

        @Test
        fun `exchange follows the order of first bidding praat`() {
            assertEquals(PraatContract(listOf("B", "D", "A")), exchanging.auction.contract)
            assertEquals(listOf("B", "D", "A"), exchanging.exchangeOrder)
            assertEquals("B", exchanging.currentExchanger)
            assertEquals(8, exchanging.hand("B").size)
            assertEquals(listOf(S7, S8), exchanging.received["B"])
            assertEquals(6, exchanging.hand("D").size)
            assertEquals(6, exchanging.hand("A").size)
        }

        @Test
        fun `each participant must finish before the next one starts`() {
            assertRejected("B's turn to exchange") {
                exchanging.discard("D", listOf(card(NINE, HEARTS), card(TEN, HEARTS)))
            }
        }

        @Test
        fun `each participant receives exactly the previous participant's discards`() {
            val bDiscards = listOf(card(KING, CLUBS), S7)
            val afterB = exchanging.discard("B", bDiscards)
            assertEquals("D", afterB.currentExchanger)
            assertEquals(RoundPhase.STOK_EXCHANGE, afterB.phase)
            assertEquals(6, afterB.hand("B").size)
            assertEquals(8, afterB.hand("D").size)
            assertTrue(afterB.hand("D").containsAll(bDiscards))
            assertEquals(bDiscards, afterB.received["D"])
            assertTrue(afterB.cardsAt(Discarded).isEmpty())
            assertNotNull(afterB.finalRoem["B"])
            assertEveryCardOnce(afterB)

            val dDiscards = listOf(card(KING, CLUBS), card(ACE, HEARTS))
            val afterD = afterB.discard("D", dDiscards)
            assertEquals("A", afterD.currentExchanger)
            assertEquals(dDiscards, afterD.received["A"])
            assertEquals(8, afterD.hand("A").size)

            val aDiscards = listOf(card(ACE, HEARTS), card(SEVEN, CLUBS))
            val ready = afterD.discard("A", aDiscards)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertEquals(aDiscards.toSet(), ready.cardsAt(Discarded).toSet())
            assertEquals(6, ready.cardsAt(Unused).size)
            players.forEach { assertEquals(6, ready.hand(it).size) }
            assertEquals(setOf("B", "D", "A"), ready.finalRoem.keys)
            assertEveryCardOnce(ready)
        }

        @Test
        fun `the phase stays in exchange until every participant has finished`() {
            val afterTwo = exchanging.discard("B", listOf(S7, S8)).discard("D", listOf(S7, S8))
            assertEquals(RoundPhase.STOK_EXCHANGE, afterTwo.phase)
            assertEquals("A", afterTwo.currentExchanger)
        }

        @Test
        fun `a participant's retained hand is fixed after their own discard`() {
            val afterB = exchanging.discard("B", listOf(S7, S8))
            val bFinal = afterB.finalRoem.getValue("B")
            val ready = afterB.discard("D", listOf(S7, S8)).discard("A", listOf(S7, S8))
            assertEquals(bFinal, ready.finalRoem.getValue("B"))
            assertEquals(bFinal.hand.toSet(), ready.hand("B").toSet())
        }
    }

    @Nested
    inner class FinalRoemDetermination {
        private val aHand = listOf(card(NINE, HEARTS), card(JACK, HEARTS), card(QUEEN, HEARTS),
            card(SEVEN, CLUBS), card(EIGHT, CLUBS), card(SEVEN, DIAMONDS))
        private val faceUp = card(KING, HEARTS)
        private val faceDown = card(TEN, HEARTS)
        private val exchanging = dealWithA(aHand, faceUp, faceDown).bids(points(100), PASS, PASS, PASS)

        @Test
        fun `face up and face down stok cards count when retained, capped at 100`() {
            val ready = exchanging.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            val roem = ready.finalRoem.getValue("A")
            assertTrue(roem.hand.containsAll(listOf(faceUp, faceDown)))
            assertEquals(120, roem.byTrump.getValue(HEARTS).uncappedTotal)
            assertEquals(100, roem.byTrump.getValue(HEARTS).total)
            assertEquals(100, roem.byTrump.getValue(SPADES).total)
        }

        @Test
        fun `discarded cards do not contribute`() {
            val ready = exchanging.discard("A", listOf(faceUp, faceDown))
            val roem = ready.finalRoem.getValue("A")
            assertEquals(aHand.toSet(), roem.hand.toSet())
            Suit.entries.forEach { assertEquals(0, roem.byTrump.getValue(it).total) }
        }

        @Test
        fun `stuk depends on the trump suit`() {
            // Keep the face-up King, drop the face-down Ten: J, Q, K of hearts is a sequence with stuk only in hearts.
            val ready = exchanging.discard("A", listOf(faceDown, card(NINE, HEARTS)))
            val roem = ready.finalRoem.getValue("A")
            assertEquals(40, roem.byTrump.getValue(HEARTS).total)
            assertEquals(20, roem.byTrump.getValue(CLUBS).total)
        }

        @Test
        fun `points requirements below the bid`() {
            val ready = exchanging.discard("A", listOf(faceDown, card(NINE, HEARTS)))
            val requirements = ready.pointsRequirements()!!
            assertEquals(PointsRequirements(100, 40, 60, 1), requirements.getValue(HEARTS))
            assertEquals(PointsRequirements(100, 20, 80, 1), requirements.getValue(DIAMONDS))
        }

        @Test
        fun `points requirements equal to and above the bid still need one trick`() {
            val fourJacks = Suit.entries.map { card(JACK, it) } + listOf(card(SEVEN, CLUBS), card(NINE, DIAMONDS))
            fun readyWithBid(amount: Int) = dealWithA(fourJacks, card(ACE, SPADES), card(EIGHT, HEARTS))
                .bids(points(amount), PASS, PASS, PASS)
                .discard("A", listOf(card(ACE, SPADES), card(EIGHT, HEARTS)))

            val equal = readyWithBid(100)
            assertEquals(100, equal.finalRoem.getValue("A").byTrump.getValue(CLUBS).total)
            assertEquals(PointsRequirements(100, 100, 0, 1), equal.pointsRequirements()!!.getValue(CLUBS))
            assertEquals(PointsRequirements(75, 100, 0, 1), readyWithBid(75).pointsRequirements()!!.getValue(CLUBS))
        }

        @Test
        fun `four jacks score 100, never 200`() {
            val fourJacks = Suit.entries.map { card(JACK, it) } + listOf(card(SEVEN, CLUBS), card(NINE, DIAMONDS))
            val ready = dealWithA(fourJacks, card(ACE, SPADES), card(EIGHT, HEARTS))
                .bids(points(50), PASS, PASS, PASS)
                .discard("A", listOf(card(SEVEN, CLUBS), card(NINE, DIAMONDS)))
            Suit.entries.forEach { assertEquals(100, ready.finalRoem.getValue("A").byTrump.getValue(it).total) }
        }

        @Test
        fun `bonak keeps both the committed roem and the actual final roem`() {
            val ready = dealWithA(aHand, faceUp, faceDown)
                .bids(BONAK, bonakWithRoem(50), PASS, PASS, PASS)
                .discard("B", listOf(card(TEN, CLUBS), card(JACK, CLUBS)))
            assertEquals(BonakContract("B", 50), ready.auction.contract)
            assertNotNull(ready.finalRoem["B"])
            assertNull(ready.pointsRequirements())
        }
    }

    @Nested
    inner class BiddingOutcomes {
        @Test
        fun `all rond starts a fresh deal instead of an exchange`() {
            val first = deal(Shuffler.seeded(3))
            val redealt = first.bids(ROND, ROND, ROND, ROND)
            assertEquals(2, redealt.dealNumber)
            assertEquals(RoundPhase.BIDDING, redealt.phase)
            assertTrue(redealt.auction.history.isEmpty())
            assertTrue(redealt.exchangeOrder.isEmpty())
            assertNotNull(redealt.redealReason)
            assertNotEquals(first.locations, redealt.locations)
            players.forEach { assertEquals(6, redealt.hand(it).size) }
            assertEquals(1, redealt.cardsAt(StokFaceDown).size)
            assertEquals("A", redealt.auction.currentPlayer)
        }

        @Test
        fun `all pass ends in the unresolved state without an exchange`() {
            val round = deal().bids(PASS, PASS, PASS, PASS)
            assertEquals(RoundPhase.BIDDING_UNRESOLVED, round.phase)
            assertTrue(round.exchangeOrder.isEmpty())
            assertRejected("only allowed during the stok exchange") { round.discard("A", listOf(S7, S8)) }
            assertRejected("Bidding is not in progress") { round.bid("A", PASS) }
        }

        @Test
        fun `ready to play is final - no further bidding, discarding or play`() {
            val ready = deal().bids(points(50), PASS, PASS, PASS).discard("A", listOf(S7, S8))
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertRejected("Bidding is not in progress") { ready.bid("B", PASS) }
            assertRejected("only allowed during the stok exchange") {
                ready.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            }
            assertEquals(
                listOf(RoundPhase.BIDDING, RoundPhase.STOK_EXCHANGE, RoundPhase.READY_TO_PLAY, RoundPhase.BIDDING_UNRESOLVED),
                RoundPhase.entries,
            )
        }
    }
}
