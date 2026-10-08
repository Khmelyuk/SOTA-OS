package sotaos.api.cli

import sotaos.api.sync.ConfigurationReloadResult
import sotaos.api.sync.P09ConfigurationReload
import sotaos.api.sync.P09NodeHost

internal class SyncReload(arguments: Arguments) {
    private val reload = if (arguments.options["reload-config"] == "true") P09ConfigurationReload(
        requireNotNull(arguments.configurationPath),
        arguments.options + ("db" to requireNotNull(arguments.databasePath).toString())) else null
    private var previous = ConfigurationReloadResult.UNCHANGED

    fun poll(host: P09NodeHost) {
        val result = reload?.poll(host::updateSettings) ?: return
        if (result == ConfigurationReloadResult.APPLIED) {
            System.err.println("P09 scheduling configuration applied.")
        } else if (result == ConfigurationReloadResult.REJECTED && result != previous) {
            System.err.println("P09 configuration reload rejected; current settings retained. " +
                "Restart for fixed changes.")
        }
        previous = result
    }
}
