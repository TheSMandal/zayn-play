package app.netlify.zaynplay.engine

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Phase A engine log: a small ring buffer of the app's own log lines.
 * The bridge `logcat` method reads this in a later phase (until then it
 * answers engine_not_ready like every other unimplemented method).
 * Nothing here ever leaves the device except through the bridge.
 */
object EngineLogger {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val dateFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        lines.addLast("${dateFmt.format(Date())} $tag: $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
