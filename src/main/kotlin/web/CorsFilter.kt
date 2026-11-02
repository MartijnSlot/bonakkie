package web

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter

/**
 * Lets a single page app served from one of [allowedOrigins] (e.g. a dev server) call the API.
 * Requests from other origins get no CORS headers, so browsers block them. Jersey answers preflight
 * OPTIONS requests itself; this filter adds the headers to those responses too.
 */
class CorsFilter(allowedOrigins: Collection<String>) : ContainerResponseFilter {
    private val allowed = allowedOrigins.map { it.trimEnd('/') }.toSet()

    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        val origin = request.getHeaderString("Origin") ?: return
        if (origin !in allowed) return
        response.headers.apply {
            putSingle("Access-Control-Allow-Origin", origin)
            add("Vary", "Origin")
            putSingle("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            putSingle("Access-Control-Allow-Headers", "Content-Type, If-None-Match")
            putSingle("Access-Control-Expose-Headers", "ETag")
            putSingle("Access-Control-Max-Age", "600")
        }
    }
}
