import api.RoemResource
import api.RoundResource
import api.RoundStore
import api.RuleViolationMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.dropwizard.core.Application
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class Bonakkie : Application<BonakkieConfiguration>() {

    val LOGGER: Logger = LoggerFactory.getLogger(Bonakkie::class.java)

    override fun initialize(bootstrap: Bootstrap<BonakkieConfiguration>) {
        bootstrap.objectMapper.registerModule(kotlinModule())
    }

    override fun run(configuration: BonakkieConfiguration, environment: Environment) {
        LOGGER.info("Application name: {}", configuration.appName)
        environment.jersey().register(RoundResource(RoundStore()))
        environment.jersey().register(RoemResource())
        environment.jersey().register(RuleViolationMapper())
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Bonakkie().run(*args)
        }
    }
}
