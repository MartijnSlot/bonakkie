package resource

import model.Card
import model.Deck
import model.Game
import model.Player
import view.GameView
import javax.annotation.PostConstruct
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.core.Response

@Path("/")
class GameResource {

    @GET
    @Path("/")
    fun startGame(playerNames: Array<String>) : Response {
        val playerList: MutableList<Player> = mutableListOf()
        playerNames.forEach { player ->
            playerList.add(Player(player))
        }
        val deck = Deck()
        val dealtPlayerList: List<Player> = deck.deal(playerList)
        val stok: List<Card> = deck.getStok()
        return Response.ok(
            GameView(
                players = dealtPlayerList,
                stok = stok
            )
        ).build()
    }
}