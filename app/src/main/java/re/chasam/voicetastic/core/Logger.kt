package re.chasam.voicetastic.core

/**
 * Pure-Kotlin logging seam. Pure-logic code in `re.chasam.voicetastic.core.*`
 * must depend on this interface rather than on [android.util.Log], so it can
 * be unit-tested off-device and re-used by a JVM/desktop host (or by the
 * Rust `voicetastic-core` crate via a JNI bridge that proxies to a
 * `tracing` subscriber).
 */
interface Logger {
    fun d(tag: String, msg: String, t: Throwable? = null)
    fun i(tag: String, msg: String, t: Throwable? = null)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)

    companion object {
        /** Discards every message — safe default for tests. */
        val Noop: Logger = object : Logger {
            override fun d(tag: String, msg: String, t: Throwable?) {}
            override fun i(tag: String, msg: String, t: Throwable?) {}
            override fun w(tag: String, msg: String, t: Throwable?) {}
            override fun e(tag: String, msg: String, t: Throwable?) {}
        }
    }
}

/** In-memory logger that records every entry — handy for asserting in tests. */
class RecordingLogger : Logger {
    data class Entry(val level: String, val tag: String, val msg: String, val t: Throwable?)
    private val _entries = mutableListOf<Entry>()
    val entries: List<Entry> get() = synchronized(_entries) { _entries.toList() }
    private fun record(level: String, tag: String, msg: String, t: Throwable?) {
        synchronized(_entries) { _entries.add(Entry(level, tag, msg, t)) }
    }
    override fun d(tag: String, msg: String, t: Throwable?) = record("D", tag, msg, t)
    override fun i(tag: String, msg: String, t: Throwable?) = record("I", tag, msg, t)
    override fun w(tag: String, msg: String, t: Throwable?) = record("W", tag, msg, t)
    override fun e(tag: String, msg: String, t: Throwable?) = record("E", tag, msg, t)
}

