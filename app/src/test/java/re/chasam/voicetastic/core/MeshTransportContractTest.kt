package re.chasam.voicetastic.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Contract test exercising the [MeshTransport] seam end-to-end via
 * [FakeMeshTransport]. When the upstream Rust `voicetastic-core`
 * integration lands, the same contract test should pass against the JNI/
 * UniFFI-backed Android transport adapter.
 */
class MeshTransportContractTest : FunSpec({

    test("write/inbound/disconnect happy path") {
        runTest {
            val t = FakeMeshTransport()

            t.writeToRadio(byteArrayOf(1, 2, 3))
            t.writeToRadio(byteArrayOf(4))
            t.written.size shouldBe 2
            t.written[0].toList() shouldContainExactly listOf<Byte>(1, 2, 3)

            // Inbound flow: push two payloads then close.
            t.pushInbound(byteArrayOf(0x10))
            t.pushInbound(byteArrayOf(0x20, 0x21))
            t.disconnect()

            val collected = t.inbound.toList()
            collected.size shouldBe 2
            collected[0].toList() shouldContainExactly listOf<Byte>(0x10)
            collected[1].toList() shouldContainExactly listOf<Byte>(0x20, 0x21)
            t.isDisconnected.shouldBeTrue()
        }
    }

    test("writing to a disconnected transport raises TransportException") {
        runTest {
            val t = FakeMeshTransport()
            t.isDisconnected.shouldBeFalse()
            t.disconnect()
            shouldThrow<TransportException> { t.writeToRadio(byteArrayOf(0)) }
        }
    }

    test("disconnect is idempotent") {
        runTest {
            val t = FakeMeshTransport()
            t.disconnect()
            t.disconnect()
            t.isDisconnected.shouldBeTrue()
        }
    }

    test("inbound flow delivers the first pushed payload to the first collector") {
        runTest {
            val t = FakeMeshTransport()
            t.pushInbound(byteArrayOf(0xAB.toByte()))
            t.disconnect()
            val firstFrame = t.inbound.first()
            firstFrame.toList() shouldContainExactly listOf<Byte>(0xAB.toByte())
        }
    }
})



