Feature: Text Messaging
  As a Meshtastic user
  I want to send and receive text messages over the mesh network
  So that I can communicate with other nodes

  Background:
    Given the Meshtastic service is connected
    And my node ID is "!mynode01"

  Scenario: Send a broadcast text message
    Given no destination node is selected
    When I send a text message "Hello mesh!"
    Then the message should be sent to broadcast
    And the message "Hello mesh!" should appear in the chat as outgoing

  Scenario: Send a direct text message to a specific node
    Given the destination node "!target01" is selected
    When I send a text message "Hey there!"
    Then the message should be sent to "!target01"
    And the message "Hey there!" should appear in the chat as outgoing

  Scenario: Receive a text message
    When a text message "Hi from mesh" is received from "!remote01"
    Then the message "Hi from mesh" should appear in the chat as incoming
    And the message should show sender "!remote01"

  Scenario: Send empty message is ignored
    When I send a text message ""
    Then no message should be sent

  Scenario: Send message when disconnected fails
    Given the Meshtastic service is disconnected
    When I send a text message "This won't work"
    Then no message should be sent

  Scenario: Channel conversation shows broadcast messages
    Given no destination node is selected
    When a broadcast message "Hello all" is received from "!remote01"
    Then the channel conversation should contain "Hello all"

  Scenario: Channel conversation shows outgoing broadcast messages
    Given no destination node is selected
    When I send a text message "Broadcast from me"
    Then the channel conversation should contain "Broadcast from me"

  Scenario: Channel conversation excludes incoming DMs addressed to me
    Given no destination node is selected
    When a direct message "Private hello" is received from "!remote01" to "!mynode01"
    Then the channel conversation should not contain "Private hello"

  Scenario: Channel conversation excludes outgoing DMs
    Given the destination node "!remote01" is selected
    When I send a text message "DM to remote"
    Given no destination node is selected
    Then the channel conversation should not contain "DM to remote"

  Scenario: DM conversation shows incoming DMs addressed to me from selected node
    Given the destination node "!remote01" is selected
    When a direct message "Hey you" is received from "!remote01" to "!mynode01"
    Then the DM conversation with "!remote01" should contain "Hey you"

  Scenario: DM conversation excludes broadcast from selected node
    Given the destination node "!remote01" is selected
    When a broadcast message "Hello everyone" is received from "!remote01"
    Then the DM conversation with "!remote01" should not contain "Hello everyone"

  Scenario: DM conversation excludes messages from other nodes
    Given the destination node "!remote01" is selected
    When a direct message "Not for me" is received from "!remote02" to "!mynode01"
    Then the DM conversation with "!remote01" should not contain "Not for me"

  Scenario: Sent DM appears in DM conversation
    Given the destination node "!remote01" is selected
    When I send a text message "Direct to you"
    Then the DM conversation with "!remote01" should contain "Direct to you"

  Scenario: Messages on different channels are separated
    Given no destination node is selected
    And the selected channel is 0
    When a broadcast message "On channel 0" is received from "!remote01" on channel 0
    And a broadcast message "On channel 1" is received from "!remote01" on channel 1
    Then the channel conversation should contain "On channel 0"
    And the channel conversation should not contain "On channel 1"

  Scenario: Switching channel shows correct messages
    Given no destination node is selected
    When a broadcast message "Primary msg" is received from "!remote01" on channel 0
    And a broadcast message "Secondary msg" is received from "!remote01" on channel 1
    And the selected channel is 1
    Then the channel conversation should contain "Secondary msg"
    And the channel conversation should not contain "Primary msg"

  Scenario: Send message on selected channel
    Given no destination node is selected
    And the selected channel is 2
    When I send a text message "Channel 2 message"
    Then the message should be sent on channel 2

