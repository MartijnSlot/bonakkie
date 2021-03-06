import io.dropwizard.Application
import io.dropwizard.setup.Environment
import model.Game
import model.Player
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class Bonakkie : Application<BonakkieConfiguration>() {

    val LOGGER: Logger = LoggerFactory.getLogger(Bonakkie::class.java)

    @Throws(Exception::class)
    override fun run(configuration: BonakkieConfiguration, environment: Environment) {
        try {
            println("Application name: ${configuration.appName}")
        } catch (e: Exception) {
            LOGGER.info("Application njame: {}", configuration.appName)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Bonakkie().run(*args)
        }
    }
}
