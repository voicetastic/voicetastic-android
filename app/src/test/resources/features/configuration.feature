Feature: Device Configuration
  As a Meshtastic user
  I want to configure my Meshtastic device parameters
  So that I can optimize the mesh network for my environment

  Background:
    Given the Meshtastic service is connected

  Scenario: Set the region
    When I set the region to "EU_868"
    Then the selected region should be "EU_868"

  Scenario: Set the modem preset
    When I set the modem preset to "LONG_SLOW"
    Then the selected modem preset should be "LONG_SLOW"

  Scenario: Set channel name and PSK
    When I set the channel name to "TestChannel"
    And I set the channel PSK to "AQ=="
    Then the channel name should be "TestChannel"
    And the channel PSK should be "AQ=="

  Scenario: Apply device config when connected
    Given the region is set to "US"
    And the modem preset is set to "SHORT_FAST"
    When I apply the device configuration
    Then the config status should indicate success

  Scenario: Apply device config when disconnected
    Given the Meshtastic service is disconnected
    When I apply the device configuration
    Then the config status should indicate not connected

  Scenario: Configure voice bitrate
    When I set the voice bitrate to "4.75 kbps"
    Then the voice config bitrate should be MR475

  Scenario: Configure max recording duration
    When I set the max recording duration to 30 seconds
    Then the voice config max duration should be 30

  Scenario: Configure chunk timeout
    When I set the chunk timeout to 45 seconds
    Then the voice config chunk timeout should be 45

  Scenario: Toggle partial play on timeout
    When I disable partial play on timeout
    Then the voice config partial play should be disabled
    When I enable partial play on timeout
    Then the voice config partial play should be enabled

  Scenario: Max duration is clamped to valid range
    When I set the max recording duration to 100 seconds
    Then the voice config max duration should be 60
    When I set the max recording duration to 0 seconds
    Then the voice config max duration should be 1

  Scenario: Set owner long name
    When I set the owner long name to "My Node"
    Then the owner long name should be "My Node"

  Scenario: Set owner short name
    When I set the owner short name to "MN"
    Then the owner short name should be "MN"

  Scenario: Set owner licensed mode
    When I enable HAM licensed mode
    Then the owner should be marked as licensed

  Scenario: Apply owner configuration
    Given the owner long name is "Test Node"
    And the owner short name is "TN"
    When I apply the owner configuration
    Then the config status should indicate success

  Scenario: Set device role
    When I set the device role to "ROUTER"
    Then the device role should be "ROUTER"

  Scenario: Set device rebroadcast mode
    When I set the rebroadcast mode to "LOCAL_ONLY"
    Then the rebroadcast mode should be "LOCAL_ONLY"

  Scenario: Set device node info broadcast interval
    When I set the node info broadcast interval to 900 seconds
    Then the node info broadcast interval should be 900

  Scenario: Apply device configuration
    When I set the device role to "CLIENT"
    And I apply the device config
    Then the config status should indicate success

  Scenario: Set position GPS enabled
    When I enable GPS
    Then GPS should be enabled

  Scenario: Set position fixed
    When I enable fixed position
    Then fixed position should be enabled

  Scenario: Set position broadcast interval
    When I set the position broadcast interval to 120 seconds
    Then the position broadcast interval should be 120

  Scenario: Set position smart broadcast
    When I enable smart position broadcast
    Then smart position broadcast should be enabled

  Scenario: Apply position configuration
    When I set the position broadcast interval to 300 seconds
    And I apply the position config
    Then the config status should indicate success

  Scenario: Set power saving mode
    When I enable power saving
    Then power saving should be enabled

  Scenario: Set shutdown on power loss
    When I enable shutdown on power loss
    Then shutdown on power loss should be enabled

  Scenario: Set on-battery shutdown timeout
    When I set the on-battery shutdown timeout to 3600 seconds
    Then the on-battery shutdown timeout should be 3600

  Scenario: Apply power configuration
    When I enable power saving
    And I apply the power config
    Then the config status should indicate success

  Scenario: Set network WiFi enabled
    When I enable WiFi
    Then WiFi should be enabled

  Scenario: Set network WiFi SSID
    When I set the WiFi SSID to "MyNetwork"
    Then the WiFi SSID should be "MyNetwork"

  Scenario: Set network WiFi PSK
    When I set the WiFi PSK to "secret123"
    Then the WiFi PSK should be "secret123"

  Scenario: Set network NTP server
    When I set the NTP server to "pool.ntp.org"
    Then the NTP server should be "pool.ntp.org"

  Scenario: Apply network configuration
    When I enable WiFi
    And I set the WiFi SSID to "MeshNet"
    And I apply the network config
    Then the config status should indicate success

  Scenario: Set display screen on time
    When I set the screen on time to 30 seconds
    Then the screen on time should be 30

  Scenario: Set display GPS format
    When I set the GPS format to "DMS"
    Then the GPS format should be "DMS"

  Scenario: Set display flip screen
    When I enable flip screen
    Then flip screen should be enabled

  Scenario: Apply display configuration
    When I set the screen on time to 60 seconds
    And I apply the display config
    Then the config status should indicate success

  Scenario: Set bluetooth enabled
    When I enable bluetooth
    Then bluetooth should be enabled

  Scenario: Set bluetooth pairing mode
    When I set the bluetooth pairing mode to "FIXED_PIN"
    Then the bluetooth pairing mode should be "FIXED_PIN"

  Scenario: Set bluetooth fixed PIN
    When I set the bluetooth fixed PIN to 123456
    Then the bluetooth fixed PIN should be 123456

  Scenario: Apply bluetooth configuration
    When I enable bluetooth
    And I apply the bluetooth config
    Then the config status should indicate success

  Scenario: Reboot device
    When I reboot the device
    Then the config status should indicate success

  Scenario: Factory reset device
    When I factory reset the device
    Then the config status should indicate success

  Scenario: Apply owner config when disconnected
    Given the Meshtastic service is disconnected
    When I apply the owner configuration
    Then the config status should indicate not connected

