package re.chasam.voicetastic.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import re.chasam.voicetastic.model.ChatItem
import re.chasam.voicetastic.model.MeshNode
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.service.Portnums
import re.chasam.voicetastic.voice.VoiceAssembler
import re.chasam.voicetastic.voice.VoiceChunker
import re.chasam.voicetastic.voice.VoicePlayer
import re.chasam.voicetastic.voice.VoiceRecorder
import java.io.File

/**
 * ViewModel for the unified chat screen (text + voice messages).
 */
class MessagingViewModel(
    private val meshService: MeshServiceManager,
    private val context: Context,
    private val voiceConfig: MutableStateFlow<VoiceConfig> = MutableStateFlow(VoiceConfig())
) : ViewModel() {

    // Master list of ALL chat items (unfiltered)
    private val _allChatItems = MutableStateFlow<List<ChatItem>>(emptyList())

    private val _selectedNode = MutableStateFlow<MeshNode?>(null)
    val selectedNode: StateFlow<MeshNode?> = _selectedNode.asStateFlow()

    private val _selectedChannel = MutableStateFlow(0)
    val selectedChannel: StateFlow<Int> = _selectedChannel.asStateFlow()

    /**
     * Filtered chat items for the currently-selected conversation.
     *
     * Conversation identity uses each message's pre-computed `contactKey`:
     *   - selectedNode == null  → conversation key = "broadcast"
     *   - selectedNode != null  → conversation key = selectedNode.nodeId
     *
     * Mirrors the meshtastic-android Contact model: each message belongs to
     * exactly one conversation, decided at ingestion time. No perspective-
     * dependent or fallback logic at filter time.
     */
    val chatItems: StateFlow<List<ChatItem>> = combine(
        _allChatItems,
        _selectedNode,
        _selectedChannel
    ) { items, node, channel ->
        val conversationKey = node?.nodeId ?: "broadcast"
        items.filter { it.channel == channel && it.contactKey == conversationKey }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val nodes: StateFlow<List<MeshNode>> = meshService.nodes
    val connectionState: StateFlow<String> = meshService.connectionState

    /**
     * Available channels as (index, name) pairs, derived from the device's channel config.
     * Always includes at least channel 0 ("Primary").
     */
    val availableChannels: StateFlow<List<Pair<Int, String>>> = meshService.channels
        .map { channelList ->
            if (channelList.isEmpty()) {
                listOf(0 to "Primary")
            } else {
                channelList.map { ch ->
                    val name = if (ch.hasSettings() && ch.settings.name.isNotBlank()) {
                        ch.settings.name
                    } else if (ch.index == 0) {
                        "Primary"
                    } else {
                        "Channel ${ch.index}"
                    }
                    ch.index to name
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(0 to "Primary"))

    // Voice state
    private val recorder = VoiceRecorder(context)
    private val player = VoicePlayer()
    private val assembler = VoiceAssembler(
        chunkTimeoutSeconds = voiceConfig.value.chunkTimeoutSeconds,
        partialPlayOnTimeout = voiceConfig.value.partialPlayOnTimeout,
        scope = viewModelScope
    )

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playingItemId = MutableStateFlow<Int?>(null)
    val playingItemId: StateFlow<Int?> = _playingItemId.asStateFlow()

    private val _sendingProgress = MutableStateFlow<Float?>(null)
    val sendingProgress: StateFlow<Float?> = _sendingProgress.asStateFlow()

    val config: StateFlow<VoiceConfig> = voiceConfig.asStateFlow()

    private var itemIdCounter = 0
    private var voiceMessageIdSeq = (System.currentTimeMillis() % 65536).toInt()
    private var currentRecordingFile: File? = null

    init {
        observeIncomingTextMessages()
        observeIncomingVoiceData()
        observeCompletedVoiceMessages()

        player.onCompletion = {
            _isPlaying.value = false
            _playingItemId.value = null
        }
    }

    private fun observeIncomingTextMessages() {
        viewModelScope.launch {
            meshService.incomingTextMessages.collect { incoming ->
                val item = ChatItem.Text(
                    id = ++itemIdCounter,
                    text = incoming.text,
                    from = incoming.from,
                    to = incoming.to,
                    timestamp = incoming.timestamp,
                    isOutgoing = false,
                    channel = incoming.channel,
                    contactKey = computeContactKey(incoming.from, incoming.to, isOutgoing = false)
                )
                _allChatItems.value = _allChatItems.value + item
            }
        }
    }

    private fun observeIncomingVoiceData() {
        viewModelScope.launch {
            meshService.incomingDataMessages.collect { data ->
                if (data.portNum == Portnums.PRIVATE_APP) {
                    assembler.onChunkReceived(
                        from = data.from,
                        chunkData = data.payload,
                        destinationId = data.to,
                        channel = data.channel
                    )
                }
            }
        }
    }

    private fun observeCompletedVoiceMessages() {
        viewModelScope.launch {
            assembler.completedMessages.collect { msg ->
                val item = ChatItem.Voice(
                    id = ++itemIdCounter,
                    from = msg.from,
                    to = msg.to,
                    audioData = msg.audioData,
                    timestamp = msg.timestamp,
                    isOutgoing = false,
                    isComplete = msg.isComplete,
                    totalChunks = msg.totalChunks,
                    receivedChunks = msg.receivedChunks,
                    bitrateIndex = msg.bitrateIndex,
                    channel = msg.channel,
                    contactKey = computeContactKey(msg.from, msg.to, isOutgoing = false)
                )
                _allChatItems.value = _allChatItems.value + item
            }
        }
    }

    // ========== TEXT MESSAGING ==========

    /**
     * Send a text message to the selected node, or broadcast if no node selected.
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val destination = _selectedNode.value?.nodeId
        val channel = _selectedChannel.value
        val success = meshService.sendText(text, destination, channel)

        if (success) {
            val myId = meshService.myNodeId.value ?: "me"
            val toField = destination ?: "broadcast"
            val item = ChatItem.Text(
                id = ++itemIdCounter,
                text = text,
                from = myId,
                to = toField,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                channel = channel,
                contactKey = computeContactKey(myId, toField, isOutgoing = true)
            )
            _allChatItems.value = _allChatItems.value + item
        }
    }

    // ========== VOICE MESSAGING ==========

    /**
     * Start recording a voice message.
     */
    fun startRecording() {
        if (_isRecording.value) return

        currentRecordingFile = recorder.startRecording(voiceConfig.value)
        if (currentRecordingFile != null) {
            _isRecording.value = true
        }
    }

    /**
     * Stop recording and send the voice message.
     */
    fun stopRecordingAndSend() {
        if (!_isRecording.value) return

        val file = recorder.stopRecording()
        _isRecording.value = false

        if (file != null && file.exists() && file.length() > 0) {
            viewModelScope.launch {
                sendVoiceFile(file)
            }
        }
    }

    /**
     * Cancel current recording without sending.
     */
    fun cancelRecording() {
        recorder.stopRecording()
        _isRecording.value = false
        currentRecordingFile?.delete()
        currentRecordingFile = null
    }

    private suspend fun sendVoiceFile(file: File) {
        val audioData = file.readBytes()
        val msgId = voiceMessageIdSeq++ and 0xFFFF

        val chunks = VoiceChunker.chunkAudio(
            audioData = audioData,
            messageId = msgId,
            bitrate = voiceConfig.value.bitrate
        )

        val destination = _selectedNode.value?.nodeId
        val channel = _selectedChannel.value

        _sendingProgress.value = 0f
        chunks.forEachIndexed { index, chunk ->
            meshService.sendData(
                data = chunk,
                portNum = Portnums.PRIVATE_APP,
                destination = destination,
                channel = channel
            )
            _sendingProgress.value = (index + 1).toFloat() / chunks.size
            delay(500)
        }
        _sendingProgress.value = null

        // Add to chat as outgoing voice item
        val myId = meshService.myNodeId.value ?: "me"
        val toField = destination ?: "broadcast"
        val item = ChatItem.Voice(
            id = ++itemIdCounter,
            from = myId,
            to = toField,
            audioData = audioData,
            isOutgoing = true,
            isComplete = true,
            totalChunks = chunks.size,
            receivedChunks = chunks.size,
            bitrateIndex = voiceConfig.value.bitrate.ordinal,
            channel = channel,
            contactKey = computeContactKey(myId, toField, isOutgoing = true)
        )
        _allChatItems.value = _allChatItems.value + item

        file.delete()
    }

    /**
     * Play a voice message.
     */
    fun playVoiceMessage(item: ChatItem.Voice) {
        if (_isPlaying.value && _playingItemId.value == item.id) {
            player.stop()
            _isPlaying.value = false
            _playingItemId.value = null
            return
        }

        _isPlaying.value = true
        _playingItemId.value = item.id
        player.play(item.audioData, context.cacheDir)
    }

    /**
     * Stop playback.
     */
    fun stopPlayback() {
        player.stop()
        _isPlaying.value = false
        _playingItemId.value = null
    }

    // ========== COMMON ==========

    /**
     * Select a node to send messages to. Null = broadcast mode.
     */
    fun selectNode(node: MeshNode?) {
        _selectedNode.value = node
    }

    /**
     * Select the channel index for sending and filtering messages.
     */
    fun selectChannel(channel: Int) {
        _selectedChannel.value = channel
    }

    /**
     * Clear the message history.
     */
    fun clearMessages() {
        _allChatItems.value = emptyList()
    }

    /**
     * Compute the conversation key for a message from this node's perspective.
     *
     *  - Outgoing broadcast (to == "broadcast")            → "broadcast"
     *  - Outgoing DM (to == some node id)                  → that node id
     *  - Incoming broadcast (to == "broadcast")            → "broadcast"
     *  - Incoming DM addressed to me (to == myId)          → the sender
     *  - Incoming overheard DM (to == some other node id)  → the sender
     *    (we never had this conversation, but group it with the sender so
     *     it doesn't pollute the broadcast/channel view)
     */
    private fun computeContactKey(
        from: String,
        to: String,
        isOutgoing: Boolean
    ): String {
        val myId = meshService.myNodeId.value ?: ""
        return when {
            to == "broadcast" -> "broadcast"
            isOutgoing -> to
            to == myId -> from
            else -> from
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        assembler.destroy()
    }
}
