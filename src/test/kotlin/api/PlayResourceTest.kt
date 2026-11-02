package api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.dropwizard.jackson.Jackson
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport
import io.dropwizard.testing.junit5.ResourceExtension
import jakarta.ws.rs.client.Entity
import model.Card
import model.Deck
import model.Rank
import model.Shuffler
import model.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import play.FirstLeaderPolicy
import play.PlayLegalityPolicy
import play.PlayRules
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Play over HTTP. [testResources] uses TEST policies (Bob leads, any card from hand) that are not game rules;
 * [confirmedResources] uses the rules as confirmed today, which block play.
 * Unshuffled deck, seating Ann, Bob, Cas with Cas dealing: Ann = C7..CQ, Bob = CK, CA, D7..D10,
 * Cas = DJ..DA, H7, H8, stok H9 (up), H10 (down).
 */
@ExtendWith(DropwizardExtensionsSupport::class)
class PlayResourceTest {

    private val mapper: ObjectMapper = Jackson.newObjectMapper().registerModule(kotlinModule())
    private val testRules = PlayRules(FirstLeaderPolicy { "Bob" }, PlayLegalityPolicy { hand, _, _ -> hand.toSet() })

    private fun resources(rules: PlayRules) = ResourceExtension.builder()
        .setMapper(mapper)
        .addResource(RoundResource(RoundStore(Shuffler { it }, rules)))
        .addProvider(RuleViolationMapper())
        .addProvider(StaleRequestMapper())
        .build()

    @JvmField
    val testResources: ResourceExtension = resources(testRules)

    @JvmField
    val confirmedResources: ResourceExtension = resources(PlayRules.CONFIRMED)

    private val faceUp = Deck.cards[18]
    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)

    private fun ResourceExtension.post(path: String, json: String): Pair<Int, JsonNode> =
        target(path).request().post(Entity.json(json)).use { it.status to mapper.readTree(it.readEntity(String::class.java)) }

    private fun ResourceExtension.get(path: String): JsonNode = mapper.readTree(target(path).request().get(String::class.java))
    private fun ResourceExtension.version() = get("/round")["version"].asLong()
    private fun ResourceExtension.bid(player: String, type: String, amount: Int? = null) =
        post("/round/bids", """{"player":"$player","type":"$type"${amount?.let { ""","amount":$it""" } ?: ""},"version":${version()}}""")
    private fun ResourceExtension.play(player: String, card: Card, version: Long = version()) =
        post("/round/plays", """{"player":"$player","card":${mapper.writeValueAsString(card)},"version":$version}""")

    /** Ann bids 60, the others pass, Ann discards the stok and chooses hearts. */
    private fun ResourceExtension.readyHand() {
        post("/round", """{"players":["Ann","Bob","Cas"],"dealer":"Cas"}""")
        bid("Ann", "BID_POINTS", 60); bid("Bob", "PASS"); bid("Cas", "PASS")
        post("/round/discards", """{"player":"Ann","cards":${mapper.writeValueAsString(listOf(faceUp, Deck.cards[19]))},"version":${version()}}""")
        post("/round/trump", """{"player":"Ann","suit":"HEARTS","version":${version()}}""")
    }

    private fun cardsIn(node: JsonNode): Set<Card> = when {
        node.isObject && node.has("rank") && node.has("suit") ->
            setOf(Card(Rank.valueOf(node["rank"].asText()), Suit.valueOf(node["suit"].asText())))
        node.isContainerNode -> node.flatMap { cardsIn(it) }.toSet()
        else -> emptySet()
    }

    @Test
    fun `with the confirmed rules play is blocked and says why`() {
        confirmedResources.readyHand()
        val round = confirmedResources.get("/round")
        assertEquals("READY_TO_PLAY", round["phase"].asText())
        assertEquals(listOf("FIRST_LEADER", "MOVE_LEGALITY"), round["playBlockedBy"].map { it["rule"].asText() })
        assertTrue(round["play"].isNull)
        assertTrue(confirmedResources.get("/round/players/Bob")["availableActions"]["playableCards"].isEmpty)

        val (status, error) = confirmedResources.play("Bob", card(Rank.KING, Suit.CLUBS))
        assertEquals(400, status)
        assertTrue(error["message"].asText().contains("blocked until these rules are confirmed"))
    }

    @Test
    fun `trump status distinguishes not chosen from chosen`() {
        confirmedResources.post("/round", """{"players":["Ann","Bob","Cas"],"dealer":"Cas"}""")
        assertEquals("NOT_CHOSEN", confirmedResources.get("/round")["trumpStatus"].asText())
        confirmedResources.readyHand()
        assertEquals("CHOSEN", confirmedResources.get("/round")["trumpStatus"].asText())
    }

    @Test
    fun `playing shows the trick publicly but keeps remaining hands private`() {
        testResources.readyHand()
        val bobCard = card(Rank.KING, Suit.CLUBS)
        val (status, bobView) = testResources.play("Bob", bobCard)
        assertEquals(200, status)
        assertEquals(5, bobView["hand"].size())

        val round = testResources.get("/round")
        assertEquals("PLAYING", round["phase"].asText())
        assertEquals("Cas", round["play"]["currentPlayer"].asText())
        assertEquals("Bob", round["play"]["leader"].asText())
        assertEquals(1, round["play"]["trickNumber"].asInt())
        assertEquals(setOf(faceUp, bobCard), cardsIn(round))
        assertEquals(5, round["handSizes"]["Bob"].asInt())

        val cas = testResources.get("/round/players/Cas")
        assertEquals(6, cas["availableActions"]["playableCards"].size())
        assertEquals(cardsIn(cas["hand"]) + faceUp + bobCard, cardsIn(cas))
        assertTrue(testResources.get("/round/players/Ann")["availableActions"]["playableCards"].isEmpty)
    }

    @Test
    fun `a duplicate submission is rejected and plays only once`() {
        testResources.readyHand()
        val version = testResources.version()
        assertEquals(200, testResources.play("Bob", card(Rank.KING, Suit.CLUBS), version).first)
        assertEquals(409, testResources.play("Bob", card(Rank.KING, Suit.CLUBS), version).first)
        assertEquals(1, testResources.get("/round")["play"]["currentTrick"].size())
    }

    @Test
    fun `concurrent plays for the same state are applied exactly once`() {
        testResources.readyHand()
        val version = testResources.version()
        val cards = listOf(card(Rank.KING, Suit.CLUBS), card(Rank.ACE, Suit.CLUBS), card(Rank.SEVEN, Suit.DIAMONDS))
        val pool = Executors.newFixedThreadPool(cards.size)
        val statuses = pool.invokeAll(cards.map { c -> Callable { testResources.play("Bob", c, version).first } }).map { it.get() }
        pool.shutdown()
        assertEquals(listOf(200, 409, 409), statuses.sorted())
        assertEquals(1, testResources.get("/round")["play"]["currentTrick"].size())
        assertEquals(5, testResources.get("/round")["handSizes"]["Bob"].asInt())
    }

    @Test
    fun `out of turn and not owned cards are rejected`() {
        testResources.readyHand()
        assertEquals(400, testResources.play("Ann", card(Rank.NINE, Suit.CLUBS)).first)
        val (status, error) = testResources.play("Bob", card(Rank.SEVEN, Suit.CLUBS))
        assertEquals(400, status)
        assertTrue(error["message"].asText().contains("your own hand"))
    }

    @Test
    fun `only the last completed trick can be looked back at`() {
        testResources.readyHand()
        // Bob leads; turns go Bob, Cas, Ann. Play two tricks with whatever is first in each hand.
        fun playFirstCard() {
            val player = testResources.get("/round")["play"]["currentPlayer"].asText()
            val first = testResources.get("/round/players/$player")["availableActions"]["playableCards"].first()
            testResources.play(player, mapper.treeToValue(first, Card::class.java))
        }
        repeat(3) { playFirstCard() }
        val afterOne = testResources.get("/round")["play"]
        val trickOne = cardsIn(afterOne["completedTricks"][0]["plays"])
        assertEquals(3, trickOne.size)

        repeat(3) { playFirstCard() }
        val round = testResources.get("/round")
        val tricks = round["play"]["completedTricks"]
        assertEquals(2, tricks.size())
        assertTrue(tricks[0]["plays"].isNull)
        assertEquals(afterOne["completedTricks"][0]["winner"], tricks[0]["winner"])
        val trickTwo = cardsIn(tricks[1]["plays"])
        assertEquals(3, trickTwo.size)

        // Trick one's cards appear nowhere any more, in the public view or in any player's view.
        assertEquals(setOf(faceUp) + trickTwo, cardsIn(round))

        // The declarer keeps their roem total and requirements, but not the cards behind them.
        val ann = testResources.get("/round/players/Ann")["finalRoem"]
        assertTrue(ann["hand"].isNull)
        assertEquals(60, ann["pointsRequirements"]["bidAmount"].asInt())
        ann["roem"]["alternatives"].flatMap { it["combinations"] }.forEach { assertTrue(it["cards"].isNull) }
        listOf("Ann", "Bob", "Cas").forEach { player ->
            val view = testResources.get("/round/players/$player")
            assertTrue(cardsIn(view).intersect(trickOne).isEmpty(), "$player still sees trick one")
        }
    }
}
