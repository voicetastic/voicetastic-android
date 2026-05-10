package re.chasam.voicetastic.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClockTest : FunSpec({

    test("system clock returns wall-time milliseconds") {
        val before = System.currentTimeMillis()
        val now = Clock.System.nowMs()
        val after = System.currentTimeMillis()
        (now in before..after) shouldBe true
    }

    test("fake clock only advances on advanceBy") {
        val c = FakeClock(initialMs = 1_000L)
        c.nowMs() shouldBe 1_000L
        c.nowMs() shouldBe 1_000L
        c.advanceBy(500L)
        c.nowMs() shouldBe 1_500L
        c.setNow(9_999L)
        c.nowMs() shouldBe 9_999L
    }
})

class LoggerTest : FunSpec({

    test("noop logger swallows everything") {
        Logger.Noop.d("t", "msg")
        Logger.Noop.i("t", "msg")
        Logger.Noop.w("t", "msg")
        Logger.Noop.e("t", "msg", RuntimeException("boom"))
    }

    test("recording logger captures level, tag, msg, throwable") {
        val log = RecordingLogger()
        val boom = RuntimeException("boom")
        log.d("T", "d-msg")
        log.i("T", "i-msg")
        log.w("T", "w-msg")
        log.e("T", "e-msg", boom)

        val entries = log.entries
        entries.size shouldBe 4
        entries[0].level shouldBe "D"
        entries[1].level shouldBe "I"
        entries[2].level shouldBe "W"
        entries[3].level shouldBe "E"
        entries[3].t shouldBe boom
        entries.map { it.tag }.toSet() shouldBe setOf("T")
        entries[2].msg shouldBe "w-msg"
    }
})

