package re.chasam.voicetastic.bdd

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.cucumber.java.Before
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.ui.settings.ConfigViewModel

class ConfigurationSteps {

    private lateinit var configViewModel: ConfigViewModel
    private lateinit var meshService: MeshServiceManager
    private val voiceConfig = MutableStateFlow(VoiceConfig())
    private var isConnected = true

    @Before
    fun setup() {
        meshService = mockk(relaxed = true)
        isConnected = true
        voiceConfig.value = VoiceConfig()

        every { meshService.connectionState } returns MutableStateFlow("CONNECTED")
        every { meshService.isConnected } returns true
        every { meshService.myNodeId } returns MutableStateFlow("!12345678")
        every { meshService.firmwareVersion } returns MutableStateFlow("2.5.0")
        every { meshService.radioConfig } returns MutableStateFlow(null)
        every { meshService.deviceConfig } returns MutableStateFlow(null)
        every { meshService.positionConfig } returns MutableStateFlow(null)
        every { meshService.powerConfig } returns MutableStateFlow(null)
        every { meshService.networkConfig } returns MutableStateFlow(null)
        every { meshService.displayConfig } returns MutableStateFlow(null)
        every { meshService.bluetoothConfig } returns MutableStateFlow(null)
        every { meshService.owner } returns MutableStateFlow(null)
        every { meshService.channels } returns MutableStateFlow(emptyList())
        every { meshService.moduleConfigs } returns MutableStateFlow(emptyMap())
        every { meshService.setRadioConfig(any()) } returns true
        every { meshService.getRadioConfig() } returns """{"region":"US"}""".toByteArray()
        every { meshService.writeConfig(any()) } returns true
        every { meshService.writeOwner(any()) } returns true
        every { meshService.writeChannel(any()) } returns true
        every { meshService.writeModuleConfig(any()) } returns true
        every { meshService.rebootDevice(any()) } returns true
        every { meshService.factoryReset() } returns true

        configViewModel = ConfigViewModel(meshService, voiceConfig)
    }

    @Given("the Meshtastic service is connected")
    fun serviceConnected() {
        isConnected = true
        every { meshService.connectionState } returns MutableStateFlow("CONNECTED")
        every { meshService.isConnected } returns true
        configViewModel = ConfigViewModel(meshService, voiceConfig)
    }

    @Given("the Meshtastic service is disconnected")
    fun serviceDisconnected() {
        isConnected = false
        every { meshService.connectionState } returns MutableStateFlow("DISCONNECTED")
        every { meshService.isConnected } returns false
        configViewModel = ConfigViewModel(meshService, voiceConfig)
    }

    @Given("the region is set to {string}")
    fun regionIsSet(region: String) {
        configViewModel.setLoraRegion(region)
    }

    @Given("the modem preset is set to {string}")
    fun modemPresetIsSet(preset: String) {
        configViewModel.setLoraModemPreset(preset)
    }

    // --- When steps ---

    @When("I set the region to {string}")
    fun setRegion(region: String) {
        configViewModel.setLoraRegion(region)
    }

    @When("I set the modem preset to {string}")
    fun setModemPreset(preset: String) {
        configViewModel.setLoraModemPreset(preset)
    }

    @When("I set the channel name to {string}")
    fun setChannelName(name: String) {
        // Set channel name on the primary channel (index 0)
        configViewModel.setChannelName(0, name)
    }

    @When("I set the channel PSK to {string}")
    fun setChannelPsk(psk: String) {
        configViewModel.setChannelPskHex(0, psk)
    }

    @When("I apply the device configuration")
    fun applyConfig() {
        configViewModel.applyLoraConfig()
    }

    @When("I set the voice bitrate to {string}")
    fun setVoiceBitrate(bitrateLabel: String) {
        val bitrate = AmrNbBitrate.entries.first { it.label == bitrateLabel }
        configViewModel.setVoiceBitrate(bitrate)
    }

    @When("I set the max recording duration to {int} seconds")
    fun setMaxDuration(seconds: Int) {
        configViewModel.setMaxDuration(seconds)
    }

    @When("I set the chunk timeout to {int} seconds")
    fun setChunkTimeout(seconds: Int) {
        configViewModel.setChunkTimeout(seconds)
    }

    @When("I disable partial play on timeout")
    fun disablePartialPlay() {
        configViewModel.setPartialPlayOnTimeout(false)
    }

    @When("I enable partial play on timeout")
    fun enablePartialPlay() {
        configViewModel.setPartialPlayOnTimeout(true)
    }

    // --- Then steps ---

    @Then("the selected region should be {string}")
    fun regionShouldBe(region: String) {
        configViewModel.loraState.value.region shouldBe region
    }

    @Then("the selected modem preset should be {string}")
    fun modemPresetShouldBe(preset: String) {
        configViewModel.loraState.value.modemPreset shouldBe preset
    }

    @Then("the channel name should be {string}")
    fun channelNameShouldBe(name: String) {
        configViewModel.channelsState.value.firstOrNull()?.name shouldBe name
    }

    @Then("the channel PSK should be {string}")
    fun channelPskShouldBe(psk: String) {
        configViewModel.channelsState.value.firstOrNull()?.pskHex shouldBe psk
    }

    @Then("the config status should indicate success")
    fun configStatusSuccess() {
        configViewModel.configStatus.value shouldContain "sent"
    }

    @Then("the config status should indicate not connected")
    fun configStatusNotConnected() {
        configViewModel.configStatus.value shouldContain "Not connected"
    }

    @Then("the voice config bitrate should be MR475")
    fun bitrateIsMR475() {
        voiceConfig.value.bitrate shouldBe AmrNbBitrate.MR475
    }

    @Then("the voice config max duration should be {int}")
    fun maxDurationShouldBe(seconds: Int) {
        voiceConfig.value.maxDurationSeconds shouldBe seconds
    }

    @Then("the voice config chunk timeout should be {int}")
    fun chunkTimeoutShouldBe(seconds: Int) {
        voiceConfig.value.chunkTimeoutSeconds shouldBe seconds
    }

    @Then("the voice config partial play should be disabled")
    fun partialPlayDisabled() {
        voiceConfig.value.partialPlayOnTimeout.shouldBeFalse()
    }

    @Then("the voice config partial play should be enabled")
    fun partialPlayEnabled() {
        voiceConfig.value.partialPlayOnTimeout.shouldBeTrue()
    }

    // ========================  OWNER  ========================

    @When("I set the owner long name to {string}")
    fun setOwnerLongName(name: String) {
        configViewModel.setOwnerLongName(name)
    }

    @When("I set the owner short name to {string}")
    fun setOwnerShortName(name: String) {
        configViewModel.setOwnerShortName(name)
    }

    @When("I enable HAM licensed mode")
    fun enableLicensedMode() {
        configViewModel.setOwnerIsLicensed(true)
    }

    @When("I apply the owner configuration")
    fun applyOwnerConfig() {
        configViewModel.applyOwner()
    }

    @Given("the owner long name is {string}")
    fun givenOwnerLongName(name: String) {
        configViewModel.setOwnerLongName(name)
    }

    @Given("the owner short name is {string}")
    fun givenOwnerShortName(name: String) {
        configViewModel.setOwnerShortName(name)
    }

    @Then("the owner long name should be {string}")
    fun ownerLongNameShouldBe(name: String) {
        configViewModel.ownerState.value.longName shouldBe name
    }

    @Then("the owner short name should be {string}")
    fun ownerShortNameShouldBe(name: String) {
        configViewModel.ownerState.value.shortName shouldBe name
    }

    @Then("the owner should be marked as licensed")
    fun ownerShouldBeLicensed() {
        configViewModel.ownerState.value.isLicensed.shouldBeTrue()
    }

    // ========================  DEVICE  ========================

    @When("I set the device role to {string}")
    fun setDeviceRole(role: String) {
        configViewModel.setDeviceRole(role)
    }

    @When("I set the rebroadcast mode to {string}")
    fun setRebroadcastMode(mode: String) {
        configViewModel.setDeviceRebroadcastMode(mode)
    }

    @When("I set the node info broadcast interval to {int} seconds")
    fun setNodeInfoBroadcastInterval(seconds: Int) {
        configViewModel.setDeviceNodeInfoBroadcastSecs(seconds)
    }

    @When("I apply the device config")
    fun applyDeviceConfig() {
        configViewModel.applyDeviceConfig()
    }

    @Then("the device role should be {string}")
    fun deviceRoleShouldBe(role: String) {
        configViewModel.deviceState.value.role shouldBe role
    }

    @Then("the rebroadcast mode should be {string}")
    fun rebroadcastModeShouldBe(mode: String) {
        configViewModel.deviceState.value.rebroadcastMode shouldBe mode
    }

    @Then("the node info broadcast interval should be {int}")
    fun nodeInfoBroadcastIntervalShouldBe(seconds: Int) {
        configViewModel.deviceState.value.nodeInfoBroadcastSecs shouldBe seconds
    }

    // ========================  POSITION  ========================

    @When("I enable GPS")
    fun enableGps() {
        configViewModel.setPositionGpsEnabled(true)
    }

    @When("I enable fixed position")
    fun enableFixedPosition() {
        configViewModel.setPositionFixed(true)
    }

    @When("I set the position broadcast interval to {int} seconds")
    fun setPositionBroadcastInterval(seconds: Int) {
        configViewModel.setPositionBroadcastSecs(seconds)
    }

    @When("I enable smart position broadcast")
    fun enableSmartPositionBroadcast() {
        configViewModel.setPositionSmartEnabled(true)
    }

    @When("I apply the position config")
    fun applyPositionConfig() {
        configViewModel.applyPositionConfig()
    }

    @Then("GPS should be enabled")
    fun gpsShouldBeEnabled() {
        configViewModel.positionState.value.gpsEnabled.shouldBeTrue()
    }

    @Then("fixed position should be enabled")
    fun fixedPositionShouldBeEnabled() {
        configViewModel.positionState.value.fixedPosition.shouldBeTrue()
    }

    @Then("the position broadcast interval should be {int}")
    fun positionBroadcastIntervalShouldBe(seconds: Int) {
        configViewModel.positionState.value.positionBroadcastSecs shouldBe seconds
    }

    @Then("smart position broadcast should be enabled")
    fun smartPositionBroadcastShouldBeEnabled() {
        configViewModel.positionState.value.positionBroadcastSmartEnabled.shouldBeTrue()
    }

    // ========================  POWER  ========================

    @When("I enable power saving")
    fun enablePowerSaving() {
        configViewModel.setPowerSaving(true)
    }

    @When("I enable shutdown on power loss")
    fun enableShutdownOnPowerLoss() {
        configViewModel.setPowerShutdownOnPowerLoss(true)
    }

    @When("I set the on-battery shutdown timeout to {int} seconds")
    fun setOnBatteryShutdownTimeout(seconds: Int) {
        configViewModel.setPowerShutdownAfterSecs(seconds)
    }

    @When("I apply the power config")
    fun applyPowerConfig() {
        configViewModel.applyPowerConfig()
    }

    @Then("power saving should be enabled")
    fun powerSavingShouldBeEnabled() {
        configViewModel.powerState.value.isPowerSaving.shouldBeTrue()
    }

    @Then("shutdown on power loss should be enabled")
    fun shutdownOnPowerLossShouldBeEnabled() {
        configViewModel.powerState.value.shutdownOnPowerLoss.shouldBeTrue()
    }

    @Then("the on-battery shutdown timeout should be {int}")
    fun onBatteryShutdownTimeoutShouldBe(seconds: Int) {
        configViewModel.powerState.value.onBatteryShutdownAfterSecs shouldBe seconds
    }

    // ========================  NETWORK  ========================

    @When("I enable WiFi")
    fun enableWifi() {
        configViewModel.setNetworkWifiEnabled(true)
    }

    @When("I set the WiFi SSID to {string}")
    fun setWifiSsid(ssid: String) {
        configViewModel.setNetworkWifiSsid(ssid)
    }

    @When("I set the WiFi PSK to {string}")
    fun setWifiPsk(psk: String) {
        configViewModel.setNetworkWifiPsk(psk)
    }

    @When("I set the NTP server to {string}")
    fun setNtpServer(server: String) {
        configViewModel.setNetworkNtpServer(server)
    }

    @When("I apply the network config")
    fun applyNetworkConfig() {
        configViewModel.applyNetworkConfig()
    }

    @Then("WiFi should be enabled")
    fun wifiShouldBeEnabled() {
        configViewModel.networkState.value.wifiEnabled.shouldBeTrue()
    }

    @Then("the WiFi SSID should be {string}")
    fun wifiSsidShouldBe(ssid: String) {
        configViewModel.networkState.value.wifiSsid shouldBe ssid
    }

    @Then("the WiFi PSK should be {string}")
    fun wifiPskShouldBe(psk: String) {
        configViewModel.networkState.value.wifiPsk shouldBe psk
    }

    @Then("the NTP server should be {string}")
    fun ntpServerShouldBe(server: String) {
        configViewModel.networkState.value.ntpServer shouldBe server
    }

    // ========================  DISPLAY  ========================

    @When("I set the screen on time to {int} seconds")
    fun setScreenOnTime(seconds: Int) {
        configViewModel.setDisplayScreenOnSecs(seconds)
    }

    @When("I set the GPS format to {string}")
    fun setGpsFormat(format: String) {
        configViewModel.setDisplayGpsFormat(format)
    }

    @When("I enable flip screen")
    fun enableFlipScreen() {
        configViewModel.setDisplayFlipScreen(true)
    }

    @When("I apply the display config")
    fun applyDisplayConfig() {
        configViewModel.applyDisplayConfig()
    }

    @Then("the screen on time should be {int}")
    fun screenOnTimeShouldBe(seconds: Int) {
        configViewModel.displayState.value.screenOnSecs shouldBe seconds
    }

    @Then("the GPS format should be {string}")
    fun gpsFormatShouldBe(format: String) {
        configViewModel.displayState.value.gpsFormat shouldBe format
    }

    @Then("flip screen should be enabled")
    fun flipScreenShouldBeEnabled() {
        configViewModel.displayState.value.flipScreen.shouldBeTrue()
    }

    // ========================  BLUETOOTH  ========================

    @When("I enable bluetooth")
    fun enableBluetooth() {
        configViewModel.setBluetoothEnabled(true)
    }

    @When("I set the bluetooth pairing mode to {string}")
    fun setBluetoothPairingMode(mode: String) {
        configViewModel.setBluetoothMode(mode)
    }

    @When("I set the bluetooth fixed PIN to {int}")
    fun setBluetoothFixedPin(pin: Int) {
        configViewModel.setBluetoothFixedPin(pin)
    }

    @When("I apply the bluetooth config")
    fun applyBluetoothConfig() {
        configViewModel.applyBluetoothConfig()
    }

    @Then("bluetooth should be enabled")
    fun bluetoothShouldBeEnabled() {
        configViewModel.bluetoothState.value.enabled.shouldBeTrue()
    }

    @Then("the bluetooth pairing mode should be {string}")
    fun bluetoothPairingModeShouldBe(mode: String) {
        configViewModel.bluetoothState.value.mode shouldBe mode
    }

    @Then("the bluetooth fixed PIN should be {int}")
    fun bluetoothFixedPinShouldBe(pin: Int) {
        configViewModel.bluetoothState.value.fixedPin shouldBe pin
    }

    // ========================  DEVICE ACTIONS  ========================

    @When("I reboot the device")
    fun rebootDevice() {
        configViewModel.rebootDevice()
    }

    @When("I factory reset the device")
    fun factoryResetDevice() {
        configViewModel.factoryReset()
    }
}
