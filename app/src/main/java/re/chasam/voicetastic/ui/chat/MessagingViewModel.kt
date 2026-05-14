package re.chasam.voicetastic.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import re.chasam.voicetastic.core.NodeIds
import re.chasam.voicetastic.model.ChatItem
import re.chasam.voicetastic.model.MeshNode
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.service.Portnums
import re.chasam.voicetastic.voice.VoicePlayer
import re.chasam.voicetastic.voice.VoiceRecorder
import uniffi.voicetastic.AssemblerConfig
import uniffi.voicetastic.AssemblyEvent
import uniffi.voicetastic.BuildConfig
import uniffi.voicetastic.VoiceAssembler as RustVoiceAssembler
import uniffi.voicetastic.VoiceCodec
import uniffi.voicetastic.VoiceMessageOut
import uniffi.voicetastic.buildMessage
import uniffi.voicetastic.randomMessageId
import java.io.File

/**
 * ViewModel for the unified chat screen (text + voice messages).
 *
 * Voice protocol is delegated to the native `voicetastic-core` crate via
 * the UniFFI-generated [uniffi.voicetastic] bindings. See `INTEGRATION.md`.
 */
class MessagingViewModel(
    private val meshService: MeshServiceManager,
    private val context: Context,
    private val voiceConfig: MutableStateFlow<VoiceConfig> = MutableStateFlow(VoiceConfig())
) : ViewModel() {

    companion object {
        private const val TAG = "MessagingVM"

        /** Default Reed-Solomon parity chunks per message. 0 = FEC disabled. */
        private const val DEFAULT_PARITY_COUNT: UByte = 0u

        /** Wire chunk body size. Matches `voicetastic_core::voice::MAX_BODY_SIZE`. */
        private const val DEFAULT_CHUNK_SIZE: UInt = 219u

        /** NACK window (ms). Mirrors `voicetastic_core::voice::NACK_WINDOW_MS`. */
        private const val NACK_WINDOW_MS: ULong = 1500uL

            /** Tick cadence: half the NACK window, so retransmit requests fire promptly. */
        private const val TICK_INTERVAL_MS: Long = 750L

        /** Per-message completion memory before duplicates are forgotten. */
        private const val COMPLETION_MEMORY_MS: ULong = 600_000uL

        /** Hard cap on NACK rounds per message. Mirrors `NACK_MAX_ROUNDS`. */
        private const val MAX_NACK_ROUNDS: UByte = 32u
    }

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

    /**
     * Native voice assembler. Owned by this ViewModel; closed in [onCleared].
     */
    private val assembler: RustVoiceAssembler = RustVoiceAssembler(
        AssemblerConfig(
            messageTimeoutMs = (voiceConfig.value.chunkTimeoutSeconds * 1000L).toULong(),
            partialPlayOnTimeout = voiceConfig.value.partialPlayOnTimeout,
            channelPsk = null,
            maxNackRounds = MAX_NACK_ROUNDS,
            nackWindowMs = NACK_WINDOW_MS,
            completionMemoryMs = COMPLETION_MEMORY_MS,
        )
    )

    private val _completedVoiceMessages =
        MutableSharedFlow<VoiceMessageOut>(extraBufferCapacity = 16)
    val completedVoiceMessages = _completedVoiceMessages.asSharedFlow()

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
    private var currentRecordingFile: File? = null
    private var tickJob: Job? = null

    init {
        observeIncomingTextMessages()
        observeIncomingVoiceData()
        observeCompletedVoiceMessages()
        startTickLoop()

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
                if (data.portNum != Portnums.PRIVATE_APP) return@collect
                val broadcast = data.to == "broadcast"
                val toNode = if (broadcast) 0u
                else (NodeIds.nodeIdToNum(data.to) ?: 0).toUInt()
                val event = try {
                    assembler.accept(
                        from = data.from,
                        broadcast = broadcast,
                        toNode = toNode,
                        channel = data.channel.toUInt(),
                        frame = data.payload,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "assembler.accept threw", t)
                    return@collect
                }
                when (event) {
                    is AssemblyEvent.Complete -> _completedVoiceMessages.tryEmit(event.message)
                    is AssemblyEvent.Rejected -> Log.d(TAG, "voice frame rejected: ${event.message}")
                    is AssemblyEvent.Nack -> {
                        // The peer is NACK-ing one of *our* messages. Send-side
                        // retransmit isn't wired yet; just log.
                        Log.d(TAG, "received NACK for messageId=${event.info.messageId}")
                    }
                    AssemblyEvent.Duplicate, is AssemblyEvent.Pending -> { /* no-op */ }
                }
            }
        }
    }

    private fun observeCompletedVoiceMessages() {
        viewModelScope.launch {
            _completedVoiceMessages.collect { msg ->
                _allChatItems.value = _allChatItems.value + msg.toChatItem()
            }
        }
    }

    /**
     * Periodically drives the assembler's timeout / NACK state machine.
     *
     * `tick()` returns:
     *  - `finalized`: messages whose timeout fired — surface partial audio
     *    if [VoiceConfig.partialPlayOnTimeout] is set.
     *  - `nacks`: NACK frames to transmit back to the sender of an
     *    incomplete message.
     */
    private fun startTickLoop() {
        tickJob = viewModelScope.launch {
            while (true) {
                ensureActive()
                kotlinx.coroutines.delay(TICK_INTERVAL_MS)
                val out = try {
                    assembler.tick()
                } catch (t: Throwable) {
                    Log.w(TAG, "assembler.tick threw", t)
                    continue
                }
                for (msg in out.finalized) {
                    _completedVoiceMessages.tryEmit(msg)
                }
                for (nack in out.nacks) {
                    val destId = nack.from // sender of the original message
                    meshService.sendData(
                        data = nack.frame,
                        portNum = Portnums.PRIVATE_APP,
                        destination = destId,
                        channel = nack.channel.toInt(),
                    )
                }
            }
        }
    }

    private fun VoiceMessageOut.toChatItem(): ChatItem.Voice {
        val toStr = if (broadcast) "broadcast" else NodeIds.nodeNumToId(toNode.toInt())
        val bitrateIdx = when (val c = codec) {
            VoiceCodec.AmrNb -> codecParam.toInt()
            // Codec2: codecParam encodes the mode (0..7). Surface as-is.
            VoiceCodec.Codec2 -> codecParam.toInt()
            VoiceCodec.Opus, VoiceCodec.PcmS16Le -> 0
            is VoiceCodec.Unknown -> c.raw.toInt()
        }
        return ChatItem.Voice(
            id = ++itemIdCounter,
            from = from,
            to = toStr,
            audioData = audio,
            timestamp = timestampMs,
            isOutgoing = false,
            isComplete = isComplete,
            totalChunks = totalData.toInt(),
            receivedChunks = receivedData.toInt(),
            bitrateIndex = bitrateIdx,
            channel = channel.toInt(),
            contactKey = computeContactKey(from, toStr, isOutgoing = false),
        )
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
            viewModelScope.launch(Dispatchers.IO) {
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
        val audioData = withContext(Dispatchers.IO) { file.readBytes() }
        val cfg = voiceConfig.value
        val myId = meshService.myNodeId.value ?: "me"
        val fromNodeNum = NodeIds.nodeIdToNum(myId)?.toUInt() ?: 0u

        val build = try {
            buildMessage(
                audio = audioData,
                cfg = BuildConfig(
                    messageId = randomMessageId(),
                    streamSeq = 0u,
                    codec = VoiceCodec.AmrNb,
                    codecParam = cfg.bitrate.ordinal.toUByte(),
                    chunkSize = DEFAULT_CHUNK_SIZE,
                    parityCount = DEFAULT_PARITY_COUNT,
                    lastInStream = true,
                    channelPsk = null,
                    fromNodeNum = fromNodeNum,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "buildMessage failed", t)
            file.delete()
            return
        }

        val destination = _selectedNode.value?.nodeId
        val channel = _selectedChannel.value
        val frames = build.frames

        _sendingProgress.value = 0f
        frames.forEachIndexed { index, frame ->
            meshService.sendData(
                data = frame,
                portNum = Portnums.PRIVATE_APP,
                destination = destination,
                channel = channel
            )
            _sendingProgress.value = (index + 1).toFloat() / frames.size
        }
        _sendingProgress.value = null

        // Add to chat as outgoing voice item
        val toField = destination ?: "broadcast"
        val item = ChatItem.Voice(
            id = ++itemIdCounter,
            from = myId,
            to = toField,
            audioData = audioData,
            isOutgoing = true,
            isComplete = true,
            totalChunks = build.totalData.toInt(),
            receivedChunks = build.totalData.toInt(),
            bitrateIndex = cfg.bitrate.ordinal,
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
        tickJob?.cancel()
        player.release()
        assembler.close()
    }
}
