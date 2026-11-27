package app.netlify.zaynplay.engine

/**
 * Phase B: replaces CloudStream's afterPluginsLoadedEvent (a MainActivity
 * companion event) and MainActivity.lastError. A tiny listener list —
 * no LiveData, no Compose, no lifecycle dependency.
 */
class EngineEvent<T> {
    private val listeners = mutableListOf<(T) -> Unit>()

    operator fun plusAssign(listener: (T) -> Unit) {
        listeners += listener
    }

    operator fun invoke(value: T) {
        for (listener in listeners.toList()) {
            try {
                listener(value)
            } catch (e: Exception) {
                EngineLogger.log("events", "listener threw: ${e.message}")
            }
        }
    }
}

object EngineEvents {
    /** Fired (forceReload) after plugins finish loading / reloading. */
    val afterPluginsLoadedEvent = EngineEvent<Boolean>()

    /** Last plugin-load failure message, for the bridge to surface. */
    var lastError: String? = null
}
