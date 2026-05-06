Feature: USB Device Connection
  As a Voicetastic user with a Meshtastic node connected via USB
  I want the app to talk to the device over the USB cable
  So that I can use Meshtastic without Bluetooth being available

  Background:
    Given Voicetastic is running
    And no transport is currently active

  Scenario: USB device appears in the device list when attached
    When a Meshtastic USB device is attached
    Then the device list should contain a USB device

  Scenario: User grants permission and the USB transport connects
    Given a Meshtastic USB device is attached
    When the user taps the USB device
    And the user grants USB permission
    Then the active transport should be "USB"
    And the connection state should be "CONNECTED"

  Scenario: User denies USB permission so the connection fails
    Given a Meshtastic USB device is attached
    When the user taps the USB device
    And the user denies USB permission
    Then the active transport should be "NONE"
    And the connection state should be "DISCONNECTED"

  Scenario: USB device unplugged while connected drops the link
    Given the USB transport is connected
    When the USB device is detached
    Then the active transport should be "NONE"
    And the connection state should be "DISCONNECTED"

  Scenario: Switching from BLE to USB drops the BLE link first
    Given the BLE transport is connected
    And a Meshtastic USB device is attached
    When the user taps the USB device
    And the user grants USB permission
    Then the active transport should be "USB"
    And the BLE transport should be disconnected

  Scenario: Outgoing text messages route through the active USB transport
    Given the USB transport is connected
    When the user sends a text message "hello over usb"
    Then a frame should be written to the USB transport
    And no frame should be written to the BLE transport

