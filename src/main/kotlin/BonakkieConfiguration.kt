import com.fasterxml.jackson.annotation.JsonProperty
import io.dropwizard.Configuration

class BonakkieConfiguration : Configuration() {
    @get:JsonProperty
    @set:JsonProperty
    var appName: String = ""
}