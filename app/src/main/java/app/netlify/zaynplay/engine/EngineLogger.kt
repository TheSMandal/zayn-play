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

    // (v1.0.20 P21-B5) Perf lines: the 200-line, second-resolution ring
    // above cannot carry timing evidence — a 300 ms render is invisible
    // at 1 s resolution and old lines scroll off during a long session.
    // Perf lines therefore live in their own 500-line ring AND are
    // mirrored into the main ring, so the existing `logcat` bridge dump
    // includes them. Every perf line carries an epoch-ms timestamp
    // (System.currentTimeMillis), never just HH:mm:ss.
    private const val MAX_PERF_LINES = 500
    private val perfLines = ArrayDeque<String>()

    // (v1.0.20 P21-B4) Cold-start anchor: MainActivity.onCreate records
    // SystemClock.elapsedRealtime() here; the first-feed paint (JS
    // perfFirstPaint bridge call) logs elapsedRealtime() - this value.
    @Volatile
    var coldStartT0Ms: Long = 0L

    @Synchronized
    fun log(tag: String, msg: String) {
        lines.addLast("${dateFmt.format(Date())} $tag: $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /** (v1.0.20 P21-B5) Log a perf line: epoch-ms timestamped, kept in
     * the dedicated perf ring and mirrored into the main ring. */
    @Synchronized
    fun logPerf(tag: String, msg: String) {
        val line = "${System.currentTimeMillis()} perf $tag: $msg"
        perfLines.addLast(line)
        while (perfLines.size > MAX_PERF_LINES) perfLines.removeFirst()
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** (v1.0.20 P21-B5) Snapshot of the dedicated perf ring only. */
    @Synchronized
    fun perfSnapshot(): List<String> = perfLines.toList()
}
