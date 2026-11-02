package round

import auction.Action
import auction.Action.Companion.BONAK
import auction.Action.Companion.PASS
import auction.Action.Companion.PRAAT
import auction.Action.Companion.ROND
import auction.Action.Companion.bonakWithRoem
import auction.Action.Companion.points
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import play.UnconfirmedRule

/**
 * Seating is clockwise A, B, C, D with D dealing, so A is dealt first and bids first.
 * With the unshuffled deck: A = C7..CQ, B = CK, CA, D7..D10, C = DJ..DA, H7, H8, D = H9..HA,
 * stok face up = S7, face down = S8, unused = S9..SA.
 */
class RoundTest {

    private val players = listOf("A", "B", "C", "D")

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)
    private fun fourOf(rank: Rank) = Suit.entries.map { card(rank, it) }
    private val S7 = card(SEVEN, SPADES)
    private val S8 = card(EIGHT, SPADES)

    /** Deals [top] first (A's six, B's six, ... then face-up and face-down stok), then the rest in deck order. */
    private fun stacked(vararg top: Card) = Shuffler { top.toList() + Deck.cards.filter { it !in top } }

    private fun deal(shuffler: Shuffler = Shuffler { it }, players: List<String> = this.players, dealer: String = players.last()) =
        Round.deal(players, dealer, shuffler)

    private fun Round.bids(vararg actions: Action): Round =
        actions.fold(this) { round, action -> round.bid(round.auction.currentPlayer!!, action) }

    /** Deals [aHand] to A, then [others] to B, C and D (filled up from the deck), then [faceUp] and [faceDown] as stok. */
    private fun dealWithA(aHand: List<Card>, faceUp: Card = S7, faceDown: Card = S8, others: List<Card> = emptyList()): Round {
        val rest = Deck.cards.filter { it !in aHand && it !in others && it != faceUp && it != faceDown }
        val othersFilled = others + rest.take(18 - others.size)
        return deal(stacked(*(aHand + othersFilled + listOf(faceUp, faceDown)).toTypedArray()))
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
        @CsvSource("3, 12", "4, 6", "5, 0")
        fun `deals six cards each, a two card stok and sets the rest aside`(count: Int, unused: Int) {
            val names = (1..count).map { "P$it" }
            val round = deal(Shuffler.seeded(count.toLong()), names)
            names.forEach { assertEquals(6, round.hand(it).size) }
            assertEquals(listOf(round.originalFaceUpCard), round.cardsAt(StokFaceUp))
            assertEquals(listOf(round.originalFaceDownCard), round.cardsAt(StokFaceDown))
            assertEquals(unused, round.cardsAt(Unused).size)
            assertEveryCardOnce(round)
        }

        @Test
        fun `rejects fewer than three and more than five players before dealing`() {
            var shuffled = false
            val shuffler = Shuffler { shuffled = true; it }
            listOf(listOf("A"), listOf("A", "B"), listOf("A", "B", "C", "D", "E", "F")).forEach {
                assertRejected("3 to 5 players") { deal(shuffler, it) }
            }
            assertFalse(shuffled)
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
    inner class Exchange {
        private val original = deal()
        private val aOriginalHand = original.hand("A")
        private val exchanging = original.bids(points(50), PASS, PASS, PASS)

        @Test
        fun `the declarer must take both stok cards and holds eight`() {
            assertEquals(RoundPhase.STOK_EXCHANGE, exchanging.phase)
            assertEquals("A", exchanging.currentExchanger)
            assertEquals(8, exchanging.hand("A").size)
            assertEquals(listOf(S7, S8), exchanging.received["A"])
            assertTrue(exchanging.cardsAt(StokFaceUp).isEmpty() && exchanging.cardsAt(StokFaceDown).isEmpty())
            listOf("B", "C", "D").forEach { assertEquals(6, exchanging.hand(it).size) }
            assertEveryCardOnce(exchanging)
        }

        @Test
        fun `discarding the cards just picked up returns to the original six and sets them aside`() {
            val discarded = exchanging.discard("A", listOf(S7, S8))
            assertEquals(aOriginalHand, discarded.hand("A"))
            assertEquals(listOf(S7, S8), discarded.cardsAt(Discarded))
            players.forEach { assertEquals(6, discarded.hand(it).size) }
            assertEveryCardOnce(discarded)
        }

        @Test
        fun `invalid discards are rejected and leave state unchanged`() {
            val before = exchanging.locations
            assertRejected("exactly 2") { exchanging.discard("A", listOf(S7)) }
            assertRejected("exactly 2") { exchanging.discard("A", listOf(S7, S8, card(SEVEN, CLUBS))) }
            assertRejected("different cards") { exchanging.discard("A", listOf(S7, S7)) }
            assertRejected("your own hand") { exchanging.discard("A", listOf(S7, card(KING, CLUBS))) }
            assertRejected("your own hand") { exchanging.discard("A", listOf(S7, card(ACE, SPADES))) }
            assertRejected("A's turn to exchange") { exchanging.discard("B", listOf(card(KING, CLUBS), card(ACE, CLUBS))) }
            assertRejected("Bidding is not in progress") { exchanging.bid("A", PASS) }
            assertEquals(before, exchanging.locations)
            assertEquals(8, exchanging.hand("A").size)
            assertEquals(RoundPhase.STOK_EXCHANGE, exchanging.phase)
        }

        @Test
        fun `discarding is rejected in the wrong phase and cannot be repeated`() {
            assertRejected("only allowed during the stok exchange") {
                original.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            }
            val discarded = exchanging.discard("A", listOf(S7, S8))
            assertRejected("only allowed during the stok exchange") {
                discarded.discard("A", listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            }
        }

        @Test
        fun `the finalized bidding contract is preserved`() {
            val ready = exchanging.discard("A", listOf(S7, S8)).selectTrump("A", HEARTS)
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
            assertEquals("B", exchanging.currentExchanger)
            assertEquals(listOf(S7, S8), exchanging.received["B"])
            assertEquals(8, exchanging.hand("B").size)
            assertRejected("B's turn to exchange") { exchanging.discard("D", listOf(card(NINE, HEARTS), card(TEN, HEARTS))) }
        }

        @Test
        fun `each participant receives exactly the previous participant's discards`() {
            val bDiscards = listOf(card(KING, CLUBS), S7)
            val afterB = exchanging.discard("B", bDiscards)
            assertEquals(bDiscards, afterB.received["D"])
            assertTrue(afterB.hand("D").containsAll(bDiscards))
            assertTrue(afterB.cardsAt(Discarded).isEmpty())

            val dDiscards = listOf(card(KING, CLUBS), card(ACE, HEARTS))
            val afterD = afterB.discard("D", dDiscards)
            assertEquals(dDiscards, afterD.received["A"])

            val aDiscards = listOf(card(ACE, HEARTS), card(SEVEN, CLUBS))
            val ready = afterD.discard("A", aDiscards)
            assertEquals(aDiscards.toSet(), ready.cardsAt(Discarded).toSet())
            players.forEach { assertEquals(6, ready.hand(it).size) }
            assertEveryCardOnce(ready)
        }

        @Test
        fun `plays without trumps and needs neither trump selection nor roem`() {
            val afterTwo = exchanging.discard("B", listOf(S7, S8)).discard("D", listOf(S7, S8))
            assertEquals(RoundPhase.STOK_EXCHANGE, afterTwo.phase)
            assertRejected("without trumps") { afterTwo.selectTrump("A", HEARTS) }

            val ready = afterTwo.discard("A", listOf(S7, S8))
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertTrue(ready.playsWithoutTrumps)
            assertNull(ready.trump)
            assertNull(ready.trumpChooser)
            assertNull(ready.finalRoem)
            assertRejected("without trumps") { ready.selectTrump("B", HEARTS) }
        }
    }

    @Nested
    inner class TrumpSelection {
        private val bidding = deal()
        private val exchanging = bidding.bids(points(50), PASS, PASS, PASS)
        private val choosing = exchanging.discard("A", listOf(S7, S8))

        @Test
        fun `is rejected during bidding and before the exchange finishes`() {
            assertRejected("after the stok exchange") { bidding.selectTrump("A", HEARTS) }
            assertRejected("after the stok exchange") { exchanging.selectTrump("A", HEARTS) }
        }

        @Test
        fun `is rejected for anyone but the declarer and leaves state unchanged`() {
            assertRejected("Only A may choose trumps") { choosing.selectTrump("B", HEARTS) }
            assertEquals(RoundPhase.TRUMP_SELECTION, choosing.phase)
            assertNull(choosing.trump)
        }

        @Test
        fun `is required for points contracts`() {
            assertEquals(RoundPhase.TRUMP_SELECTION, choosing.phase)
            assertEquals("A", choosing.trumpChooser)
            val ready = choosing.selectTrump("A", DIAMONDS)
            assertEquals(DIAMONDS, ready.trump)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertRejected("after the stok exchange") { ready.selectTrump("A", HEARTS) }
        }

        @Test
        fun `is required for plain bonak, which needs no roem`() {
            val round = deal().bids(BONAK, PASS, PASS, PASS).discard("A", listOf(S7, S8))
            assertEquals(RoundPhase.TRUMP_SELECTION, round.phase)
            val ready = round.selectTrump("A", CLUBS)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertNull(ready.finalRoem)
            assertNull(ready.bonakCommitmentSatisfied())
        }

        @Test
        fun `is required for bonak with roem`() {
            val round = deal().bids(BONAK, bonakWithRoem(20), PASS, PASS, PASS).discard("B", listOf(S7, S8))
            assertEquals(RoundPhase.TRUMP_SELECTION, round.phase)
            assertEquals("B", round.trumpChooser)
        }

        @Test
        fun `is required for a single praat player, who needs no roem`() {
            val round = deal().bids(PRAAT, PASS, PASS, PASS).discard("A", listOf(S7, S8))
            assertEquals(RoundPhase.TRUMP_SELECTION, round.phase)
            assertFalse(round.playsWithoutTrumps)
            val ready = round.selectTrump("A", SPADES)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertNull(ready.finalRoem)
        }

        @Test
        fun `final stuk uses the chosen trump suit`() {
            val hand = listOf(card(JACK, HEARTS), card(QUEEN, HEARTS), card(KING, HEARTS),
                card(SEVEN, CLUBS), card(NINE, DIAMONDS), card(SEVEN, DIAMONDS))
            val choosingPoints = dealWithA(hand).bids(points(100), PASS, PASS, PASS).discard("A", listOf(S7, S8))
            assertEquals(40, choosingPoints.selectTrump("A", HEARTS).finalRoem!!.roem.total)
            assertEquals(20, choosingPoints.selectTrump("A", CLUBS).finalRoem!!.roem.total)
        }
    }

    @Nested
    inner class PointsContracts {
        private val jackQueenKingHearts = listOf(card(JACK, HEARTS), card(QUEEN, HEARTS), card(KING, HEARTS),
            card(SEVEN, CLUBS), card(NINE, DIAMONDS), card(SEVEN, DIAMONDS))

        private fun readyWith(
            hand: List<Card>,
            bid: Int,
            trump: Suit,
            faceUp: Card = S7,
            faceDown: Card = S8,
            discard: List<Card> = listOf(S7, S8),
            others: List<Card> = emptyList(),
        ) = dealWithA(hand, faceUp, faceDown, others).bids(points(bid), PASS, PASS, PASS)
            .discard("A", discard).selectTrump("A", trump)

        @Test
        fun `actual roem reduces the required trick points and the bid is unchanged`() {
            val ready = readyWith(jackQueenKingHearts, 100, HEARTS)
            assertEquals(PointsRequirements(100, 40, 60, 1), ready.pointsRequirements())
            assertEquals(PointsContract("A", 100), ready.auction.contract)
        }

        @Test
        fun `seventy roem leaves thirty trick points`() {
            val hand = listOf(card(JACK, HEARTS), card(QUEEN, HEARTS), card(KING, HEARTS),
                card(SEVEN, CLUBS), card(EIGHT, CLUBS), card(SEVEN, DIAMONDS))
            val ready = readyWith(hand, 100, HEARTS, faceUp = card(ACE, HEARTS), faceDown = S7,
                discard = listOf(card(EIGHT, CLUBS), S7))
            assertEquals(PointsRequirements(100, 70, 30, 1), ready.pointsRequirements())
        }

        @Test
        fun `roem equal to or above the bid still requires one trick`() {
            val fourAces = fourOf(ACE) + listOf(card(SEVEN, CLUBS), card(NINE, DIAMONDS))
            assertEquals(PointsRequirements(100, 100, 0, 1), readyWith(fourAces, 100, CLUBS).pointsRequirements())
            assertEquals(PointsRequirements(75, 100, 0, 1), readyWith(fourAces, 75, CLUBS).pointsRequirements())
        }

        @Test
        fun `roem is capped at 100 and comes only from retained cards`() {
            val hand = listOf(card(NINE, HEARTS), card(JACK, HEARTS), card(QUEEN, HEARTS),
                card(SEVEN, CLUBS), card(EIGHT, CLUBS), card(SEVEN, DIAMONDS))
            val faceUp = card(KING, HEARTS)
            val faceDown = card(TEN, HEARTS)
            val kept = readyWith(hand, 100, HEARTS, faceUp, faceDown, discard = listOf(card(SEVEN, CLUBS), card(EIGHT, CLUBS)))
            assertEquals(120, kept.finalRoem!!.roem.uncappedTotal)
            assertEquals(100, kept.finalRoem!!.roem.total)

            val dropped = readyWith(hand, 100, HEARTS, faceUp, faceDown, discard = listOf(faceUp, faceDown))
            assertEquals(0, dropped.finalRoem!!.roem.total)
            assertEquals(PointsRequirements(100, 0, 100, 1), dropped.pointsRequirements())
        }

        @Test
        fun `an opponent's roem never affects the declarer`() {
            // B holds four Aces, which must not count for anything.
            val ready = readyWith(jackQueenKingHearts, 100, HEARTS, others = fourOf(ACE))
            assertTrue(ready.hand("B").containsAll(fourOf(ACE)))
            assertEquals("A", ready.finalRoem!!.player)
            assertEquals(PointsRequirements(100, 40, 60, 1), ready.pointsRequirements())
        }

        @Test
        fun `with competing roem alternatives the highest total counts`() {
            // Four Jacks (100) or 10-J-Q of hearts (20): the Jack of hearts cannot count for both.
            val fourJacksAndHearts = fourOf(JACK) + listOf(card(TEN, HEARTS), card(QUEEN, HEARTS))
            val round = readyWith(fourJacksAndHearts, 100, CLUBS)
            assertEquals(RoundPhase.READY_TO_PLAY, round.phase)
            assertEquals(listOf(20, 100), round.finalRoem!!.roem.possibleTotals)
            assertEquals(PointsRequirements(100, 100, 0, 1), round.pointsRequirements())
        }
    }

    @Nested
    inner class BonakCommitments {
        private val aHand = listOf(card(NINE, HEARTS), card(JACK, HEARTS), card(QUEEN, HEARTS),
            card(SEVEN, CLUBS), card(EIGHT, CLUBS), card(SEVEN, DIAMONDS))

        /** B holds C9..CA and takes the stok (HK, H10) after A Bonak, B Bonak with [roem], everyone else passes. */
        private fun bExchanging(roem: Int) = dealWithA(aHand, card(KING, HEARTS), card(TEN, HEARTS))
            .bids(BONAK, bonakWithRoem(roem), PASS, PASS, PASS)

        @Test
        fun `roem below the commitment fails the contract immediately`() {
            // Keeps Q, K, A of clubs: 20 roem with spades as trumps.
            val failed = bExchanging(50).discard("B", listOf(card(TEN, CLUBS), card(JACK, CLUBS))).selectTrump("B", SPADES)
            assertEquals(RoundPhase.CONTRACT_FAILED, failed.phase)
            assertEquals(FailureReason.INSUFFICIENT_BONAK_ROEM, failed.failure!!.reason)
            assertEquals(50, failed.failure!!.declaredRoem)
            assertEquals(20, failed.finalRoem!!.roem.total)
            assertEquals(false, failed.bonakCommitmentSatisfied())
            assertEquals(BonakContract("B", 50), failed.auction.contract)
        }

        @Test
        fun `a failed contract never becomes ready to play and accepts no further actions`() {
            val failed = bExchanging(50).discard("B", listOf(card(TEN, CLUBS), card(JACK, CLUBS))).selectTrump("B", SPADES)
            assertRejected("after the stok exchange") { failed.selectTrump("B", CLUBS) }
            assertRejected("only allowed during the stok exchange") { failed.discard("B", failed.hand("B").take(2)) }
            assertRejected("Bidding is not in progress") { failed.bid("C", PASS) }
            assertEquals(RoundPhase.CONTRACT_FAILED, failed.phase)
        }

        @Test
        fun `equal roem satisfies the commitment`() {
            // Keeps 9, 10, J, Q of clubs: 50 roem with spades as trumps.
            val ready = bExchanging(50).discard("B", listOf(card(KING, CLUBS), card(ACE, CLUBS))).selectTrump("B", SPADES)
            assertEquals(50, ready.finalRoem!!.roem.total)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertEquals(true, ready.bonakCommitmentSatisfied())
            assertNull(ready.failure)
        }

        @Test
        fun `higher roem satisfies the commitment without changing the bid`() {
            // Keeps all six clubs: 100 roem.
            val ready = bExchanging(20).discard("B", listOf(card(KING, HEARTS), card(TEN, HEARTS))).selectTrump("B", SPADES)
            assertEquals(100, ready.finalRoem!!.roem.total)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertEquals(BonakContract("B", 20), ready.auction.contract)
        }

        @Test
        fun `with competing alternatives the highest total is checked against the commitment`() {
            // Four Kings + J, Q of hearts with hearts as trumps: four Kings and stuk (100) or J-Q-K with stuk (40).
            val hand = fourOf(KING) + listOf(card(JACK, HEARTS), card(QUEEN, HEARTS))
            // A Bonak, B Bonak 20, C and D pass, A Bonak with [roem], B passes: A declares.
            fun aChoosing(roem: Int) = dealWithA(hand)
                .bids(BONAK, bonakWithRoem(20), PASS, PASS, bonakWithRoem(roem), PASS)
                .discard("A", listOf(S7, S8))

            // Only the lower alternative (40) falls short of 50; the highest (100) counts.
            val ready = aChoosing(50).selectTrump("A", HEARTS)
            assertEquals(listOf(40, 100), ready.finalRoem!!.roem.possibleTotals)
            assertEquals(100, ready.finalRoem!!.roem.total)
            assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
            assertEquals(true, ready.bonakCommitmentSatisfied())
            assertEquals(BonakContract("A", 50), ready.auction.contract)
        }
    }

    @Nested
    inner class Redealing {
        private val recorded = mutableListOf<List<Card>>()
        private val recording = Shuffler.seeded(3).let { seeded -> Shuffler { deck -> recorded += deck; seeded.shuffle(deck) } }

        @Test
        fun `all pass moves the deal to the next player clockwise, wrapping around`() {
            val redealt = deal(recording).bids(PASS, PASS, PASS, PASS)
            assertEquals(2, redealt.dealNumber)
            assertEquals("A", redealt.dealer)
            assertEquals("B", redealt.auction.currentPlayer)
            assertEquals(RoundPhase.BIDDING, redealt.phase)

            val fromB = deal(dealer = "B").bids(PASS, PASS, PASS, PASS)
            assertEquals("C", fromB.dealer)
            assertEquals("D", fromB.auction.currentPlayer)
        }

        @Test
        fun `all rond keeps the same dealer`() {
            val redealt = deal(recording).bids(ROND, ROND, ROND, ROND)
            assertEquals(2, redealt.dealNumber)
            assertEquals("D", redealt.dealer)
            assertEquals("A", redealt.auction.currentPlayer)
        }

        @Test
        fun `rond followed by all pass is an all pass redeal`() {
            val redealt = deal().bids(ROND, PASS, PASS, PASS, PASS)
            assertEquals(2, redealt.dealNumber)
            assertEquals("A", redealt.dealer)
        }

        @Test
        fun `a pass makes rond illegal`() {
            assertRejected("Rond is only allowed") { deal().bids(ROND, PASS).bid("C", ROND) }
        }

        @Test
        fun `a redeal clears the old cards and auction and reshuffles the complete deck`() {
            val first = deal(recording)
            val redealt = first.bids(ROND, PASS, PASS, PASS, PASS)
            assertEquals(listOf(Deck.cards, Deck.cards), recorded)
            assertNotEquals(first.locations, redealt.locations)
            assertTrue(redealt.auction.history.isEmpty())
            assertTrue(redealt.exchangeOrder.isEmpty() && redealt.received.isEmpty() && redealt.discards.isEmpty())
            assertTrue(redealt.cardsAt(Discarded).isEmpty())
            assertEquals(1, redealt.cardsAt(StokFaceUp).size)
            assertEquals(1, redealt.cardsAt(StokFaceDown).size)
            players.forEach { assertEquals(6, redealt.hand(it).size) }
            assertEveryCardOnce(redealt)
        }

        @Test
        fun `every transition, including a redeal, gets a new version`() {
            val first = deal()
            val afterBid = first.bid("A", PASS)
            val redealt = afterBid.bids(PASS, PASS, PASS)
            assertTrue(afterBid.version > first.version)
            assertTrue(redealt.version > afterBid.version)
        }
    }

    @Test
    fun `with the confirmed rules a ready hand is blocked from play`() {
        val ready = deal().bids(points(50), PASS, PASS, PASS).discard("A", listOf(S7, S8)).selectTrump("A", HEARTS)
        assertEquals(RoundPhase.READY_TO_PLAY, ready.phase)
        assertEquals(listOf(UnconfirmedRule.FIRST_LEADER, UnconfirmedRule.MOVE_LEGALITY), ready.playBlockedBy)
        assertRejected("blocked until these rules are confirmed") { ready.playCard("B", card(KING, CLUBS)) }
        assertRejected("Bidding is not in progress") { ready.bid("B", PASS) }
        assertRejected("only allowed during the stok exchange") { ready.discard("A", ready.hand("A").take(2)) }
        assertRejected("after the stok exchange") { ready.selectTrump("A", CLUBS) }
        assertEquals(
            listOf("BIDDING", "STOK_EXCHANGE", "TRUMP_SELECTION", "READY_TO_PLAY", "PLAYING", "HAND_COMPLETE", "CONTRACT_FAILED"),
            RoundPhase.entries.map { it.name },
        )
    }
}
