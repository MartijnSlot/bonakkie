import com.fasterxml.jackson.annotation.JsonProperty
import io.dropwizard.core.Configuration

class BonakkieConfiguration : Configuration() {
    @get:JsonProperty
    @set:JsonProperty
    lateinit var appName: String

    @get:JsonProperty
    @set:JsonProperty
    var frontend: FrontendConfiguration = FrontendConfiguration()
}

class FrontendConfiguration {
    /** Folder with the built single page app, or null to serve only the API. */
    @get:JsonProperty
    @set:JsonProperty
    var directory: String? = null

    /** Browser origins allowed to call the API cross-origin (CORS). */
    @get:JsonProperty
    @set:JsonProperty
    var allowedOrigins: List<String> = emptyList()
}
