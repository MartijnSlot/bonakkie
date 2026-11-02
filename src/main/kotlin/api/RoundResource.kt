package api

import auction.Action
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import model.Card
import model.Shuffler
import round.Round

/** Holds the single in-memory round. Each transition replaces the immutable round in one step. */
class RoundStore(private val shuffler: Shuffler = Shuffler.random()) {
    private var round: Round? = null

    @Synchronized
    fun current(): Round = round ?: throw NotFoundException("No round has been started.")

    @Synchronized
    fun start(players: List<String>, dealer: String): Round = Round.deal(players, dealer, shuffler).also { round = it }

    @Synchronized
    fun bid(player: String, action: Action): Round = current().bid(player, action).also { round = it }

    @Synchronized
    fun discard(player: String, cards: List<Card>): Round = current().discard(player, cards).also { round = it }
}

@Path("/api/round")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class RoundResource(private val store: RoundStore) {

    /** Public state: no hidden card identities. */
    @GET
    fun get(): RoundView = RoundView.of(store.current())

    /** Deals a fresh round and starts bidding, replacing any existing round. */
    @POST
    fun start(request: StartRoundRequest): RoundView = RoundView.of(store.start(request.players, request.dealer))

    /** The given player's private view, including their own cards. */
    @GET
    @Path("/players/{player}")
    fun player(@PathParam("player") player: String): PlayerView {
        val round = store.current()
        if (player !in round.players) throw NotFoundException("$player is not playing in this round.")
        return PlayerView.of(round, player)
    }

    @POST
    @Path("/bids")
    fun bid(request: ActionRequest): RoundView =
        RoundView.of(store.bid(request.player, Action(request.type, request.amount)))

    /** Returns the discarding player's private view, since it contains their new hand. */
    @POST
    @Path("/discards")
    fun discard(request: DiscardRequest): PlayerView =
        PlayerView.of(store.discard(request.player, request.cards), request.player)
}
