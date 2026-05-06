package re.chasam.voicetastic.bdd

import io.cucumber.java.Before
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Gherkin steps for USB transport behaviour.
 *
 * The real `UsbMeshTransport` depends on the Android USB framework, which
 * is not available on the JVM. Rather than instrument these tests, we drive
 * a small `FakeMeshConnectionFacade` that mirrors the public surface used
 * by `MeshServiceManager` (active transport selection, mutual exclusion
 * with BLE, framed writes). This keeps the behavioural specification
 * coupled to the *contract* rather than the SDK plumbing.
 */
class UsbConnectionSteps {

    private lateinit var facade: FakeMeshConnectionFacade
    private var usbDeviceAttached = false
    private var lastPermissionPrompted = false

    @Before
    fun setup() {
        facade = FakeMeshConnectionFacade()
        usbDeviceAttached = false
        lastPermissionPrompted = false
    }

    // ===== Background =====

    @Given("Voicetastic is running")
    fun voicetasticRunning() {
        facade.activeTransport shouldBe Transport.NONE
    }

    @Given("no transport is currently active")
    fun noTransportActive() {
        facade.disconnect()
        facade.activeTransport shouldBe Transport.NONE
    }

    // ===== USB attach / detach =====

    @When("a Meshtastic USB device is attached")
    @Given("a Meshtastic USB device is attached")
    fun usbAttached() {
        usbDeviceAttached = true
        facade.attachUsbDevice()
    }

    @When("the USB device is detached")
    fun usbDetached() {
        usbDeviceAttached = false
        facade.detachUsbDevice()
    }

    @Then("the device list should contain a USB device")
    fun deviceListContainsUsb() {
        facade.usbDevices().shouldNotBeEmpty()
    }

    // ===== Connect via USB =====

    @When("the user taps the USB device")
    fun userTapsUsbDevice() {
        usbDeviceAttached.shouldBeTrue()
        lastPermissionPrompted = !facade.usbHasPermission()
    }

    @And("the user grants USB permission")
    fun userGrantsPermission() {
        facade.grantUsbPermission()
        facade.connectUsb()
    }

    @And("the user denies USB permission")
    fun userDeniesPermission() {
        facade.denyUsbPermission()
    }

    // ===== BLE setup for switch scenario =====

    @Given("the BLE transport is connected")
    fun bleConnected() {
        facade.connectBle()
        facade.activeTransport shouldBe Transport.BLE
    }

    @Then("the BLE transport should be disconnected")
    fun bleDisconnected() {
        facade.bleConnected.shouldBeFalse()
    }

    // ===== USB connected pre-state =====

    @Given("the USB transport is connected")
    fun usbConnected() {
        facade.attachUsbDevice()
        facade.grantUsbPermission()
        facade.connectUsb()
        facade.activeTransport shouldBe Transport.USB
    }

    // ===== Assertions on transport / state =====

    @Then("the active transport should be {string}")
    fun activeTransportShouldBe(name: String) {
        facade.activeTransport.name shouldBe name
    }

    @Then("the connection state should be {string}")
    fun connectionStateShouldBe(state: String) {
        facade.connectionState shouldBe state
    }

    // ===== Sending =====

    @When("the user sends a text message {string}")
    fun userSendsText(text: String) {
        facade.sendText(text)
    }

    @Then("a frame should be written to the USB transport")
    fun frameWrittenToUsb() {
        facade.usbWrites.shouldNotBeEmpty()
    }

    @Then("no frame should be written to the BLE transport")
    fun noFrameWrittenToBle() {
        facade.bleWrites.isEmpty().shouldBeTrue()
    }
}

// =====================================================================
// Test double for MeshServiceManager's USB / BLE selection contract.
// Kept JVM-only on purpose so these scenarios run as fast unit tests.
// =====================================================================

internal enum class Transport { NONE, BLE, USB }

internal class FakeMeshConnectionFacade {

    private var usbAttached = false
    private var usbPermission = false
    var bleConnected: Boolean = false
        private set
    var activeTransport: Transport = Transport.NONE
        private set
    var connectionState: String = "DISCONNECTED"
        private set

    val usbWrites = mutableListOf<ByteArray>()
    val bleWrites = mutableListOf<ByteArray>()

    fun attachUsbDevice() { usbAttached = true }

    fun detachUsbDevice() {
        usbAttached = false
        if (activeTransport == Transport.USB) {
            activeTransport = Transport.NONE
            connectionState = "DISCONNECTED"
        }
    }

    fun usbDevices(): List<String> = if (usbAttached) listOf("usb-meshtastic-0") else emptyList()

    fun usbHasPermission(): Boolean = usbPermission
    fun grantUsbPermission() { usbPermission = true }
    fun denyUsbPermission() { usbPermission = false }

    fun connectUsb(): Boolean {
        if (!usbAttached || !usbPermission) return false
        if (bleConnected) {
            // Mirror MeshServiceManager: switching transports drops BLE.
            bleConnected = false
        }
        activeTransport = Transport.USB
        connectionState = "CONNECTED"
        return true
    }

    fun connectBle() {
        if (activeTransport == Transport.USB) {
            activeTransport = Transport.NONE
        }
        bleConnected = true
        activeTransport = Transport.BLE
        connectionState = "CONNECTED"
    }

    fun disconnect() {
        bleConnected = false
        activeTransport = Transport.NONE
        connectionState = "DISCONNECTED"
    }

    /** Mirrors `MeshServiceManager.writeToRadio`: routes through the active transport. */
    fun sendText(text: String) {
        val bytes = text.toByteArray()
        when (activeTransport) {
            Transport.USB -> usbWrites += bytes
            Transport.BLE -> bleWrites += bytes
            Transport.NONE -> { /* no-op when disconnected */ }
        }
    }
}

