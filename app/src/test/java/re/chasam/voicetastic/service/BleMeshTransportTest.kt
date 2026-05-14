package re.chasam.voicetastic.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger

class BleMeshTransportTest : FunSpec({

    test("companion object MTU constant is 512") {
        val field = BleMeshTransport::class.java.getDeclaredField("MTU_SIZE")
        field.isAccessible = true
        field.getInt(null) shouldBe 512
    }

    test("drain queued counter starts at zero") {
        val transport = createTransport()
        val field = BleMeshTransport::class.java.getDeclaredField("drainQueued")
        field.isAccessible = true
        val counter = field.get(transport) as AtomicInteger
        counter.get() shouldBe 0
    }

    test("closed flag starts false") {
        val transport = createTransport()
        val field = BleMeshTransport::class.java.getDeclaredField("closed")
        field.isAccessible = true
        field.getBoolean(transport) shouldBe false
    }

    test("setupCompleted flag starts false") {
        val transport = createTransport()
        val field = BleMeshTransport::class.java.getDeclaredField("setupCompleted")
        field.isAccessible = true
        field.getBoolean(transport) shouldBe false
    }
})

private fun createTransport(): BleMeshTransport {
    val ctx = mockk<android.content.Context>(relaxed = true)
    val device = mockk<android.bluetooth.BluetoothDevice>(relaxed = true)
    return BleMeshTransport(ctx, device)
}
