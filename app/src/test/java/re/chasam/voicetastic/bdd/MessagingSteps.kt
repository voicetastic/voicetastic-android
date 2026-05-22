package re.chasam.voicetastic.bdd

import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import re.chasam.voicetastic.model.Message
import re.chasam.voicetastic.service.MeshFacade
import re.chasam.voicetastic.service.Portnums

class MessagingSteps {

    private lateinit var meshService: MeshFacade
    private val sentPackets = mutableListOf<SentPacket>()
    private val messages = mutableListOf<Message>()
    private var myNodeId: String = ""
    private var selectedDestination: String? = null
    private var selectedChannel: Int = 0
    private var isConnected = true

    data class SentPacket(val text: String?, val data: ByteArray?, val destination: String?, val portNum: Int, val channel: Int = 0)

    @Before
    fun setup() {
        sentPackets.clear()
        messages.clear()
        selectedDestination = null
        selectedChannel = 0
        isConnected = true

        meshService = mockk(relaxed = true)
        every { meshService.connectionState } returns MutableStateFlow("CONNECTED")
        every { meshService.myNodeId } returns MutableStateFlow("!mynode01")
        every { meshService.isConnected } returns true
        every { meshService.sendText(any(), any(), any()) } answers {
            if (!isConnected) false
            else {
                val text = firstArg<String>()
                val dest = secondArg<String?>()
                val ch = thirdArg<Int>()
                sentPackets.add(SentPacket(text, null, dest, Portnums.TEXT_MESSAGE_APP, ch))
                true
            }
        }
    }

    @Given("the Meshtastic service is connected")
    fun serviceConnected() {
        isConnected = true
        every { meshService.connectionState } returns MutableStateFlow("CONNECTED")
        every { meshService.isConnected } returns true
    }

    @Given("the Meshtastic service is disconnected")
    fun serviceDisconnected() {
        isConnected = false
        every { meshService.connectionState } returns MutableStateFlow("DISCONNECTED")
        every { meshService.isConnected } returns false
    }

    @Given("my node ID is {string}")
    fun setMyNodeId(nodeId: String) {
        myNodeId = nodeId
        every { meshService.myNodeId } returns MutableStateFlow(nodeId)
    }

    @Given("no destination node is selected")
    fun noDestinationSelected() {
        selectedDestination = null
    }

    @Given("the destination node {string} is selected")
    fun destinationSelected(nodeId: String) {
        selectedDestination = nodeId
    }

    @When("I send a text message {string}")
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        if (!isConnected) return
        meshService.sendText(text, selectedDestination, selectedChannel)
        if (isConnected && text.isNotBlank()) {
            messages.add(
                Message(
                    id = messages.size + 1,
                    text = text,
                    from = myNodeId,
                    to = selectedDestination ?: "broadcast",
                    isOutgoing = true,
                    channel = selectedChannel
                )
            )
        }
    }

    @When("a text message {string} is received from {string}")
    fun receiveTextMessage(text: String, from: String) {
        messages.add(
            Message(
                id = messages.size + 1,
                text = text,
                from = from,
                to = myNodeId,
                isOutgoing = false
            )
        )
    }

    @Then("the message should be sent to broadcast")
    fun messageSentToBroadcast() {
        sentPackets.shouldNotBeEmpty()
        sentPackets.last().destination shouldBe null
    }

    @Then("the message should be sent to {string}")
    fun messageSentToNode(nodeId: String) {
        sentPackets.shouldNotBeEmpty()
        sentPackets.last().destination shouldBe nodeId
    }

    @Then("the message {string} should appear in the chat as outgoing")
    fun messageAppearsOutgoing(text: String) {
        messages.any { it.text == text && it.isOutgoing }.shouldBeTrue()
    }

    @Then("the message {string} should appear in the chat as incoming")
    fun messageAppearsIncoming(text: String) {
        messages.any { it.text == text && !it.isOutgoing }.shouldBeTrue()
    }

    @Then("the message should show sender {string}")
    fun messageShowsSender(sender: String) {
        messages.any { it.from == sender }.shouldBeTrue()
    }

    @Then("no message should be sent")
    fun noMessageSent() {
        sentPackets shouldHaveSize 0
    }

    // ===== Channel and conversation filtering steps =====

    @Given("the selected channel is {int}")
    fun setSelectedChannel(channel: Int) {
        selectedChannel = channel
    }

    @When("a broadcast message {string} is received from {string}")
    fun receiveBroadcastMessage(text: String, from: String) {
        messages.add(
            Message(
                id = messages.size + 1,
                text = text,
                from = from,
                to = "broadcast",
                isOutgoing = false,
                channel = selectedChannel
            )
        )
    }

    @When("a direct message {string} is received from {string} to {string}")
    fun receiveDirectMessage(text: String, from: String, to: String) {
        messages.add(
            Message(
                id = messages.size + 1,
                text = text,
                from = from,
                to = to,
                isOutgoing = false,
                channel = selectedChannel
            )
        )
    }

    @When("a broadcast message {string} is received from {string} on channel {int}")
    fun receiveBroadcastMessageOnChannel(text: String, from: String, channel: Int) {
        messages.add(
            Message(
                id = messages.size + 1,
                text = text,
                from = from,
                to = "broadcast",
                isOutgoing = false,
                channel = channel
            )
        )
    }

    @Then("the channel conversation should contain {string}")
    fun channelConversationContains(text: String) {
        messagesIn("broadcast").map { it.text } shouldContain text
    }

    @Then("the channel conversation should not contain {string}")
    fun channelConversationNotContains(text: String) {
        messagesIn("broadcast").map { it.text } shouldNotContain text
    }

    @Then("the DM conversation with {string} should contain {string}")
    fun dmConversationContains(nodeId: String, text: String) {
        messagesIn(nodeId).map { it.text } shouldContain text
    }

    @Then("the DM conversation with {string} should not contain {string}")
    fun dmConversationNotContains(nodeId: String, text: String) {
        messagesIn(nodeId).map { it.text } shouldNotContain text
    }

    /**
     * Mirrors MessagingViewModel.computeContactKey:
     *   - to == "broadcast" → "broadcast"
     *   - outgoing          → destination node
     *   - incoming to me    → sender
     *   - overheard         → sender
     */
    private fun contactKeyOf(msg: Message): String = when {
        msg.to == "broadcast" -> "broadcast"
        msg.isOutgoing -> msg.to
        msg.to == myNodeId -> msg.from
        else -> msg.from
    }

    private fun messagesIn(conversationKey: String): List<Message> =
        messages.filter { it.channel == selectedChannel && contactKeyOf(it) == conversationKey }

    @Then("the message should be sent on channel {int}")
    fun messageSentOnChannel(channel: Int) {
        sentPackets.shouldNotBeEmpty()
        sentPackets.last().channel shouldBe channel
    }
}

