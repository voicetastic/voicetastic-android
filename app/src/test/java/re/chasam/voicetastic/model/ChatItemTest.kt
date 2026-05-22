package re.chasam.voicetastic.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import uniffi.voicetastic.VoiceCodec

/**
 * Pins the `equals` / `hashCode` contract on [ChatItem.Voice].
 *
 * The previous implementation compared only (id, from), which violates
 * the data-class equality contract and would silently dedupe distinct
 * messages if anyone ever put these in a `Set` or used `distinct()` on
 * the chat list. Sprint 3a rewrote the override to compare every field,
 * using `audioData.contentEquals` (the compiler-generated equals on a
 * data class with a ByteArray field uses reference equality on the
 * array, which is its own footgun).
 */
class ChatItemTest : FunSpec({

    val baseAudio = byteArrayOf(1, 2, 3, 4, 5)
    val sameAudio = byteArrayOf(1, 2, 3, 4, 5) // distinct array, same content
    val differentAudio = byteArrayOf(9, 9, 9, 9, 9)

    fun voice(
        audio: ByteArray = baseAudio,
        id: Int = 42,
        from: String = "!a1b2c3d4",
        timestamp: Long = 1_700_000_000L,
        codec: VoiceCodec = VoiceCodec.AmrNb,
    ) = ChatItem.Voice(
        id = id,
        from = from,
        to = "broadcast",
        timestamp = timestamp,
        isOutgoing = false,
        audioData = audio,
        codec = codec,
        isComplete = true,
        totalChunks = 1,
        receivedChunks = 1,
        bitrateIndex = 0,
        channel = 0,
        contactKey = "broadcast",
    )

    test("two Voice items with identical content compare equal") {
        voice(audio = baseAudio) shouldBe voice(audio = sameAudio)
    }

    test("Voice items with different audio content are not equal") {
        voice(audio = baseAudio) shouldNotBe voice(audio = differentAudio)
    }

    test("Voice items with different ids are not equal even with same audio") {
        voice(id = 1) shouldNotBe voice(id = 2)
    }

    test("Voice items with different timestamps are not equal") {
        voice(timestamp = 1L) shouldNotBe voice(timestamp = 2L)
    }

    test("Voice items with different codec are not equal") {
        voice(codec = VoiceCodec.AmrNb) shouldNotBe voice(codec = VoiceCodec.Opus)
    }

    test("hashCode is consistent with equals for identical content") {
        val a = voice(audio = baseAudio)
        val b = voice(audio = sameAudio)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    test("hashCode includes audio content") {
        // Not strictly required by the contract, but a regression marker:
        // if someone reverts to id+from-only hashing they'll get a hit here.
        voice(audio = baseAudio).hashCode() shouldNotBe voice(audio = differentAudio).hashCode()
    }

    test("Voice is never equal to a Text with the same id") {
        val voice = voice()
        val text = ChatItem.Text(
            id = 42,
            from = "!a1b2c3d4",
            to = "broadcast",
            timestamp = 1_700_000_000L,
            isOutgoing = false,
            text = "hello",
            channel = 0,
            contactKey = "broadcast",
        )
        voice shouldNotBe text
    }
})
