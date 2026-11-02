import api.RoemResource
import api.RoundResource
import api.RoundStore
import api.RuleViolationMapper
import api.StaleRequestMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.dropwizard.core.Application
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import web.CorsFilter
import web.SpaServlet
import java.nio.file.Files
import java.nio.file.Path

internal class Bonakkie : Application<BonakkieConfiguration>() {

    val LOGGER: Logger = LoggerFactory.getLogger(Bonakkie::class.java)

    override fun initialize(bootstrap: Bootstrap<BonakkieConfiguration>) {
        bootstrap.objectMapper.registerModule(kotlinModule())
    }

    override fun run(configuration: BonakkieConfiguration, environment: Environment) {
        LOGGER.info("Application name: {}", configuration.appName)

        // The API lives under /api so the single page app can own every other path.
        environment.jersey().setUrlPattern("/api/*")
        environment.jersey().register(RoundResource(RoundStore()))
        environment.jersey().register(RoemResource())
        environment.jersey().register(RuleViolationMapper())
        environment.jersey().register(StaleRequestMapper())

        val frontend = configuration.frontend
        if (frontend.allowedOrigins.isNotEmpty()) {
            environment.jersey().register(CorsFilter(frontend.allowedOrigins))
        }
        frontend.directory?.takeIf { it.isNotBlank() }?.let { directory ->
            val path = Path.of(directory)
            if (!Files.isRegularFile(path.resolve("index.html"))) {
                LOGGER.warn("No index.html in frontend directory {}; only API requests will succeed.", path.toAbsolutePath())
            }
            environment.servlets().addServlet("spa", SpaServlet(path)).addMapping("/*")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Bonakkie().run(*args)
        }
    }
}
