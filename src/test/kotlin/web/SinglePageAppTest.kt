package web

import Bonakkie
import BonakkieConfiguration
import io.dropwizard.testing.ConfigOverride
import io.dropwizard.testing.ResourceHelpers
import io.dropwizard.testing.junit5.DropwizardAppExtension
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** Boots the real application with a built-app folder and checks how a browser would see it. */
@ExtendWith(DropwizardExtensionsSupport::class)
class SinglePageAppTest {

    companion object {
        private val parent: Path = Files.createTempDirectory("bonakkie-spa")
        private val app: Path = Files.createDirectory(parent.resolve("app")).also {
            Files.writeString(it.resolve("index.html"), "<!doctype html><div id=app></div>")
            Files.createDirectory(it.resolve("assets"))
            Files.writeString(it.resolve("assets").resolve("main.js"), "console.log('bonak')")
            Files.writeString(parent.resolve("secret.txt"), "not for browsers")
        }

        @JvmField
        val APP = DropwizardAppExtension(
            Bonakkie::class.java,
            ResourceHelpers.resourceFilePath("test-config.yml"),
            ConfigOverride.config("frontend.directory", app.toString()),
        )
    }

    private val http = HttpClient.newHttpClient()

    private fun send(path: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null):
        HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:${APP.localPort}$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `the app is served at the root`() {
        val response = send("/")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("id=app"))
        assertTrue(response.headers().firstValue("Content-Type").get().startsWith("text/html"))
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").get())
    }

    @Test
    fun `client side routes get index html`() {
        val response = send("/table/3/players")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("id=app"))
    }

    @Test
    fun `assets are served with their content type, missing assets are 404`() {
        val script = send("/assets/main.js")
        assertEquals(200, script.statusCode())
        assertTrue(script.headers().firstValue("Content-Type").get().contains("javascript"))
        assertEquals(404, send("/assets/missing.js").statusCode())
    }

    @Test
    fun `files outside the app folder are never served`() {
        listOf("/../secret.txt", "/%2e%2e/secret.txt", "/assets/..%2f..%2fsecret.txt").forEach { path ->
            val response = send(path)
            assertNotEquals(200, response.statusCode(), path)
            assertFalse(response.body().contains("not for browsers"), path)
        }
    }

    @Test
    fun `the api is under api and answers in json, never with the app`() {
        val combinations = send("/api/roem/combinations")
        assertEquals(200, combinations.statusCode())
        assertTrue(combinations.headers().firstValue("Content-Type").get().startsWith("application/json"))

        // 404 whether or not another test in this class already started a round.
        val noPlayer = send("/api/round/players/Nobody")
        assertEquals(404, noPlayer.statusCode())
        assertTrue(noPlayer.body().contains("\"message\""))

        val unknown = send("/api/does-not-exist")
        assertEquals(404, unknown.statusCode())
        assertFalse(unknown.body().contains("id=app"))
    }

    @Test
    fun `a full round can be started through the api prefix`() {
        val response = send(
            "/api/round", "POST", mapOf("Content-Type" to "application/json"),
            """{"players":["Ann","Bob","Cas"],"dealer":"Cas"}""",
        )
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"phase\":\"BIDDING\""))
    }

    @Test
    fun `allowed origins get cors headers, including on preflight`() {
        val origin = mapOf("Origin" to "http://localhost:5173")
        val response = send("/api/roem/combinations", headers = origin)
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals("ETag", response.headers().firstValue("Access-Control-Expose-Headers").orElse(null))

        val preflight = send(
            "/api/round/bids", "OPTIONS",
            origin + mapOf("Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "content-type"),
        )
        assertEquals(200, preflight.statusCode())
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"))
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Content-Type"))
    }

    @Test
    fun `other origins get no cors headers`() {
        val response = send("/api/roem/combinations", headers = mapOf("Origin" to "https://evil.example"))
        assertNull(response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
    }

    @Test
    fun `configuration types are wired`() {
        val configuration: BonakkieConfiguration = APP.configuration
        assertEquals(app.toString(), configuration.frontend.directory)
    }
}
