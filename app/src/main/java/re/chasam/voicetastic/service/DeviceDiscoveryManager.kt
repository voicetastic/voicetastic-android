package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceDiscovery"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _discoveredBleDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredBleDevices: StateFlow<List<BluetoothDevice>> = _discoveredBleDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanTimeoutJob: Job? = null

    @Volatile private var destroyed = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val current = _discoveredBleDevices.value
            if (current.none { it.address == device.address }) {
                _discoveredBleDevices.value = current + device
                Log.i(TAG, "Found Meshtastic device: ${device.name ?: device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager
        return manager?.adapter
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (destroyed) return
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Log.w(TAG, "startBleScan: no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "startBleScan: Bluetooth disabled")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "startBleScan: bluetoothLeScanner unavailable")
            return
        }
        if (_isScanning.value) {
            Log.d(TAG, "startBleScan: already scanning")
            return
        }
        _discoveredBleDevices.value = emptyList()
        _isScanning.value = true

        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(MeshtasticBle.SERVICE_UUID))
            .build()
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan failed to start", e)
            _isScanning.value = false
            return
        }

        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (isActive && !destroyed) {
                stopBleScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (!_isScanning.value) return
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopBleScan threw", e)
        }
        _isScanning.value = false
        Log.i(TAG, "BLE scan stopped")
    }

    fun discoverUsbDevices(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(
            context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        )

    fun destroy() {
        destroyed = true
        stopBleScan()
        scope.cancel()
    }
}
