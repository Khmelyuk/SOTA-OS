package sotaos.api.sync

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration

enum class ConfigurationReloadResult { UNCHANGED, APPLIED, REJECTED }

/** Trusted local scheduling changes only. Immutable hosting/admission inputs must match startup configuration. */
class P09ConfigurationReload(private val path: Path, initial: Map<String, String>) {
    private val fixed = initial - MUTABLE_FIELDS
    private var current = loopSettings(initial)

    fun poll(apply: (SyncLoopSettings) -> Unit): ConfigurationReloadResult = try {
        require(Files.isRegularFile(path))
        val candidate = P09NodeConfiguration.load(path, "run")
        require(candidate - MUTABLE_FIELDS == fixed)
        val settings = loopSettings(candidate)
        if (settings == current) {
            ConfigurationReloadResult.UNCHANGED
        } else {
            apply(settings)
            current = settings
            ConfigurationReloadResult.APPLIED
        }
    } catch (_: IOException) {
        ConfigurationReloadResult.REJECTED
    } catch (_: IllegalArgumentException) {
        ConfigurationReloadResult.REJECTED
    }

    companion object {
        private val MUTABLE_FIELDS = setOf("interval-seconds", "max-backoff-seconds")

        fun loopSettings(values: Map<String, String>): SyncLoopSettings {
            val defaults = SyncLoopSettings()
            return defaults.copy(
                interval = values["interval-seconds"]?.toLong()?.let(Duration::ofSeconds) ?: defaults.interval,
                maxBackoff = values["max-backoff-seconds"]?.toLong()?.let(Duration::ofSeconds) ?: defaults.maxBackoff)
        }
    }
}
