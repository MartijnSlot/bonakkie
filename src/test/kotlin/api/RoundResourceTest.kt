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

    private fun bid(player: String, type: String, amount: Int? = null) =
        post("/api/round/bids", """{"player":"$player","type":"$type"${amount?.let { ""","amount":$it""" } ?: ""}}""")

    private fun discard(player: String, vararg cards: Card) =
        post("/api/round/discards", """{"player":"$player","cards":${mapper.writeValueAsString(cards)}}""")

    /** Every card identity anywhere in a response. */
    private fun cardsIn(node: JsonNode): Set<Card> = when {
        node.isObject && node.has("rank") && node.has("suit") ->
            setOf(Card(Rank.valueOf(node["rank"].asText()), Suit.valueOf(node["suit"].asText())))
        node.isContainerNode -> node.flatMap { cardsIn(it) }.toSet()
        else -> emptySet()
    }

    private fun start() = post("/api/round", """{"players":["Ann","Bob","Cas"],"dealer":"Cas"}""")

    @Test
    fun `public state during bidding only reveals the face up stok card`() {
        val (status, round) = start()
        assertEquals(200, status)
        assertEquals("BIDDING", round["phase"].asText())
        assertEquals(setOf(faceUp), cardsIn(round))
        assertEquals(1, round["stokCardsFaceDown"].asInt())
        assertEquals(12, round["unusedCards"].asInt())
        assertEquals(6, round["handSizes"]["Bob"].asInt())
        assertEquals(setOf(faceUp), cardsIn(get("/api/round").second))
    }

    @Test
    fun `a player sees only their own hand and the face up card`() {
        start()
        val (_, view) = get("/api/round/players/Ann")
        assertEquals(ann, cardsIn(view["hand"]))
        assertEquals(ann + faceUp, cardsIn(view))
        assertEquals(404, get("/api/round/players/Eve").first)
    }

    @Test
    fun `the face down card is only revealed to the declarer who takes it`() {
        start()
        bid("Ann", "BID_POINTS", 60)
        bid("Bob", "PASS")
        val (_, round) = bid("Cas", "PASS")
        assertEquals("STOK_EXCHANGE", round["phase"].asText())
        assertEquals("Ann", round["exchange"]["currentPlayer"].asText())
        assertEquals(8, round["handSizes"]["Ann"].asInt())
        assertEquals(setOf(faceUp), cardsIn(round))

        val (_, annView) = get("/api/round/players/Ann")
        assertEquals(ann + faceUp + faceDown, cardsIn(annView))
        assertEquals(bob + faceUp, cardsIn(get("/api/round/players/Bob").second))
    }

    @Test
    fun `discards stay private and final roem is only in the declarer's view`() {
        start()
        bid("Ann", "BID_POINTS", 60); bid("Bob", "PASS"); bid("Cas", "PASS")
        val discarded = listOf(card(Rank.SEVEN, Suit.CLUBS), card(Rank.EIGHT, Suit.CLUBS))
        val (status, annView) = discard("Ann", *discarded.toTypedArray())
        assertEquals(200, status)
        assertEquals("READY_TO_PLAY", annView["round"]["phase"].asText())
        assertEquals(6, annView["hand"].size())
        // Ann keeps C9, C10, CJ, CQ (a four-card sequence: 50) plus H9, H10.
        assertEquals(50, annView["finalRoem"]["roemByTrump"]["HEARTS"]["total"].asInt())
        val requirements = annView["finalRoem"]["pointsRequirementsByTrump"]["CLUBS"]
        assertEquals(10, requirements["requiredTrickPoints"].asInt())
        assertEquals(1, requirements["minimumTricks"].asInt())

        val (_, round) = get("/api/round")
        assertEquals(2, round["discardedCards"].asInt())
        assertEquals(setOf(faceUp), cardsIn(round))
        assertTrue(round["trumpSuit"].isNull)
        val (_, bobView) = get("/api/round/players/Bob")
        assertEquals(bob + faceUp, cardsIn(bobView))
        assertTrue(bobView["finalRoem"].isNull)
    }

    @Test
    fun `the next praat player sees the passed cards`() {
        start()
        bid("Ann", "BID_PRAAT"); bid("Bob", "BID_PRAAT"); bid("Cas", "PASS")
        val passed = listOf(card(Rank.SEVEN, Suit.CLUBS), faceDown)
        discard("Ann", *passed.toTypedArray())
        val (_, bobView) = get("/api/round/players/Bob")
        assertEquals(passed.toSet(), cardsIn(bobView["receivedFromStok"]))
        assertEquals(8, bobView["hand"].size())
        assertEquals(bob + passed + faceUp, cardsIn(bobView))
        assertEquals(setOf(faceUp), cardsIn(get("/api/round").second))
    }

    @Test
    fun `invalid actions return 400 and leave state unchanged`() {
        start()
        val before = get("/api/round").second
        val (status, error) = bid("Bob", "PASS")
        assertEquals(400, status)
        assertTrue(error["message"].asText().contains("It is Ann's turn"))
        val (discardStatus, discardError) = discard("Ann", card(Rank.SEVEN, Suit.CLUBS), card(Rank.EIGHT, Suit.CLUBS))
        assertEquals(400, discardStatus)
        assertTrue(discardError["message"].asText().contains("stok exchange"))
        assertEquals(before, get("/api/round").second)
        assertEquals(ann, cardsIn(get("/api/round/players/Ann").second["hand"]))
    }

    @Test
    fun `players beyond the deck's capacity are rejected`() {
        val (status, error) = post("/api/round", """{"players":["A","B","C","D","E","F"],"dealer":"F"}""")
        assertEquals(400, status)
        assertTrue(error["message"].asText().contains("at most 5 players"))
    }

    @Test
    fun `returns 404 before any round is started`() {
        assertEquals(404, get("/api/round").first)
    }

    @Test
    fun `calculates roem`() {
        val json = """{"trump":"HEARTS","cards":[
            {"rank":"JACK","suit":"HEARTS"},{"rank":"QUEEN","suit":"HEARTS"},
            {"rank":"KING","suit":"HEARTS"},{"rank":"ACE","suit":"HEARTS"}]}"""
        val (status, result) = post("/api/roem/calculate", json)
        assertEquals(200, status)
        assertEquals(70, result["total"].asInt())
    }
}
