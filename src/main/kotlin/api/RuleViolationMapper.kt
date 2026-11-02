package api

import auction.RuleViolation
import io.dropwizard.jersey.errors.ErrorMessage
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper

class RuleViolationMapper : ExceptionMapper<RuleViolation> {
    override fun toResponse(exception: RuleViolation): Response =
        Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(ErrorMessage(Response.Status.BAD_REQUEST.statusCode, exception.message))
            .build()
}
