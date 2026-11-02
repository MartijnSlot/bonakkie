import com.fasterxml.jackson.annotation.JsonProperty
import io.dropwizard.core.Configuration

class BonakkieConfiguration : Configuration() {
    @get:JsonProperty
    @set:JsonProperty
    lateinit var appName: String
}