package re.chasam.voicetastic.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Equality contract for [IncomingData].
 *
 * The compiler-generated `equals` on a data class with a `ByteArray`
 * field uses reference equality on the array — so two messages with
 * identical content but distinct buffers would compare unequal, and
 * mockk argument matchers that build a fresh ByteArray to assert
 * against would always fail. The override added in sprint 3b uses
 * `contentEquals` / `contentHashCode` instead.
 */
class MeshTypesTest : FunSpec({

    fun data(
        from: String = "!a1b2c3d4",
        payload: ByteArray = byteArrayOf(1, 2, 3),
        timestamp: Long = 1_700_000_000L,
    ) = IncomingData(
        from = from,
        to = "broadcast",
        portNum = 256,
        payload = payload,
        channel = 0,
        timestamp = timestamp,
    )

    test("two IncomingData with identical content are equal") {
        val a = data(payload = byteArrayOf(1, 2, 3))
        val b = data(payload = byteArrayOf(1, 2, 3))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    test("two IncomingData with different payload are not equal") {
        data(payload = byteArrayOf(1, 2, 3)) shouldNotBe data(payload = byteArrayOf(9, 9, 9))
    }

    test("two IncomingData with different sender are not equal") {
        data(from = "!aaa") shouldNotBe data(from = "!bbb")
    }
})
