package re.chasam.voicetastic.core

/**
 * Wall-clock abstraction. Replaces direct [System.currentTimeMillis] calls so
 * pure-logic code (voice assembler, message timestamps, …) can be exercised
 * deterministically from unit tests with a [FakeClock].
 *
 * Mirrors the role `chrono` / `tokio::time` play in the upstream
 * `voicetastic-core` Rust crate at
 * <https://git.cha-sam.re/acarteron/voicetastic-desktop>.
 */
fun interface Clock {
    /** Current epoch time in milliseconds. */
    fun nowMs(): Long

    companion object {
        /** Default production clock backed by [System.currentTimeMillis]. */
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Mutable clock for tests. Time only advances when [advanceBy] is called. */
class FakeClock(initialMs: Long = 0L) : Clock {
    @Volatile private var now: Long = initialMs
    override fun nowMs(): Long = now
    fun advanceBy(ms: Long) { now += ms }
    fun setNow(ms: Long) { now = ms }
}

