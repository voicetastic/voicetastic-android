package re.chasam.voicetastic.voice

import uniffi.voicetastic.AssemblerConfig
import uniffi.voicetastic.AssemblyEvent
import uniffi.voicetastic.TickOutput
import uniffi.voicetastic.VoiceAssembler as RustVoiceAssembler

/**
 * Test seam over the native [RustVoiceAssembler].
 *
 * The concrete assembler is a UniFFI handle backed by the Rust core, so it
 * can't be constructed on a plain JVM (no native lib in unit tests). Exposing
 * just the behaviour the ViewModel depends on lets the tests drive a fake.
 */
interface VoiceAssemblerApi {
    fun accept(
        from: String,
        broadcast: Boolean,
        toNode: UInt,
        channel: UInt,
        frame: ByteArray,
    ): AssemblyEvent

    fun setConfig(cfg: AssemblerConfig)
    fun tick(): TickOutput
    fun close()
}

/** Production implementation: thin delegate over the native assembler. */
class RustAssembler(config: AssemblerConfig) : VoiceAssemblerApi {
    private val inner = RustVoiceAssembler(config)

    override fun accept(
        from: String,
        broadcast: Boolean,
        toNode: UInt,
        channel: UInt,
        frame: ByteArray,
    ): AssemblyEvent = inner.accept(from, broadcast, toNode, channel, frame)

    override fun setConfig(cfg: AssemblerConfig) = inner.setConfig(cfg)
    override fun tick(): TickOutput = inner.tick()
    override fun close() = inner.close()
}
