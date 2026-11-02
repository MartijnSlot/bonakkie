package cli

import auction.Action
import model.Shuffler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unshuffled deck, Ann, Bob, Cas with Cas dealing: Ann = 7♣..Q♣, Bob = K♣ A♣ 7♦..10♦,
 * Cas = J♦ Q♦ K♦ A♦ 7♥ 8♥, stok 9♥ face up.
 */
class TextGameTest {

    private fun play(vararg lines: String, shuffler: Shuffler = Shuffler { it }): String {
        val out = ByteArrayOutputStream()
        TextGame(lines.joinToString("\n").reader().buffered(), PrintStream(out, true, Charsets.UTF_8), shuffler).run()
        return out.toString(Charsets.UTF_8)
    }

    private val game = TextGame("".reader().buffered(), PrintStream(ByteArrayOutputStream()))

    @Test
    fun `shows every hand, the dealer and only the face up stok card`() {
        val output = play("quit")
        assertTrue(output.contains("Ann:  7♣ 8♣ 9♣ 10♣ J♣ Q♣"))
        assertTrue(output.contains("Bob:  K♣ A♣ 7♦ 8♦ 9♦ 10♦"))
        assertTrue(output.contains("Cas:  J♦ Q♦ K♦ A♦ 7♥ 8♥ (dealer)"))
        assertTrue(output.contains("Stok: 9♥ face up, one card face down"))
        assertTrue(output.contains("Bidding starts with Ann"))
    }

    @Test
    fun `parses numbers, praat, bonak with and without roem, pass and rond`() {
        assertEquals(Action.points(60), game.parse("60"))
        assertEquals(Action.PRAAT, game.parse("Praat"))
        assertEquals(Action.BONAK, game.parse("bonak"))
        assertEquals(Action.bonakWithRoem(40), game.parse("bonak 40"))
        assertEquals(Action.PASS, game.parse(" pass "))
        assertEquals(Action.ROND, game.parse("rond"))
        assertNull(game.parse("bonak forty"))
        assertNull(game.parse("sixty"))
    }

    @Test
    fun `bids until a contract is final`() {
        val output = play("60", "praat", "bonak", "pass", "bonak 50", "pass")
        assertTrue(output.contains("Ann: 60 points"))
        assertTrue(output.contains("Bob: Praat"))
        assertTrue(output.contains("Cas: Bonak"))
        assertTrue(output.contains("Options: pass, bonak 20|40|50|60|70|100"))
        assertTrue(output.contains("Bidding is over: Bob plays Bonak with 50 roem"))
    }

    @Test
    fun `illegal bids are explained and the same player is asked again`() {
        val output = play("80", "praat", "sixty", "quit")
        assertTrue(output.contains("Not allowed: Praat cannot be bid once the points bid is above 75."))
        assertTrue(output.contains("Didn't understand \"sixty\""))
        assertEquals(3, Regex("Bob> ").findAll(output).count())
    }

    @Test
    fun `all rond redeals with the same dealer and all pass passes the deal on`() {
        val rond = play("rond", "rond", "rond", "quit", shuffler = Shuffler.seeded(1))
        assertTrue(rond.contains("Bonak - deal 2, Cas deals"))

        val pass = play("pass", "pass", "pass", "quit", shuffler = Shuffler.seeded(1))
        assertTrue(pass.contains("Bonak - deal 2, Ann deals"))
        assertTrue(pass.contains("Bidding starts with Bob"))
    }

    @Test
    fun `stops at the end of input`() {
        assertTrue(play("60").trimEnd().endsWith("Bye."))
    }
}
