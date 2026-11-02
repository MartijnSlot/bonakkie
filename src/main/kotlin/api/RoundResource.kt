package api

import auction.Action
import auction.RuleViolation
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
import model.Suit
import round.Round

/**
 * Holds the single in-memory round. Each transition replaces the immutable round in one synchronized step,
 * and only applies to the version the request was based on, so a repeated request cannot act twice.
 */
class RoundStore(private val shuffler: Shuffler = Shuffler.random()) {
    private var round: Round? = null

    @Synchronized
    fun current(): Round = round ?: throw NotFoundException("No round has been started.")

    @Synchronized
    fun start(players: List<String>, dealer: String): Round = Round.deal(players, dealer, shuffler).also { round = it }

    @Synchronized
    fun bid(version: Long, player: String, action: Action): Round = transition(version) { it.bid(player, action) }

    @Synchronized
    fun discard(version: Long, player: String, cards: List<Card>): Round = transition(version) { it.discard(player, cards) }

    @Synchronized
    fun selectTrump(version: Long, player: String, suit: Suit): Round = transition(version) { it.selectTrump(player, suit) }

    private fun transition(version: Long, change: (Round) -> Round): Round {
        val current = current()
        if (version != current.version) {
            throw StaleRequest("The round has changed (version ${current.version}, request was for $version). Reload and try again.")
        }
        return change(current).also { round = it }
    }
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
        RoundView.of(store.bid(request.version, request.player, Action(request.type, request.amount)))

    /** Returns the discarding player's private view, since it contains their new hand. */
    @POST
    @Path("/discards")
    fun discard(request: DiscardRequest): PlayerView =
        PlayerView.of(store.discard(request.version, request.player, request.cards), request.player)

    /** Returns the declarer's private view, since it contains their final roem. */
    @POST
    @Path("/trump")
    fun selectTrump(request: TrumpRequest): PlayerView {
        val suit = Suit.entries.find { it.name == request.suit }
            ?: throw RuleViolation("Trumps must be one of ${Suit.entries.joinToString(", ")}.")
        return PlayerView.of(store.selectTrump(request.version, request.player, suit), request.player)
    }
}
