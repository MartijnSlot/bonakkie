package api

import auction.Action
import auction.Auction
import auction.PointsContract
import auction.PointsRequirements
import auction.RuleViolation
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/** Holds the single in-memory auction. Each transition replaces the immutable auction in one step. */
class AuctionStore {
    private var auction: Auction? = null

    @Synchronized
    fun current(): Auction = auction ?: throw NotFoundException("No auction has been started.")

    @Synchronized
    fun start(players: List<String>, dealer: String): Auction = Auction.start(players, dealer).also { auction = it }

    @Synchronized
    fun act(player: String, action: Action): Auction = current().apply(player, action).also { auction = it }
}

@Path("/api/auction")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuctionResource(private val store: AuctionStore) {

    @GET
    fun get(): AuctionView = AuctionView.of(store.current())

    /** Starts a fresh auction, replacing any existing one. */
    @POST
    fun start(request: StartAuctionRequest): AuctionView = AuctionView.of(store.start(request.players, request.dealer))

    @POST
    @Path("/actions")
    fun act(request: ActionRequest): AuctionView =
        AuctionView.of(store.act(request.player, Action(request.type, request.amount)))

    /** Trick requirements of the finalized points contract for a given total roem. */
    @GET
    @Path("/contract/requirements")
    fun requirements(@QueryParam("roem") roem: Int?): PointsRequirements {
        val contract = store.current().contract as? PointsContract
            ?: throw RuleViolation("Requirements only apply to a finalized points contract.")
        return contract.requirements(roem ?: 0)
    }
}
