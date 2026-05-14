package re.chasam.voicetastic.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class DeviceDiscoveryManagerTest : FunSpec({

    test("initial state is not scanning") {
        val ctx = mockk<android.content.Context>(relaxed = true)
        val manager = DeviceDiscoveryManager(ctx)
        manager.isScanning.value shouldBe false
        manager.discoveredBleDevices.value shouldBe emptyList()
        manager.destroy()
    }

    test("destroy stops scanning and is idempotent") {
        val ctx = mockk<android.content.Context>(relaxed = true)
        val manager = DeviceDiscoveryManager(ctx)
        manager.destroy()
        manager.isScanning.value shouldBe false
        manager.discoveredBleDevices.value shouldBe emptyList()
        manager.destroy()
    }

    test("destroy does not throw when called multiple times") {
        val ctx = mockk<android.content.Context>(relaxed = true)
        val manager = DeviceDiscoveryManager(ctx)
        manager.destroy()
        manager.destroy()
        manager.destroy()
    }

    test("startBleScan does not crash when bluetooth is unavailable") {
        val ctx = mockk<android.content.Context>(relaxed = true)
        val manager = DeviceDiscoveryManager(ctx)
        manager.startBleScan()
        manager.isScanning.value shouldBe false
        manager.destroy()
    }
})
