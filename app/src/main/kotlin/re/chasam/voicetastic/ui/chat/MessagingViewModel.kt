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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import re.chasam.voicetastic.core.NodeIds
import re.chasam.voicetastic.model.ChatItem
import re.chasam.voicetastic.model.MeshNode
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.model.VoiceCodecChoice
import re.chasam.voicetastic.service.DeliveryStatus
import re.chasam.voicetastic.service.MeshFacade
import re.chasam.voicetastic.service.Portnums
import re.chasam.voicetastic.voice.RustAssembler
import re.chasam.voicetastic.voice.VoiceAssemblerApi
import re.chasam.voicetastic.voice.VoicePlayer
import re.chasam.voicetastic.voice.VoicePlayerApi
import re.chasam.voicetastic.voice.VoiceRecorder
import re.chasam.voicetastic.voice.VoiceRecorderApi
import uniffi.voicetastic.AssemblerConfig
import uniffi.voicetastic.AssemblyEvent
import uniffi.voicetastic.SendRequestUdl
import uniffi.voicetastic.SendStatus
import uniffi.voicetastic.VoiceCodec
import uniffi.voicetastic.VoiceMessageOut
import uniffi.voicetastic.VoiceSenderListener
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
    private val meshService: MeshFacade,
    private val context: Context,
    private val voiceConfig: MutableStateFlow<VoiceConfig> = MutableStateFlow(VoiceConfig()),
    // Voice components are injectable for tests; production callers
    // never pass these and get the framework-backed implementations.
    private val recorder: VoiceRecorderApi = VoiceRecorder(context),
    private val player: VoicePlayerApi = VoicePlayer(),
    // Assembler is injectable for tests; production gets the native-backed impl.
    private val assemblerFactory: (AssemblerConfig) -> VoiceAssemblerApi = { RustAssembler(it) },
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

        /**
         * Fallback hard cap on NACK rounds per message. Mirrors core's
         * `NACK_MAX_ROUNDS` (400). The Rust bridge re-derives the effective
         * cap from `messageTimeoutMs / nackWindowMs` via
         * `sync_nack_cap_to_timeout()`, so this value is normally overridden;
         * it just guards against a degenerate config. (The previous `32`
         * tripped well before the timeout and forced spurious
         * "partial: N/M chunks" finalizes.)
         */
        private const val MAX_NACK_ROUNDS: UShort = 400u

        /**
         * Per-chunk audio body size on the wire, in bytes. Caps the
         * ToRadio protobuf encoding of each voice chunk to comfortably
         * fit under a 255-byte BLE ATT MTU (effective payload 252).
         *
         * Empirically a `MAX_BODY_SIZE` (215) chunk wraps to a 259-byte
         * ToRadio on this codebase — 7 bytes over the BLE limit — and
         * the Meshtastic firmware's BLE stack does not support GATT
         * Long Write (Prepare/Execute), so any ToRadio above MTU − 3
         * is silently dropped regardless of the chosen write type. A
         * 200-byte body yields a ~244-byte ToRadio, leaving ~8 bytes
         * of headroom for protobuf varint width variation across
         * different from/to/id values.
         *
         * USB transports don't have this constraint but pay only a
         * small overhead cost (more frames per message); using the
         * same cap unconditionally keeps the wire format consistent.
         */
        private const val BLE_SAFE_CHUNK_SIZE: UInt = 200u

        /**
         * Hard cap on retained chat items across all conversations.
         *
         * The list is the source of truth for the entire chat history of
         * a session, so without a cap an always-on listener accumulates
         * indefinitely — voice items in particular carry the full audio
         * payload in memory until the user clears the chat. Eviction is
         * FIFO (drop the oldest) which is the right policy for a chat:
         * the user can always scroll back through the most recent N.
         * 1000 items covers months of typical low-bandwidth mesh use.
         */
        private const val MAX_CHAT_ITEMS = 1_000
    }

    // Master list of ALL chat items (unfiltered)
    private val _allChatItems = MutableStateFlow<List<ChatItem>>(emptyList())

    /**
     * Append a [ChatItem] to [_allChatItems], evicting the oldest entries
     * once the list exceeds [MAX_CHAT_ITEMS]. All append sites go through
     * here so the bound is uniform; bypassing it means a memory leak.
     */
    private fun appendChatItem(item: ChatItem) {
        // Atomic RMW: sendVoiceFile appends from Dispatchers.IO while the
        // incoming-message collectors append on the main dispatcher, so a
        // plain value=value+item read-modify-write could drop an append (and
        // hand out a duplicate id, which crashes the keyed LazyColumn).
        _allChatItems.update { current ->
            val withItem = current + item
            if (withItem.size > MAX_CHAT_ITEMS) withItem.takeLast(MAX_CHAT_ITEMS) else withItem
        }
    }

    val selectedNode: StateFlow<MeshNode?>
        field = MutableStateFlow<MeshNode?>(null)

    val selectedChannel: StateFlow<Int>
        field = MutableStateFlow(0)

    /**
     * Filtered chat items for the currently-selected conversation.
     *
     * Conversation identity uses each message's pre-computed `contactKey`:
     *   - selectedNode == null  → conversation key = "broadcast"
     *   - selectedNode != null  → conversation key = selectedNode.nodeId
     */
    val chatItems: StateFlow<List<ChatItem>> = combine(
        _allChatItems,
        selectedNode,
        selectedChannel
    ) { items, node, channel ->
        val conversationKey = node?.nodeId ?: "broadcast"
        items.filter { it.channel == channel && it.contactKey == conversationKey }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val nodes: StateFlow<List<MeshNode>> = meshService.nodes
    /** Local node id (`!aabbccdd`), or null until the device handshake completes. */
    val myNodeId: StateFlow<String?> = meshService.myNodeId
    /** Our own node (matched by node number), or null while disconnected. */
    val selfNode: StateFlow<MeshNode?> = meshService.selfNode
    /** Per-node telemetry history exposed for the node-detail dialog's sparklines. */
    val nodeHistory: StateFlow<Map<Int, List<re.chasam.voicetastic.service.NodeSample>>> =
        meshService.nodeHistory
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

    // Voice state — recorder/player are injected via the constructor
    // (see VoiceRecorderApi / VoicePlayerApi) so tests can swap fakes.

    /** Build an [AssemblerConfig] from the live [VoiceConfig] user settings. */
    private fun assemblerConfigFrom(cfg: VoiceConfig): AssemblerConfig = AssemblerConfig(
        messageTimeoutMs = (cfg.chunkTimeoutSeconds * 1000L).toULong(),
        partialPlayOnTimeout = cfg.partialPlayOnTimeout,
        maxNackRounds = MAX_NACK_ROUNDS,
        nackWindowMs = NACK_WINDOW_MS,
        completionMemoryMs = COMPLETION_MEMORY_MS,
    )

    /**
     * Native voice assembler. Owned by this ViewModel; closed in [onCleared].
     * Reconfigured live when the user changes the relevant voice settings (see
     * the collector in [init]).
     */
    private val assembler: VoiceAssemblerApi = assemblerFactory(assemblerConfigFrom(voiceConfig.value))

    private val _completedVoiceMessages =
        MutableSharedFlow<VoiceMessageOut>(extraBufferCapacity = 16)
    val completedVoiceMessages = _completedVoiceMessages.asSharedFlow()

    val isRecording: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /**
     * Captured-clip-awaiting-confirmation file, when the user stopped a
     * recording without sending it. Null in the Idle and Recording states.
     * The UI's voice composer switches into Preview mode (Listen / Delete /
     * Send) when this is non-null, mirroring desktop's `VoiceCompose::Preview`.
     */
    val previewFile: StateFlow<File?>
        field = MutableStateFlow<File?>(null)

    val isPreviewPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val isPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val playingItemId: StateFlow<Int?>
        field = MutableStateFlow<Int?>(null)

    val sendingProgress: StateFlow<VoiceTransferProgress?>
        field = MutableStateFlow<VoiceTransferProgress?>(null)

    /**
     * In-flight inbound voice messages, keyed by `messageId`. Populated as
     * `AssemblyEvent.Pending` frames arrive; cleared when the message
     * completes, is rejected, or its UI bubble takes over (the bubble
     * itself shows received/total chunks on its own).
     */
    val incomingProgress: StateFlow<Map<UInt, VoiceReceiveProgress>>
        field = MutableStateFlow<Map<UInt, VoiceReceiveProgress>>(emptyMap())

    val config: StateFlow<VoiceConfig> = voiceConfig.asStateFlow()

    // Atomic so the IO-dispatched sendVoiceFile and the main-thread collectors
    // never hand out a duplicate id (a duplicate key crashes the LazyColumn).
    private val itemIdCounter = AtomicInteger(0)
    private var currentRecordingFile: File? = null
    private var tickJob: Job? = null

    init {
        observeIncomingTextMessages()
        observeIncomingVoiceData()
        observeCompletedVoiceMessages()
        observeAckEvents()
        startTickLoop()
        observeAssemblerConfig()

        // The recorder can stop itself at the configured max duration. Pick the
        // clip up here (any thread → hop to Main) instead of dropping it.
        recorder.onMaxDurationReached = { file ->
            viewModelScope.launch {
                isRecording.value = false
                if (file.exists() && file.length() > 0) {
                    previewFile.value = file
                } else {
                    currentRecordingFile = null
                }
            }
        }
    }

    /**
     * Push live changes to the reassembly-timeout / partial-play settings into
     * the running assembler so the Settings sliders take effect without a
     * restart.
     */
    private fun observeAssemblerConfig() {
        viewModelScope.launch {
            voiceConfig
                .map { it.chunkTimeoutSeconds to it.partialPlayOnTimeout }
                .distinctUntilChanged()
                .drop(1) // construction already applied the initial values
                .collect {
                    runCatching { assembler.setConfig(assemblerConfigFrom(voiceConfig.value)) }
                        .onFailure { Log.w(TAG, "assembler.setConfig failed", it) }
                }
        }
    }

    /**
     * Subscribe to firmware-reported delivery acks/naks and stamp the
     * matching outgoing [ChatItem.Text] with its [DeliveryStatus]. The
     * lookup is by [ChatItem.Text.packetId], which `sendMessage` set at
     * send time. No-op if no item matches (e.g. an ack for a packet sent
     * before this view model existed, or for a non-text packet).
     */
    private fun observeAckEvents() {
        viewModelScope.launch {
            meshService.ackEvents.collect { ev ->
                _allChatItems.update { items ->
                    items.map { item ->
                        if (item is ChatItem.Text && item.packetId == ev.packetId) {
                            item.copy(deliveryStatus = ev.status)
                        } else {
                            item
                        }
                    }
                }
            }
        }
    }

    private fun observeIncomingTextMessages() {
        viewModelScope.launch {
            meshService.incomingTextMessages.collect { incoming ->
                val contactKey = computeContactKey(incoming.from, incoming.to, isOutgoing = false)
                val myId = meshService.myNodeId.value
                val selected = selectedNode.value?.nodeId
                val selectedChan = selectedChannel.value
                val willShow = incoming.channel == selectedChan &&
                    contactKey == (selected ?: "broadcast")
                Log.d(
                    TAG,
                    "incoming text: from=${incoming.from} to=${incoming.to} ch=${incoming.channel} " +
                        "myId=$myId → contactKey=$contactKey | selectedNode=$selected " +
                        "selectedCh=$selectedChan → willShow=$willShow"
                )
                val item = ChatItem.Text(
                    id = itemIdCounter.incrementAndGet(),
                    text = incoming.text,
                    from = incoming.from,
                    to = incoming.to,
                    timestamp = incoming.timestamp,
                    isOutgoing = false,
                    channel = incoming.channel,
                    contactKey = contactKey
                )
                appendChatItem(item)
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
                        incomingProgress.value = incomingProgress.value - event.message.messageId
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
                        incomingProgress.value = incomingProgress.value + (event.messageId to VoiceReceiveProgress(
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
                appendChatItem(msg.toChatItem())
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
                    incomingProgress.value = incomingProgress.value - msg.messageId
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
            id = itemIdCounter.incrementAndGet(),
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

        val destination = selectedNode.value?.nodeId
        val channel = selectedChannel.value
        // `sendTextTracked` returns the mesh packet id so the bubble can
        // be correlated with the eventual ack/nak from `ackEvents`.
        val packetId = meshService.sendTextTracked(text, destination, channel)

        if (packetId != null) {
            val myId = meshService.myNodeId.value ?: "me"
            val toField = destination ?: "broadcast"
            // Only unicast text packets get firmware-level acks; the
            // bubble for a broadcast stays icon-less because no ack will
            // ever arrive to clear a Pending state.
            val initialStatus = if (destination != null) DeliveryStatus.Pending else null
            val item = ChatItem.Text(
                id = itemIdCounter.incrementAndGet(),
                text = text,
                from = myId,
                to = toField,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                channel = channel,
                contactKey = computeContactKey(myId, toField, isOutgoing = true),
                packetId = packetId,
                deliveryStatus = initialStatus,
            )
            appendChatItem(item)
        }
    }

    // ========== VOICE MESSAGING ==========

    /**
     * Start recording a voice message.
     */
    fun startRecording() {
        if (isRecording.value) return

        currentRecordingFile = recorder.startRecording(voiceConfig.value)
        if (currentRecordingFile != null) {
            isRecording.value = true
        }
    }

    /**
     * Stop recording and send the voice message immediately. Kept for
     * callers that don't want the Preview state (e.g. tests); the chat
     * UI now uses `stopRecordingToPreview()` so the user can listen and
     * confirm before transmitting.
     */
    fun stopRecordingAndSend() {
        if (!isRecording.value) return

        val file = recorder.stopRecording()
        isRecording.value = false

        if (file != null && file.exists() && file.length() > 0) {
            viewModelScope.launch(Dispatchers.IO) {
                sendVoiceFile(file)
            }
        }
    }

    /**
     * Stop recording but keep the captured file around so the user can
     * preview it before sending. Drives the voice composer into Preview
     * mode (Listen / Delete / Send), mirroring desktop's `VoiceCompose`
     * state machine.
     */
    fun stopRecordingToPreview() {
        if (!isRecording.value) return
        val file = recorder.stopRecording()
        isRecording.value = false
        if (file != null && file.exists() && file.length() > 0) {
            previewFile.value = file
        } else {
            // Recording was empty or vanished; just go back to Idle.
            currentRecordingFile = null
        }
    }

    /**
     * Play the recorded preview clip through the same decoder path
     * inbound messages use. The codec choice comes from `voiceConfig`
     * because the recorder always encodes with the currently-selected
     * codec.
     */
    fun playPreview() {
        val file = previewFile.value ?: return
        if (isPreviewPlaying.value) return
        // Mark playing up front; player.play() returns at start-of-playback,
        // so the flag must be cleared from the completion callback (below),
        // not synchronously — otherwise the Stop button never appears and
        // stopPreviewPlayback() early-returns.
        isPreviewPlaying.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: run {
                isPreviewPlaying.value = false
                return@launch
            }
            val cfg = voiceConfig.value
            val (codec, param) = when (cfg.codec) {
                VoiceCodecChoice.AmrNb -> VoiceCodec.AmrNb to cfg.bitrate.ordinal
                VoiceCodecChoice.Opus -> VoiceCodec.Opus to cfg.opusBitrateKbps
                VoiceCodecChoice.Codec2 -> VoiceCodec.Codec2 to cfg.codec2Mode.ordinal
            }
            try {
                player.play(bytes, context.cacheDir, codec, param) {
                    isPreviewPlaying.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "playPreview failed", e)
                isPreviewPlaying.value = false
            }
        }
    }

    /** Interrupt the preview-clip playback (no-op if not playing). */
    fun stopPreviewPlayback() {
        if (!isPreviewPlaying.value) return
        player.stop()
        isPreviewPlaying.value = false
    }

    /** Drop the previewed clip without sending; returns to Idle. */
    fun discardPreview() {
        stopPreviewPlayback()
        previewFile.value?.delete()
        previewFile.value = null
        currentRecordingFile = null
    }

    /** Send the previewed clip; returns to Idle once dispatched. */
    fun sendPreview() {
        val file = previewFile.value ?: return
        stopPreviewPlayback()
        previewFile.value = null
        currentRecordingFile = null
        if (file.exists() && file.length() > 0) {
            viewModelScope.launch(Dispatchers.IO) { sendVoiceFile(file) }
        }
    }

    /**
     * Cancel current recording without sending.
     */
    fun cancelRecording() {
        recorder.stopRecording()
        isRecording.value = false
        currentRecordingFile?.delete()
        currentRecordingFile = null
    }

    private suspend fun sendVoiceFile(file: File) {
        val audioData = withContext(Dispatchers.IO) { file.readBytes() }
        val cfg = voiceConfig.value
        val myId = meshService.myNodeId.value ?: "me"

        val (voiceCodec, codecParam) = when (cfg.codec) {
            VoiceCodecChoice.AmrNb -> VoiceCodec.AmrNb to cfg.bitrate.ordinal.toUByte()
            VoiceCodecChoice.Opus -> VoiceCodec.Opus to cfg.opusBitrateKbps.toUByte()
            VoiceCodecChoice.Codec2 -> VoiceCodec.Codec2 to cfg.codec2Mode.ordinal.toUByte()
        }

        val destination = selectedNode.value?.nodeId
        val channel = selectedChannel.value
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
                        sendingProgress.value = VoiceTransferProgress(
                            sent = 0,
                            total = status.totalData.toInt() + status.parityCount.toInt(),
                            contactKey = sendContactKey,
                            channel = channel,
                        )
                    }
                    is SendStatus.Sending -> {
                        sendingProgress.value = VoiceTransferProgress(
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
                        sendingProgress.value = null
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
            chunkSize = BLE_SAFE_CHUNK_SIZE,
            lingerMs = 0uL,        // 0 = default 60 s retain window
            streamSeq = 0u,
            lastInStream = true,
            pacingMs = 0uL,        // 0 = live modem preset
        )

        val messageId = try {
            sender.send(req, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "VoiceSender.send failed", t)
            sendingProgress.value = null
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
            id = itemIdCounter.incrementAndGet(),
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
        appendChatItem(item)

        file.delete()
    }

    /**
     * Play a voice message.
     */
    fun playVoiceMessage(item: ChatItem.Voice) {
        if (isPlaying.value && playingItemId.value == item.id) {
            player.stop()
            isPlaying.value = false
            playingItemId.value = null
            return
        }

        isPlaying.value = true
        playingItemId.value = item.id
        // Off the main thread: play() writes a temp file + MediaPlayer.prepare()
        // synchronously. Clear state from the per-playback completion callback,
        // guarded by item id so a stale completion can't unstick a newer item.
        viewModelScope.launch(Dispatchers.IO) {
            player.play(item.audioData, context.cacheDir, item.codec, item.bitrateIndex) {
                if (playingItemId.value == item.id) {
                    isPlaying.value = false
                    playingItemId.value = null
                }
            }
        }
    }

    /**
     * Stop playback.
     */
    fun stopPlayback() {
        player.stop()
        isPlaying.value = false
        playingItemId.value = null
    }

    // ========== COMMON ==========

    /**
     * Select a node to send messages to. Null = broadcast mode.
     */
    fun selectNode(node: MeshNode?) {
        selectedNode.value = node
    }

    /**
     * Select the channel index for sending and filtering messages.
     */
    fun selectChannel(channel: Int) {
        selectedChannel.value = channel
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
