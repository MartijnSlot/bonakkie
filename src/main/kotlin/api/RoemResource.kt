package api

import auction.RuleViolation
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import roem.RoemCalculator
import roem.RoemCombinationType

@Path("/api/roem")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class RoemResource {

    @GET
    @Path("/combinations")
    fun combinations() = RoemCombinationType.entries.map { RoemCombinationTypeView(it, it.label, it.value) }

    @POST
    @Path("/calculate")
    fun calculate(request: RoemRequest): RoemResultView = try {
        RoemResultView.of(RoemCalculator.calculate(request.cards, request.trump))
    } catch (e: IllegalArgumentException) {
        throw RuleViolation(e.message ?: "Invalid cards.")
    }
}
