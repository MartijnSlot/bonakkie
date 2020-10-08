import com.fasterxml.jackson.annotation.JsonProperty
import io.dropwizard.Configuration

class BonakkieConfiguration : Configuration() {
    @get:JsonProperty
    @set:JsonProperty
    lateinit var appName: String
}