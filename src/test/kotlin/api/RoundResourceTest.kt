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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Uses the unshuffled deck. Seating Ann, Bob, Cas with Cas dealing:
 * Ann = C7..CQ, Bob = CK, CA, D7..D10, Cas = DJ..DA, H7, H8, stok up = H9, stok down = H10, unused = HJ..SA.
 */
@ExtendWith(DropwizardExtensionsSupport::class)
class RoundResourceTest {

    private val mapper: ObjectMapper = Jackson.newObjectMapper().registerModule(kotlinModule())

    @JvmField
    val resources: ResourceExtension = ResourceExtension.builder()
        .setMapper(mapper)
        .addResource(RoundResource(RoundStore(Shuffler { it })))
        .addResource(RoemResource())
        .addProvider(RuleViolationMapper())
        .addProvider(StaleRequestMapper())
        .build()

    private val ann = Deck.cards.subList(0, 6).toSet()
    private val bob = Deck.cards.subList(6, 12).toSet()
    private val faceUp = Deck.cards[18]
    private val faceDown = Deck.cards[19]

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)

    private fun post(path: String, json: String): Pair<Int, JsonNode> =
        resources.target(path).request().post(Entity.json(json)).use { it.status to mapper.readTree(it.readEntity(String::class.java)) }

    private fun get(path: String): Pair<Int, JsonNode> =
        resources.target(path).request().get().use { it.status to mapper.readTree(it.readEntity(String::class.java)) }

    private fun version() = get("/round").second["version"].asLong()

    private fun bid(player: String, type: String, amount: Int? = null, version: Long = version()) =
        post("/round/bids", """{"player":"$player","type":"$type"${amount?.let { ""","amount":$it""" } ?: ""},"version":$version}""")

    private fun discard(player: String, vararg cards: Card) =
        post("/round/discards", """{"player":"$player","cards":${mapper.writeValueAsString(cards)},"version":${version()}}""")

    private fun trump(player: String, suit: String) =
        post("/round/trump", """{"player":"$player","suit":"$suit","version":${version()}}""")

    /** Every card identity anywhere in a response. */
    private fun cardsIn(node: JsonNode): Set<Card> = when {
        node.isObject && node.has("rank") && node.has("suit") ->
            setOf(Card(Rank.valueOf(node["rank"].asText()), Suit.valueOf(node["suit"].asText())))
        node.isContainerNode -> node.flatMap { cardsIn(it) }.toSet()
        else -> emptySet()
    }

    private fun start() = post("/round", """{"players":["Ann","Bob","Cas"],"dealer":"Cas"}""")

    @Test
    fun `public state during bidding only reveals the face up stok card`() {
        val (status, round) = start()
        assertEquals(200, status)
        assertEquals("BIDDING", round["phase"].asText())
        assertEquals(setOf(faceUp), cardsIn(round))
        assertEquals(1, round["stokCardsFaceDown"].asInt())
        assertEquals(12, round["unusedCards"].asInt())
        assertEquals(6, round["handSizes"]["Bob"].asInt())
    }

    @Test
    fun `a player sees only their own hand and the face up card`() {
        start()
        val (_, view) = get("/round/players/Ann")
        assertEquals(ann, cardsIn(view["hand"]))
        assertEquals(ann + faceUp, cardsIn(view))
        assertEquals(404, get("/round/players/Eve").first)
    }

    @Test
    fun `the face down card is only revealed to the declarer who takes it`() {
        start()
        bid("Ann", "BID_POINTS", 60); bid("Bob", "PASS")
        val (_, round) = bid("Cas", "PASS")
        assertEquals("STOK_EXCHANGE", round["phase"].asText())
        assertEquals(setOf(faceUp), cardsIn(round))
        assertEquals(ann + faceUp + faceDown, cardsIn(get("/round/players/Ann").second))
        assertEquals(bob + faceUp, cardsIn(get("/round/players/Bob").second))
    }

    @Test
    fun `trump selection is offered only to the declarer, then final roem only to them`() {
        start()
        bid("Ann", "BID_POINTS", 60); bid("Bob", "PASS"); bid("Cas", "PASS")
        val (_, afterDiscard) = discard("Ann", card(Rank.SEVEN, Suit.CLUBS), card(Rank.EIGHT, Suit.CLUBS))
        assertEquals("TRUMP_SELECTION", afterDiscard["round"]["phase"].asText())
        assertEquals("Ann", afterDiscard["round"]["trumpChooser"].asText())
        assertEquals(Suit.entries.map { it.name }, afterDiscard["availableActions"]["trumpSuits"].map { it.asText() })
        assertTrue(get("/round/players/Bob").second["availableActions"]["trumpSuits"].isEmpty)

        val (wrongStatus, wrongPlayer) = trump("Bob", "HEARTS")
        assertEquals(400, wrongStatus)
        assertTrue(wrongPlayer["message"].asText().contains("Only Ann may choose trumps"))
        val (badStatus, badSuit) = trump("Ann", "PURPLE")
        assertEquals(400, badStatus)
        assertTrue(badSuit["message"].asText().contains("must be one of"))

        val (status, annView) = trump("Ann", "HEARTS")
        assertEquals(200, status)
        assertEquals("READY_TO_PLAY", annView["round"]["phase"].asText())
        // Ann keeps C9, C10, CJ, CQ (a four-card sequence: 50) plus H9, H10.
        assertEquals(50, annView["finalRoem"]["roem"]["total"].asInt())
        assertEquals(10, annView["finalRoem"]["pointsRequirements"]["requiredTrickPoints"].asInt())

        val (_, round) = get("/round")
        assertEquals("HEARTS", round["trumpSuit"].asText())
        assertEquals(setOf(faceUp), cardsIn(round))
        assertTrue(get("/round/players/Bob").second["finalRoem"].isNull)
    }

    @Test
    fun `a failed bonak commitment is shown publicly without revealing the actual roem`() {
        start()
        bid("Ann", "BID_BONAK"); bid("Bob", "BID_BONAK_WITH_ROEM", 50); bid("Cas", "PASS"); bid("Ann", "PASS")
        // Bob takes H9, H10 and keeps D8, D9, D10: 20 roem.
        discard("Bob", card(Rank.SEVEN, Suit.DIAMONDS), card(Rank.NINE, Suit.HEARTS))
        val (_, bobView) = trump("Bob", "SPADES")
        assertEquals("CONTRACT_FAILED", bobView["round"]["phase"].asText())
        assertEquals(20, bobView["finalRoem"]["roem"]["total"].asInt())
        assertFalse(bobView["finalRoem"]["bonakCommitmentSatisfied"].asBoolean())

        val (_, round) = get("/round")
        assertEquals("INSUFFICIENT_BONAK_ROEM", round["failure"]["reason"].asText())
        assertEquals(50, round["failure"]["declaredRoem"].asInt())
        assertFalse(round.toString().contains("\"total\""))
    }

    @Test
    fun `multi player praat skips trump selection`() {
        start()
        bid("Ann", "BID_PRAAT"); bid("Bob", "BID_PRAAT"); bid("Cas", "PASS")
        val passed = listOf(card(Rank.SEVEN, Suit.CLUBS), faceDown)
        discard("Ann", *passed.toTypedArray())
        val (_, bobView) = get("/round/players/Bob")
        assertEquals(passed.toSet(), cardsIn(bobView["receivedFromStok"]))
        assertEquals(bob + passed + faceUp, cardsIn(bobView))

        val (_, afterBob) = discard("Bob", card(Rank.KING, Suit.CLUBS), card(Rank.ACE, Suit.CLUBS))
        assertEquals("READY_TO_PLAY", afterBob["round"]["phase"].asText())
        assertTrue(afterBob["round"]["playsWithoutTrumps"].asBoolean())
        assertEquals(400, trump("Ann", "HEARTS").first)
    }

    @Test
    fun `a repeated request after a redeal is rejected instead of acting on the new deal`() {
        start()
        bid("Ann", "PASS"); bid("Bob", "PASS")
        val lastVersion = version()
        val (_, redealt) = bid("Cas", "PASS", version = lastVersion)
        assertEquals(2, redealt["dealNumber"].asInt())
        assertEquals("Ann", redealt["dealer"].asText())
        assertEquals("Bob", redealt["auction"]["currentPlayer"].asText())

        val (status, error) = bid("Cas", "PASS", version = lastVersion)
        assertEquals(409, status)
        assertTrue(error["message"].asText().contains("has changed"))
        assertEquals(redealt, get("/round").second)
    }

    @Test
    fun `invalid actions return 400 and leave state unchanged`() {
        start()
        val before = get("/round").second
        val (status, error) = bid("Bob", "PASS")
        assertEquals(400, status)
        assertTrue(error["message"].asText().contains("It is Ann's turn"))
        val (discardStatus, discardError) = discard("Ann", card(Rank.SEVEN, Suit.CLUBS), card(Rank.EIGHT, Suit.CLUBS))
        assertEquals(400, discardStatus)
        assertTrue(discardError["message"].asText().contains("stok exchange"))
        assertEquals(400, trump("Ann", "HEARTS").first)
        assertEquals(before, get("/round").second)
    }

    @Test
    fun `player counts outside three to five are rejected`() {
        listOf("""["A","B"]""", """["A","B","C","D","E","F"]""").forEach { players ->
            val (status, error) = post("/round", """{"players":$players,"dealer":"A"}""")
            assertEquals(400, status)
            assertTrue(error["message"].asText().contains("3 to 5 players"))
        }
    }

    @Test
    fun `returns 404 before any round is started`() {
        assertEquals(404, get("/round").first)
    }

    @Test
    fun `the roem calculator lists competing alternatives and counts the highest`() {
        val json = """{"trump":"CLUBS","cards":[
            {"rank":"JACK","suit":"CLUBS"},{"rank":"JACK","suit":"DIAMONDS"},{"rank":"JACK","suit":"HEARTS"},
            {"rank":"JACK","suit":"SPADES"},{"rank":"TEN","suit":"HEARTS"},{"rank":"QUEEN","suit":"HEARTS"}]}"""
        val (status, result) = post("/roem/calculate", json)
        assertEquals(200, status)
        assertEquals(100, result["total"].asInt())
        assertEquals(listOf(20, 100), result["possibleTotals"].map { it.asInt() })
        assertEquals(2, result["alternatives"].size())
    }

    @Test
    fun `each player's view lists exactly what they can do now`() {
        start()
        val (_, ann) = get("/round/players/Ann")
        assertTrue(ann["availableActions"]["bids"].map { it["type"].asText() }.containsAll(listOf("ROND", "PASS", "BID_PRAAT")))
        assertTrue(get("/round/players/Bob").second["availableActions"]["bids"].isEmpty)

        bid("Ann", "BID_POINTS", 60); bid("Bob", "PASS"); bid("Cas", "PASS")
        assertEquals(2, get("/round/players/Ann").second["availableActions"]["discardCount"].asInt())
        assertTrue(get("/round/players/Bob").second["availableActions"]["discardCount"].isNull)
    }

    @Test
    fun `polling with the ETag returns 304 until the round changes`() {
        start()
        val first = resources.target("/round").request().get()
        val tag = first.entityTag
        assertEquals(version().toString(), tag.value)
        val unchanged = resources.target("/round").request().header("If-None-Match", first.getHeaderString("ETag")).get()
        assertEquals(304, unchanged.status)

        bid("Ann", "PASS")
        val changed = resources.target("/round").request().header("If-None-Match", first.getHeaderString("ETag")).get()
        assertEquals(200, changed.status)
        assertEquals(304, resources.target("/round/players/Ann").request()
            .header("If-None-Match", changed.getHeaderString("ETag")).get().status)
    }

    @Test
    fun `versions keep counting up across new rounds, so old requests never apply`() {
        start()
        bid("Ann", "PASS")
        val oldVersion = version()
        val (_, fresh) = start()
        assertTrue(fresh["version"].asLong() > oldVersion)
        assertEquals(409, bid("Bob", "PASS", version = oldVersion).first)
    }
}
