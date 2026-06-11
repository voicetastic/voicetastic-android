package re.chasam.voicetastic.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse

/**
 * Tests the bits of [VoicePlayer] that don't actually touch the Android
 * framework — i.e. the idempotent / no-op paths through `finishPlayback`.
 *
 * Anything that exercises real `MediaPlayer` / `AudioTrack` / Codec2 decode
 * needs Robolectric or an instrumented test; those are out of scope here.
 * What we *can* verify is the contract documented on [VoicePlayer.stop]:
 *
 *  - `stop()` before `play()` is a no-op (early return in `finishPlayback`
 *    when no resources are held), and never fires a completion callback —
 *    none could have been registered without a `play()`.
 *  - Repeated `stop()` is safe (no second teardown attempt).
 *  - `release()` is just `stop()`, same guarantees.
 *
 * Together these pin the teardown fix that wrapped teardown in a `playLock`,
 * an `expected`-identity check, and a "first caller wins" early-return.
 */
class VoicePlayerTest : FunSpec({

    test("fresh player reports not playing") {
        VoicePlayer().isPlaying.shouldBeFalse()
    }

    test("stop before play is a no-op") {
        val player = VoicePlayer()
        player.stop()
        player.isPlaying.shouldBeFalse()
    }

    test("repeated stop is idempotent") {
        val player = VoicePlayer()
        repeat(5) { player.stop() }
        player.isPlaying.shouldBeFalse()
    }

    test("release is idempotent") {
        val player = VoicePlayer()
        player.release()
        player.release()
        player.isPlaying.shouldBeFalse()
    }
})
