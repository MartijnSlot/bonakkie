package api

import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.dropwizard.jackson.Jackson
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport
import io.dropwizard.testing.junit5.ResourceExtension
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.GenericType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(DropwizardExtensionsSupport::class)
class AuctionResourceTest {

    @JvmField
    val resources: ResourceExtension = ResourceExtension.builder()
        .setMapper(Jackson.newObjectMapper().registerModule(kotlinModule()))
        .addResource(AuctionResource(AuctionStore()))
        .addResource(RoemResource())
        .addProvider(RuleViolationMapper())
        .build()

    private val mapType = object : GenericType<Map<String, Any?>>() {}

    private fun post(path: String, json: String) =
        resources.target(path).request().post(Entity.json(json))

    @Test
    fun `starts an auction and accepts a legal action`() {
        post("/api/auction", """{"players":["Ann","Bob","Cas"],"dealer":"Cas"}""").use {
            assertEquals(200, it.status)
            val body = it.readEntity(mapType)
            assertEquals("Ann", body["currentPlayer"])
            assertEquals("IN_PROGRESS", body["status"])
        }
        post("/api/auction/actions", """{"player":"Ann","type":"BID_POINTS","amount":60}""").use {
            assertEquals(200, it.status)
            val body = it.readEntity(mapType)
            assertEquals("Bob", body["currentPlayer"])
            assertEquals(1, (body["history"] as List<*>).size)
        }
    }

    @Test
    fun `rejects an illegal action with a clear error and keeps state`() {
        post("/api/auction", """{"players":["Ann","Bob"],"dealer":"Bob"}""").close()
        post("/api/auction/actions", """{"player":"Bob","type":"PASS"}""").use {
            assertEquals(400, it.status)
            assertTrue(it.readEntity(mapType)["message"].toString().contains("It is Ann's turn"))
        }
        val state = resources.target("/api/auction").request().get(mapType)
        assertEquals(emptyList<Any>(), state["history"])
        assertEquals("Ann", state["currentPlayer"])
    }

    @Test
    fun `returns 404 before any auction is started`() {
        assertEquals(404, resources.target("/api/auction").request().get().status)
    }

    @Test
    fun `exposes points contract requirements`() {
        post("/api/auction", """{"players":["Ann","Bob"],"dealer":"Bob"}""").close()
        post("/api/auction/actions", """{"player":"Ann","type":"BID_POINTS","amount":100}""").close()
        post("/api/auction/actions", """{"player":"Bob","type":"PASS"}""").close()
        val requirements = resources.target("/api/auction/contract/requirements")
            .queryParam("roem", 40).request().get(mapType)
        assertEquals(mapOf("bidAmount" to 100, "totalRoem" to 40, "requiredTrickPoints" to 60, "minimumTricks" to 1), requirements)
    }

    @Test
    fun `calculates roem`() {
        val json = """{"trump":"HEARTS","cards":[
            {"rank":"JACK","suit":"HEARTS"},{"rank":"QUEEN","suit":"HEARTS"},
            {"rank":"KING","suit":"HEARTS"},{"rank":"ACE","suit":"HEARTS"}]}"""
        post("/api/roem/calculate", json).use {
            assertEquals(200, it.status)
            assertEquals(70, it.readEntity(mapType)["total"])
        }
    }
}
