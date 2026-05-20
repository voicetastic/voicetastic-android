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
import re.chasam.voicetastic.model.VoiceCodecChoice
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.service.Portnums
import re.chasam.voicetastic.voice.VoicePlayer
import re.chasam.voicetastic.voice.VoiceRecorder
import uniffi.voicetastic.AssemblerConfig
import uniffi.voicetastic.AssemblyEvent
import uniffi.voicetastic.SendRequestUdl
import uniffi.voicetastic.SendStatus
import uniffi.voicetastic.VoiceAssembler as RustVoiceAssembler
import uniffi.voicetastic.VoiceCodec
import uniffi.voicetastic.VoiceMessageOut
import uniffi.voicetastic.VoiceSenderListener
import java.io.File

/**
 * Per-message outgoing voice transfer progress, surfaced to the chat UI
 * while a burst is on its way to the radio. `sent` and `total` are chunk
 * counts (DATA + parity); the fraction is derived in the view layer.
 *
 * `contactKey` and `channel` identify the conversation this send belongs
 * to so the chat screen can show the banner only in that conversation.
 */
data class VoiceTransferProgress(
    val sent: Int,
    val total: Int,
    val contactKey: String,
    val channel: Int,
) {
    val fraction: Float get() = if (total > 0) sent.toFloat() / total else 0f
}

/**
 * Per-message incoming voice transfer progress, populated from
 * `AssemblyEvent.Pending` frames so the UI can show "N / M chunks"
 * before the message completes.
 *
 * `contactKey` is computed the same way as on the completed `ChatItem.Voice`
 * so progress filtering matches the chat filter exactly.
 */
data class VoiceReceiveProgress(
    val messageId: UInt,
    val from: String,
    val received: Int,
    val total: Int,
    val channel: Int,
    val contactKey: String,
) {
    val fraction: Float get() = if (total > 0) received.toFloat() / total else 0f
}

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

        /** NACK window (ms). Mirrors `voicetastic_core::voice::NACK_WINDOW_MS`. */
        private const val NACK_WINDOW_MS: ULong = 1500uL

            /** Tick cadence: half the NACK window, so retransmit requests fire promptly. */
        private const val TICK_INTERVAL_MS: Long = 750L

        /** Per-message completion memory before duplicates are forgotten. */
        private const val COMPLETION_MEMORY_MS: ULong = 600_000uL

        /** Hard cap on NACK rounds per message. Mirrors `NACK_MAX_ROUNDS`. */
        private const val MAX_NACK_ROUNDS: UShort = 32u
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

    private val _sendingProgress = MutableStateFlow<VoiceTransferProgress?>(null)
    val sendingProgress: StateFlow<VoiceTransferProgress?> = _sendingProgress.asStateFlow()

    /**
     * In-flight inbound voice messages, keyed by `messageId`. Populated as
     * `AssemblyEvent.Pending` frames arrive; cleared when the message
     * completes, is rejected, or its UI bubble takes over (the bubble
     * itself shows received/total chunks on its own).
     */
    private val _incomingProgress = MutableStateFlow<Map<UInt, VoiceReceiveProgress>>(emptyMap())
    val incomingProgress: StateFlow<Map<UInt, VoiceReceiveProgress>> = _incomingProgress.asStateFlow()

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
                val contactKey = computeContactKey(incoming.from, incoming.to, isOutgoing = false)
                val myId = meshService.myNodeId.value
                val selected = _selectedNode.value?.nodeId
                val selectedChan = _selectedChannel.value
                val willShow = incoming.channel == selectedChan &&
                    contactKey == (selected ?: "broadcast")
                Log.d(
                    TAG,
                    "incoming text: from=${incoming.from} to=${incoming.to} ch=${incoming.channel} " +
                        "myId=$myId → contactKey=$contactKey | selectedNode=$selected " +
                        "selectedCh=$selectedChan → willShow=$willShow"
                )
                val item = ChatItem.Text(
                    id = ++itemIdCounter,
                    text = incoming.text,
                    from = incoming.from,
                    to = incoming.to,
                    timestamp = incoming.timestamp,
                    isOutgoing = false,
                    channel = incoming.channel,
                    contactKey = contactKey
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
                    is AssemblyEvent.Complete -> {
                        _incomingProgress.value = _incomingProgress.value - event.message.messageId
                        _completedVoiceMessages.tryEmit(event.message)
                    }
                    is AssemblyEvent.Rejected -> Log.d(TAG, "voice frame rejected: ${event.message}")
                    is AssemblyEvent.Nack -> {
                        // The peer is NACK-ing one of *our* messages. Send-side
                        // retransmit isn't wired yet; just log.
                        Log.d(TAG, "received NACK for messageId=${event.info.messageId}")
                    }
                    is AssemblyEvent.Pending -> {
                        // `Pending` doesn't carry `to`, so derive the contactKey
                        // from the same packet header `accept` ran on. Matches
                        // exactly what the completed `ChatItem.Voice` will use,
                        // so the filter holds end-to-end.
                        val contactKey = computeContactKey(data.from, data.to, isOutgoing = false)
                        _incomingProgress.value = _incomingProgress.value + (event.messageId to VoiceReceiveProgress(
                            messageId = event.messageId,
                            from = event.from,
                            received = event.receivedData.toInt(),
                            total = event.totalData.toInt(),
                            channel = event.channel.toInt(),
                            contactKey = contactKey,
                        ))
                    }
                    AssemblyEvent.Duplicate -> { /* no-op */ }
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
                    _incomingProgress.value = _incomingProgress.value - msg.messageId
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
            codec = codec,
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

        val (voiceCodec, codecParam) = when (cfg.codec) {
            VoiceCodecChoice.AmrNb -> VoiceCodec.AmrNb to cfg.bitrate.ordinal.toUByte()
            VoiceCodecChoice.Opus -> VoiceCodec.Opus to cfg.opusBitrateKbps.toUByte()
            VoiceCodecChoice.Codec2 -> VoiceCodec.Codec2 to cfg.codec2Mode.ordinal.toUByte()
        }

        val destination = _selectedNode.value?.nodeId
        val channel = _selectedChannel.value
        // contactKey for the conversation this send belongs to — matches
        // the key the outgoing ChatItem.Voice gets below, so the progress
        // banner only shows in that conversation.
        val sendContactKey = computeContactKey(myId, destination ?: "broadcast", isOutgoing = true)
        val toNodeNum: UInt = destination?.let { NodeIds.nodeIdToNum(it)?.toUInt() } ?: 0u
        val broadcast = destination == null

        val sender = meshService.voiceSender() ?: run {
            Log.e(TAG, "sendVoiceFile: VoiceSender unavailable (not connected?)")
            file.delete()
            return
        }

        // Listener runs on a Rust worker thread. Everything we touch here
        // is thread-safe (MutableStateFlow.value) — no need to dispatch
        // back to viewModelScope.
        val listener = object : VoiceSenderListener {
            override fun `onStatus`(status: SendStatus) {
                when (status) {
                    is SendStatus.Building -> {
                        // First event: now we know total chunks. Seed the
                        // progress banner at 0 / total so it shows up the
                        // instant we start, even before the first packet
                        // leaves the radio.
                        _sendingProgress.value = VoiceTransferProgress(
                            sent = 0,
                            total = status.totalData.toInt() + status.parityCount.toInt(),
                            contactKey = sendContactKey,
                            channel = channel,
                        )
                    }
                    is SendStatus.Sending -> {
                        _sendingProgress.value = VoiceTransferProgress(
                            sent = status.sent.toInt(),
                            total = status.total.toInt(),
                            contactKey = sendContactKey,
                            channel = channel,
                        )
                    }
                    is SendStatus.Retransmitting -> {
                        // FEC retransmit: pin the bar to "in flight" rather
                        // than letting it look done. The next Sending event
                        // will advance it again.
                        Log.d(TAG, "voice send: retransmitting ${status.chunks.size} chunks for ${status.messageId}")
                    }
                    is SendStatus.Complete,
                    is SendStatus.GaveUp,
                    is SendStatus.Failed -> {
                        if (status is SendStatus.Failed) {
                            Log.w(TAG, "voice send failed: ${status.message}")
                        }
                        _sendingProgress.value = null
                    }
                    is SendStatus.BurstComplete -> {
                        // Initial burst is on the air; we may still get
                        // Retransmitting events. Don't clear the banner.
                    }
                }
            }
        }

        val req = SendRequestUdl(
            audio = audioData,
            codec = voiceCodec,
            codecParam = codecParam,
            channel = channel.toUInt(),
            broadcast = broadcast,
            toNode = toNodeNum,
            parityCount = DEFAULT_PARITY_COUNT,
            chunkSize = 0u,        // 0 = MAX_BODY_SIZE (matches previous DEFAULT_CHUNK_SIZE)
            channelPsk = ByteArray(0),
            fromNodeNum = fromNodeNum,
            lingerMs = 0uL,        // 0 = default 60 s retain window
            streamSeq = 0u,
            lastInStream = true,
            pacingMs = 0uL,        // 0 = live modem preset
        )

        val messageId = try {
            sender.send(req, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "VoiceSender.send failed", t)
            _sendingProgress.value = null
            file.delete()
            return
        }
        Log.d(TAG, "voice send queued: messageId=$messageId, audioBytes=${audioData.size}")

        // Add to chat as outgoing voice item. `bitrateIndex` doubles as the
        // codec-specific param the player needs back (Codec2 mode, AMR-NB
        // bitrate index); Opus playback ignores it. `totalChunks` is left
        // at 0 — the bubble only renders chunk counts when isComplete is
        // false, which never happens for outgoing items.
        val outgoingBitrateIndex = when (cfg.codec) {
            VoiceCodecChoice.AmrNb -> cfg.bitrate.ordinal
            VoiceCodecChoice.Codec2 -> cfg.codec2Mode.ordinal
            VoiceCodecChoice.Opus -> 0
        }
        val toField = destination ?: "broadcast"
        val item = ChatItem.Voice(
            id = ++itemIdCounter,
            from = myId,
            to = toField,
            audioData = audioData,
            codec = voiceCodec,
            isOutgoing = true,
            isComplete = true,
            totalChunks = 0,
            receivedChunks = 0,
            bitrateIndex = outgoingBitrateIndex,
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
        player.play(item.audioData, context.cacheDir, item.codec, item.bitrateIndex)
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
