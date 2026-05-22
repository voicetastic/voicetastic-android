package re.chasam.voicetastic.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe

/**
 * Tests the bits of [VoicePlayer] that don't actually touch the
 * Android framework — i.e. the idempotent / no-op paths through
 * `finishPlayback`.
 *
 * Anything that exercises real `MediaPlayer` / `AudioTrack` /
 * Codec2 decode needs Robolectric or an instrumented test; those
 * are out of scope for this sprint. What we *can* verify here is
 * the contract documented on [VoicePlayer.stop]:
 *
 *  - `stop()` before `play()` is a no-op (early return in
 *    `finishPlayback` when no resources are held).
 *  - `stop()` doesn't fire `onCompletion` — the caller of `stop`
 *    already knows they asked to stop, so re-firing the callback
 *    would surface as a phantom "playback ended" event in the VM.
 *  - Repeated `stop()` is safe (no second teardown attempt; no
 *    spurious callback).
 *  - `release()` is just `stop()`, same guarantees.
 *
 * Together these pin the sprint-1 fix that wrapped teardown in a
 * `playLock` and a "first caller wins" early-return.
 */
class VoicePlayerTest : FunSpec({

    test("fresh player reports not playing") {
        VoicePlayer().isPlaying.shouldBeFalse()
    }

    test("stop before play does not fire onCompletion") {
        val player = VoicePlayer()
        var fired = 0
        player.onCompletion = { fired++ }
        player.stop()
        fired shouldBe 0
        player.isPlaying.shouldBeFalse()
    }

    test("repeated stop is idempotent and silent") {
        val player = VoicePlayer()
        var fired = 0
        player.onCompletion = { fired++ }
        repeat(5) { player.stop() }
        fired shouldBe 0
        player.isPlaying.shouldBeFalse()
    }

    test("release is idempotent and silent") {
        val player = VoicePlayer()
        var fired = 0
        player.onCompletion = { fired++ }
        player.release()
        player.release()
        fired shouldBe 0
        player.isPlaying.shouldBeFalse()
    }
})
