Feature: Voice Messaging
  As a Meshtastic user
  I want to send and receive voice messages over the mesh network
  So that I can communicate hands-free with other nodes

  Background:
    Given the Meshtastic service is connected
    And my node ID is "!mynode01"
    And the voice bitrate is set to "7.95 kbps"
    And the max recording duration is 20 seconds

  Scenario: Record and send a voice message as broadcast
    Given no destination node is selected
    When I start recording a voice message
    And I stop recording after 3 seconds
    Then the recording should produce AMR-NB audio data
    And the audio should be chunked into packets of at most 231 bytes each
    And each chunk should have a 6-byte header with message ID, chunk index, total chunks, and bitrate
    And the chunks should be sent via the PRIVATE_APP port

  Scenario: Voice message chunking preserves audio data
    Given I have AMR-NB audio data of 500 bytes
    When the audio is chunked with message ID 42
    Then it should produce 3 chunks
    And reassembling the chunk payloads should produce the original 500 bytes

  Scenario: Receive a complete voice message
    Given a voice message with ID 100 has 3 chunks
    When all 3 chunks arrive from "!sender01" in order
    Then a complete voice message should be assembled
    And the voice message should be marked as complete
    And the voice message should be playable

  Scenario: Receive voice message chunks out of order
    Given a voice message with ID 200 has 3 chunks
    When chunk 2 arrives from "!sender02"
    And chunk 0 arrives from "!sender02"
    And chunk 1 arrives from "!sender02"
    Then a complete voice message should be assembled
    And the voice message should be marked as complete

  Scenario: Partial voice message on timeout
    Given a voice message with ID 300 has 5 chunks
    And the chunk timeout is set to 2 seconds
    When only chunks 0 and 2 arrive from "!sender03"
    And I wait for the chunk timeout to expire
    Then a partial voice message should be assembled
    And the voice message should have 2 received chunks out of 5 total
    And missing chunks should be replaced with silence frames

  Scenario: Max recording duration is enforced
    Given the max recording duration is 5 seconds
    When I start recording a voice message
    And 5 seconds elapse
    Then the recording should stop automatically

  Scenario: Configurable bitrate is encoded in chunks
    Given the voice bitrate is set to "4.75 kbps"
    And I have AMR-NB audio data of 100 bytes
    When the audio is chunked with message ID 10
    Then each chunk header should contain bitrate index 0

  Scenario: Duplicate chunks are ignored during assembly
    Given a voice message with ID 400 has 1 chunk
    When the same chunk arrives 3 times from "!sender04"
    Then exactly 1 voice message should be assembled
    And it should be marked as complete

