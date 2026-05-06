package re.chasam.voicetastic.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the Meshtastic 4-byte stream framing used by the USB
 * transport. Pure JVM, no Android.
 */
class MeshSerialFramingTest : FunSpec({

    test("constants match Meshtastic spec") {
        MeshSerialFraming.START1 shouldBe 0x94.toByte()
        MeshSerialFraming.START2 shouldBe 0xC3.toByte()
        MeshSerialFraming.HEADER_SIZE shouldBe 4
        MeshSerialFraming.MAX_PAYLOAD_SIZE shouldBe 512
    }

    test("encode prepends magic and big-endian length") {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val framed = MeshSerialFraming.encode(payload)
        framed.size shouldBe 4 + payload.size
        framed[0] shouldBe 0x94.toByte()
        framed[1] shouldBe 0xC3.toByte()
        framed[2] shouldBe 0x00.toByte()  // len MSB
        framed[3] shouldBe 0x05.toByte()  // len LSB
        framed.copyOfRange(4, framed.size) shouldBe payload
    }

    test("encode supports the maximum payload size") {
        val payload = ByteArray(MeshSerialFraming.MAX_PAYLOAD_SIZE) { (it and 0xFF).toByte() }
        val framed = MeshSerialFraming.encode(payload)
        framed.size shouldBe 4 + 512
        framed[2] shouldBe 0x02.toByte() // 512 = 0x0200
        framed[3] shouldBe 0x00.toByte()
    }

    test("encode rejects empty and oversized payloads") {
        shouldThrow<IllegalArgumentException> { MeshSerialFraming.encode(ByteArray(0)) }
        shouldThrow<IllegalArgumentException> {
            MeshSerialFraming.encode(ByteArray(MeshSerialFraming.MAX_PAYLOAD_SIZE + 1))
        }
    }

    test("parser round-trips a single frame") {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val parser = MeshSerialFraming.Parser()
        val out = parser.feed(MeshSerialFraming.encode(payload))
        out shouldHaveSize 1
        out[0] shouldBe payload
        parser.bufferedByteCount shouldBe 0
    }

    test("parser concatenates multiple frames in a single buffer") {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6, 7)
        val combined = MeshSerialFraming.encode(a) + MeshSerialFraming.encode(b)
        val out = MeshSerialFraming.Parser().feed(combined)
        out shouldHaveSize 2
        out[0] shouldBe a
        out[1] shouldBe b
    }

    test("parser reassembles a frame split across multiple feed() calls") {
        val payload = ByteArray(64) { (it * 2).toByte() }
        val framed = MeshSerialFraming.encode(payload)
        val parser = MeshSerialFraming.Parser()

        // Split header across boundary too: 1 / 5 / rest.
        parser.feed(framed.copyOfRange(0, 1)).shouldBeEmpty()
        parser.feed(framed.copyOfRange(1, 5)).shouldBeEmpty()
        val tail = parser.feed(framed.copyOfRange(5, framed.size))
        tail shouldHaveSize 1
        tail[0] shouldBe payload
    }

    test("parser resyncs past garbage bytes before a valid frame") {
        val payload = byteArrayOf(99, 98, 97)
        val garbage = byteArrayOf(0x12, 0x34, 0x56, 0x94.toByte(), 0x00 /* false START1 */)
        val framed = MeshSerialFraming.encode(payload)
        val out = MeshSerialFraming.Parser().feed(garbage + framed)
        out shouldHaveSize 1
        out[0] shouldBe payload
    }

    test("parser drops a header with zero length and resyncs") {
        val parser = MeshSerialFraming.Parser()
        // Bad header: magic + len=0
        val bad = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x00, 0x00)
        val good = MeshSerialFraming.encode(byteArrayOf(7, 7, 7))
        val out = parser.feed(bad + good)
        out shouldHaveSize 1
        out[0] shouldBe byteArrayOf(7, 7, 7)
    }

    test("parser drops a header whose length exceeds MAX_PAYLOAD_SIZE") {
        val parser = MeshSerialFraming.Parser()
        // len = 0xFFFF — way over the cap.
        val bad = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val good = MeshSerialFraming.encode(byteArrayOf(1))
        val out = parser.feed(bad + good)
        out shouldHaveSize 1
        out[0] shouldBe byteArrayOf(1)
    }

    test("parser tolerates a false START1 followed by a non-START2 byte") {
        val parser = MeshSerialFraming.Parser()
        val noise = byteArrayOf(0x94.toByte(), 0x00, 0x94.toByte(), 0xFF.toByte())
        val good = MeshSerialFraming.encode(byteArrayOf(42))
        val out = parser.feed(noise + good)
        out shouldHaveSize 1
        out[0] shouldBe byteArrayOf(42)
    }

    test("reset clears any partially-buffered frame") {
        val parser = MeshSerialFraming.Parser()
        parser.feed(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x01, 0x00))
        parser.bufferedByteCount shouldBe 4
        parser.reset()
        parser.bufferedByteCount shouldBe 0
        // And after reset, normal frames still parse:
        val out = parser.feed(MeshSerialFraming.encode(byteArrayOf(1, 2)))
        out shouldHaveSize 1
    }

    test("feed honours explicit offset and length") {
        val payload = byteArrayOf(11, 22, 33)
        val framed = MeshSerialFraming.encode(payload)
        val padded = byteArrayOf(0x00, 0x00) + framed + byteArrayOf(0x77, 0x88.toByte())
        val out = MeshSerialFraming.Parser().feed(padded, offset = 2, length = framed.size)
        out shouldHaveSize 1
        out[0] shouldBe payload
    }

    test("feed with invalid offset/length throws") {
        val parser = MeshSerialFraming.Parser()
        shouldThrow<IllegalArgumentException> { parser.feed(ByteArray(4), offset = -1, length = 2) }
        shouldThrow<IllegalArgumentException> { parser.feed(ByteArray(4), offset = 3, length = 5) }
    }
})


