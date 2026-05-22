package re.chasam.voicetastic.service

import android.bluetooth.BluetoothDevice
import android.hardware.usb.UsbDevice
import com.geeksville.mesh.MeshProtos
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import re.chasam.voicetastic.model.MeshNode

/**
 * Public surface of [MeshServiceManager] expressed as an interface so
 * ViewModels and screens can be tested against a fake implementation
 * without instantiating the real BLE/USB/Rust stack.
 *
 * The interface intentionally mirrors the existing public API one-for-
 * one — the goal here is a test seam, not a refactor of the underlying
 * responsibilities. A subsequent change can split this into narrower
 * role-based interfaces (sender vs. config-source vs. inbox) if that
 * becomes useful, without rewriting call sites that already only see
 * the parts of this interface they need.
 */
interface MeshFacade {

    // ----- Connection state -----

    val connectionState: StateFlow<String>
    val activeTransport: StateFlow<TransportType>
    val isConnected: Boolean
    val myNodeId: StateFlow<String?>
    val firmwareVersion: StateFlow<String?>
    val nodes: StateFlow<List<MeshNode>>
    val isNodeScanInProgress: StateFlow<Boolean>

    // ----- Discovery -----

    val discoveredDevices: StateFlow<List<BluetoothDevice>>
    val isScanning: StateFlow<Boolean>
    val usbState: StateFlow<UsbMeshTransport.State>
    val usbConnectedDevice: StateFlow<UsbDevice?>
    val usbErrors: SharedFlow<String>

    fun startScan()
    fun stopScan()
    fun discoverUsbDevices(): List<UsbSerialDriver>
    fun usbHasPermission(device: UsbDevice): Boolean
    fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit)

    // ----- Connection control -----

    fun connect(device: BluetoothDevice)
    fun connectUsb(driver: UsbSerialDriver): Boolean
    fun disconnect()
    fun disconnectUsb()
    fun onUsbDeviceDetached(device: UsbDevice)
    fun requestNodeInfo(): Boolean

    // ----- Inbound traffic -----

    val incomingTextMessages: SharedFlow<IncomingText>
    val incomingDataMessages: SharedFlow<IncomingData>

    // ----- Per-section config flows -----

    val radioConfig: StateFlow<MeshProtos.Config.LoRaConfig?>
    val deviceConfig: StateFlow<MeshProtos.Config.DeviceConfig?>
    val positionConfig: StateFlow<MeshProtos.Config.PositionConfig?>
    val myPosition: StateFlow<MeshProtos.Position?>
    val powerConfig: StateFlow<MeshProtos.Config.PowerConfig?>
    val networkConfig: StateFlow<MeshProtos.Config.NetworkConfig?>
    val displayConfig: StateFlow<MeshProtos.Config.DisplayConfig?>
    val bluetoothConfig: StateFlow<MeshProtos.Config.BluetoothConfig?>
    val channels: StateFlow<List<MeshProtos.Channel>>
    val owner: StateFlow<MeshProtos.User?>
    val moduleConfigs: StateFlow<Map<String, MeshProtos.ModuleConfig>>
    val configComplete: SharedFlow<Int>

    // ----- Outbound traffic -----

    fun sendText(text: String, destination: String? = null, channel: Int = 0): Boolean
    fun sendData(
        data: ByteArray,
        portNum: Int,
        destination: String? = null,
        channel: Int = 0,
        wantAck: Boolean = false,
        wantResponse: Boolean = false,
    ): Boolean

    /** Lazily-resolved Rust voice sender. Returns null when not connected. */
    fun voiceSender(): uniffi.voicetastic.VoiceSender?

    fun refreshConfig()

    // ----- Admin writes (local node only) -----

    fun writeConfig(config: MeshProtos.Config): Boolean
    fun writeModuleConfig(moduleConfig: MeshProtos.ModuleConfig): Boolean
    fun writeChannel(channel: MeshProtos.Channel): Boolean
    fun writeOwner(user: MeshProtos.User): Boolean
    fun setFixedPosition(position: MeshProtos.Position): Boolean
    fun removeFixedPosition(): Boolean
    fun rebootDevice(seconds: Int = 5): Boolean
    fun factoryReset(): Boolean

    // ----- Lifecycle -----

    fun destroy()
}
