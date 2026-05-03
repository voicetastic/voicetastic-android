package re.chasam.voicetastic.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class VoiceConfigTest : FunSpec({

    test("default config has 20s max duration") {
        val config = VoiceConfig()
        config.maxDurationSeconds shouldBe 20
    }

    test("default config has 30s chunk timeout") {
        val config = VoiceConfig()
        config.chunkTimeoutSeconds shouldBe 30
    }

    test("default config has MR795 bitrate") {
        val config = VoiceConfig()
        config.bitrate shouldBe AmrNbBitrate.MR795
    }

    test("default config has partial play enabled") {
        val config = VoiceConfig()
        config.partialPlayOnTimeout shouldBe true
    }

    test("all AMR-NB bitrates have positive bps") {
        AmrNbBitrate.entries.forEach { br ->
            br.bps shouldBeGreaterThan 0
        }
    }

    test("AMR-NB bitrate bytesPerSecond is approximately bps/8") {
        AmrNbBitrate.entries.forEach { br ->
            br.bytesPerSecond shouldBe br.bps / 8
        }
    }

    test("AMR-NB bitrates are in ascending order") {
        val bpsList = AmrNbBitrate.entries.map { it.bps }
        bpsList shouldBe bpsList.sorted()
    }

    test("config copy preserves unchanged fields") {
        val original = VoiceConfig(
            bitrate = AmrNbBitrate.MR475,
            maxDurationSeconds = 10,
            chunkTimeoutSeconds = 15,
            partialPlayOnTimeout = false
        )
        val modified = original.copy(maxDurationSeconds = 30)
        modified.bitrate shouldBe AmrNbBitrate.MR475
        modified.maxDurationSeconds shouldBe 30
        modified.chunkTimeoutSeconds shouldBe 15
        modified.partialPlayOnTimeout shouldBe false
    }
})

