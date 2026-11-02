package auction

import auction.Action.Companion.BONAK
import auction.Action.Companion.PASS
import auction.Action.Companion.PRAAT
import auction.Action.Companion.ROND
import auction.Action.Companion.bonakWithRoem
import auction.Action.Companion.points
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Seating is clockwise A, B, C, D with D dealing, so A opens. */
class AuctionTest {

    private fun start() = Auction.start(listOf("A", "B", "C", "D"), dealer = "D")

    /** Plays actions for whoever's turn it is. */
    private fun Auction.play(vararg actions: Action): Auction =
        actions.fold(this) { auction, action -> auction.apply(auction.currentPlayer!!, action) }

    private fun assertRejected(auction: Auction, player: String, action: Action, messagePart: String) {
        val error = assertThrows<RuleViolation> { auction.apply(player, action) }
        assertTrue(error.message!!.contains(messagePart), "Unexpected message: ${error.message}")
    }

    private fun Auction.isLegal(action: Action) = validate(currentPlayer!!, action) == null

    @Nested
    inner class Setup {
        @Test
        fun `bidding starts left of the dealer`() {
            assertEquals("A", start().currentPlayer)
            assertEquals("C", Auction.start(listOf("A", "B", "C", "D"), dealer = "B").currentPlayer)
            assertEquals("A", Auction.start(listOf("A", "B", "C"), dealer = "C").currentPlayer)
        }

        @Test
        fun `invalid setups are rejected`() {
            assertThrows<RuleViolation> { Auction.start(listOf("A"), "A") }
            assertThrows<RuleViolation> { Auction.start(listOf("A", "A"), "A") }
            assertThrows<RuleViolation> { Auction.start(listOf("A", " "), "A") }
            assertThrows<RuleViolation> { Auction.start(listOf("A", "B"), "C") }
        }
    }

    @Nested
    inner class TurnOrder {
        @Test
        fun `turns proceed clockwise`() {
            val auction = start()
            val order = generateSequence(auction) { it.play(ROND) }.take(4).map { it.currentPlayer }.toList()
            assertEquals(listOf("A", "B", "C", "D"), order)
        }

        @Test
        fun `out of turn actions are rejected`() {
            assertRejected(start(), "B", points(50), "It is A's turn")
        }

        @Test
        fun `turn order skips permanently passed players`() {
            val auction = start().play(points(50), PASS, points(60), PASS)
            assertEquals("A", auction.currentPlayer)
            assertEquals("C", auction.play(points(70)).currentPlayer)
        }
    }

    @Nested
    inner class PassAndRond {
        @Test
        fun `pass is permanent for the auction`() {
            val auction = start().play(PASS, points(50), PASS, PASS)
            assertEquals(AuctionStatus.CONTRACT_FINALIZED, auction.status)
            assertEquals(setOf("A", "C", "D"), auction.passed)
            assertEquals(listOf("A", "B", "C", "D"), auction.players)
        }

        @Test
        fun `a passed player never gets the turn again`() {
            val auction = start().play(PASS, points(50), points(55), points(60))
            assertEquals("B", auction.currentPlayer)
        }

        @Test
        fun `rond keeps the right to bid later`() {
            val auction = start().play(ROND, points(50), PASS, PASS)
            assertEquals(AuctionStatus.IN_PROGRESS, auction.status)
            assertEquals("A", auction.currentPlayer)
            assertTrue(auction.isLegal(points(55)))

            val finished = auction.play(points(55), PASS)
            assertEquals(PointsContract("A", 55), finished.contract)
        }

        @Test
        fun `rond players must get the chance to respond before the auction finishes`() {
            val auction = start().play(ROND, ROND, points(80), PASS)
            assertEquals(AuctionStatus.IN_PROGRESS, auction.status)
            assertEquals("A", auction.currentPlayer)
            assertEquals("B", auction.play(PASS).currentPlayer)
        }

        @Test
        fun `following players may say rond while every previous action was rond`() {
            assertTrue(start().play(ROND, ROND).isLegal(ROND))
        }

        @Test
        fun `rond becomes illegal after a bid`() {
            assertRejected(start().play(ROND, points(50)), "C", ROND, "Rond is only allowed")
        }

        @Test
        fun `rond becomes illegal after a pass`() {
            assertRejected(start().play(PASS), "B", ROND, "Rond is only allowed")
            assertRejected(start().play(ROND, PASS), "C", ROND, "Rond is only allowed")
        }

        @Test
        fun `all rond on the opening circuit requires a redeal`() {
            val auction = start().play(ROND, ROND, ROND, ROND)
            assertEquals(AuctionStatus.REDEAL_REQUIRED, auction.status)
            assertNull(auction.contract)
            assertNull(auction.currentPlayer)
            assertTrue(auction.legalActions().isEmpty())
        }

        @Test
        fun `all pass on the opening circuit ends the auction as all passed, not as all rond`() {
            val auction = start().play(PASS, PASS, PASS, PASS)
            assertEquals(AuctionStatus.ALL_PASSED, auction.status)
            assertNull(auction.contract)
        }

        @Test
        fun `rond then all pass without a bid is all passed`() {
            val auction = start().play(ROND, PASS, PASS, PASS, PASS)
            assertEquals(AuctionStatus.ALL_PASSED, auction.status)
        }

        @Test
        fun `the last eligible player may still bid after everyone else passed`() {
            val auction = start().play(PASS, PASS, PASS)
            assertEquals("D", auction.currentPlayer)
            assertEquals(PointsContract("D", 50), auction.play(points(50)).contract)
        }
    }

    @Nested
    inner class PointsBids {
        @Test
        fun `points bids must be between 50 and 125 in steps of five`() {
            val auction = start()
            listOf(45, 52, 130, 0).forEach { assertFalse(auction.isLegal(points(it)), "$it should be illegal") }
            listOf(50, 75, 125).forEach { assertTrue(auction.isLegal(points(it)), "$it should be legal") }
        }

        @Test
        fun `points bids must increase and may skip levels`() {
            val auction = start().play(points(60))
            assertFalse(auction.isLegal(points(60)))
            assertFalse(auction.isLegal(points(55)))
            assertTrue(auction.isLegal(points(65)))
            assertTrue(auction.isLegal(points(120)))
        }

        @Test
        fun `points contract finalizes when everyone else passed`() {
            val auction = start().play(points(50), points(90), PASS, PASS, PASS)
            assertEquals(AuctionStatus.CONTRACT_FINALIZED, auction.status)
            assertEquals(PointsContract("B", 90), auction.contract)
        }

        @Test
        fun `points requirements subtract roem but always need one trick`() {
            val contract = PointsContract("A", 100)
            assertEquals(PointsRequirements(100, 40, 60, 1), contract.requirements(40))
            assertEquals(PointsRequirements(100, 100, 0, 1), contract.requirements(100))
            assertEquals(PointsRequirements(75, 100, 0, 1), PointsContract("A", 75).requirements(100))
            assertThrows<RuleViolation> { contract.requirements(101) }
        }
    }

    @Nested
    inner class Praat {
        @Test
        fun `praat may be bid before any points bid`() {
            assertTrue(start().isLegal(PRAAT))
        }

        @Test
        fun `praat may be introduced over 75`() {
            assertTrue(start().play(points(75)).isLegal(PRAAT))
        }

        @Test
        fun `praat cannot be introduced over 80`() {
            assertRejected(start().play(points(80)), "B", PRAAT, "above 75")
        }

        @Test
        fun `players can join praat and the praat completes with all participants`() {
            val auction = start().play(points(60), PRAAT, PRAAT, PASS)
            assertEquals("A", auction.currentPlayer)
            val finished = auction.play(PRAAT)
            assertEquals(AuctionStatus.CONTRACT_FINALIZED, finished.status)
            assertEquals(PraatContract(listOf("B", "C", "A")), finished.contract)
        }

        @Test
        fun `rond players may join praat later`() {
            val auction = start().play(ROND, PRAAT, PASS, PASS, PRAAT)
            assertEquals(PraatContract(listOf("B", "A")), auction.contract)
        }

        @Test
        fun `passed players cannot join praat`() {
            val auction = start().play(PASS, PRAAT, PASS, PASS)
            assertEquals(PraatContract(listOf("B")), auction.contract)
            assertRejected(auction, "A", PRAAT, "auction has ended")
        }

        @Test
        fun `multiple praat participants do not stop a legal higher bid`() {
            val auction = start().play(PRAAT, PRAAT)
            assertTrue(auction.isLegal(points(100)))
            assertTrue(auction.isLegal(BONAK))
        }

        @Test
        fun `praat can be outbid with 100 but not 95`() {
            val auction = start().play(PRAAT, PRAAT)
            assertRejected(auction, "C", points(95), "at least 100")
            val outbid = auction.play(points(100))
            assertEquals(PointsBid("C", 100), outbid.standingBid)
        }

        @Test
        fun `former praat participants stay eligible after praat is outbid`() {
            val auction = start().play(PRAAT, PRAAT, points(100), PASS)
            assertEquals("A", auction.currentPlayer)
            assertFalse(auction.isLegal(PRAAT))
            assertTrue(auction.isLegal(points(105)))
            assertEquals("B", auction.play(PASS).currentPlayer)
        }
    }

    @Nested
    inner class Bonak {
        @Test
        fun `bonak beats 125`() {
            val auction = start().play(points(125), BONAK, PASS, PASS, PASS)
            assertEquals(BonakContract("B", null), auction.contract)
        }

        @Test
        fun `bonak beats praat`() {
            assertEquals(BonakBid("B", null), start().play(PRAAT, BONAK).standingBid)
        }

        @Test
        fun `points and praat cannot beat bonak`() {
            val auction = start().play(BONAK)
            assertFalse(auction.isLegal(points(125)))
            assertFalse(auction.isLegal(PRAAT))
        }

        @Test
        fun `plain bonak cannot beat bonak`() {
            assertRejected(start().play(BONAK), "B", BONAK, "Plain Bonak cannot beat")
        }

        @Test
        fun `bonak with roem requires an earlier bonak`() {
            assertRejected(start(), "A", bonakWithRoem(20), "after an earlier Bonak")
            assertRejected(start().play(points(125)), "B", bonakWithRoem(20), "after an earlier Bonak")
            assertTrue(start().play(BONAK).isLegal(bonakWithRoem(20)))
        }

        @Test
        fun `higher roem beats lower roem, equal and lower do not`() {
            val auction = start().play(BONAK, bonakWithRoem(40))
            assertFalse(auction.isLegal(bonakWithRoem(20)))
            assertFalse(auction.isLegal(bonakWithRoem(40)))
            assertTrue(auction.isLegal(bonakWithRoem(50)))
            assertFalse(auction.isLegal(BONAK))
        }

        @Test
        fun `bonak roem must be positive, achievable and at most 100`() {
            val auction = start().play(BONAK)
            listOf(0, -20, 30, 45, 80, 90, 110).forEach { assertFalse(auction.isLegal(bonakWithRoem(it)), "$it should be illegal") }
        }

        @Test
        fun `equal 100 roem never overbids, whatever combination it would come from`() {
            // Bids only carry an amount: a six-card sequence (100) cannot beat a five-card sequence (100).
            val auction = start().play(BONAK, bonakWithRoem(100))
            assertFalse(auction.isLegal(bonakWithRoem(100)))
            assertFalse(auction.isLegal(bonakWithRoem(110)))
            assertEquals(setOf(ActionType.PASS), auction.legalActions().map { it.type }.toSet())
        }

        @Test
        fun `plain bonak is beaten by bonak with roem`() {
            assertTrue(start().play(BONAK).isLegal(bonakWithRoem(20)))
        }

        @Test
        fun `highest bonak with roem becomes the contract`() {
            val auction = start().play(BONAK, bonakWithRoem(20), bonakWithRoem(50), PASS, PASS, PASS)
            assertEquals(BonakContract("C", 50), auction.contract)
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `invalid actions leave state unchanged`() {
            val auction = start().play(ROND, points(60))
            val historyBefore = auction.history
            assertThrows<RuleViolation> { auction.apply("C", points(55)) }
            assertThrows<RuleViolation> { auction.apply("C", ROND) }
            assertThrows<RuleViolation> { auction.apply("A", points(65)) }
            assertThrows<RuleViolation> { auction.apply("C", Action(ActionType.BID_POINTS)) }
            assertThrows<RuleViolation> { auction.apply("C", Action(ActionType.PASS, 5)) }
            assertSame(historyBefore, auction.history)
            assertEquals("C", auction.currentPlayer)
            assertEquals(PointsBid("B", 60), auction.standingBid)
            assertFalse(auction.rondAllowed)
        }

        @Test
        fun `actions after completion are rejected`() {
            val finished = start().play(points(50), PASS, PASS, PASS)
            assertEquals(AuctionStatus.CONTRACT_FINALIZED, finished.status)
            assertRejected(finished, "A", points(55), "auction has ended")
            assertRejected(start().play(ROND, ROND, ROND, ROND), "A", PASS, "auction has ended")
        }

        @Test
        fun `history records every accepted action in order`() {
            val auction = start().play(ROND, points(50), PRAAT)
            assertEquals(
                listOf(HistoryEntry("A", ROND), HistoryEntry("B", points(50)), HistoryEntry("C", PRAAT)),
                auction.history,
            )
        }

        @Test
        fun `legal actions match validation`() {
            val opening = start().legalActions().associateBy { it.type }
            assertEquals(
                setOf(ActionType.ROND, ActionType.PASS, ActionType.BID_POINTS, ActionType.BID_PRAAT, ActionType.BID_BONAK),
                opening.keys,
            )
            assertEquals((50..125 step 5).toList(), opening.getValue(ActionType.BID_POINTS).amounts)

            val overPraat = start().play(PRAAT).legalActions().associateBy { it.type }
            assertEquals(listOf(100, 105, 110, 115, 120, 125), overPraat.getValue(ActionType.BID_POINTS).amounts)

            val overBonak = start().play(BONAK, bonakWithRoem(60)).legalActions().associateBy { it.type }
            assertEquals(setOf(ActionType.PASS, ActionType.BID_BONAK_WITH_ROEM), overBonak.keys)
            assertEquals(listOf(70, 100), overBonak.getValue(ActionType.BID_BONAK_WITH_ROEM).amounts)
        }
    }
}
