package re.chasam.voicetastic.voice

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.VoiceMessage
import java.io.ByteArrayOutputStream

/**
 * Reassembles incoming voice chunks into complete voice messages.
 *
 * Handles out-of-order delivery, missing chunks (with timeout + partial play),
 * and concurrent messages from different senders.
 *
 * Uses a coroutine [Mutex] instead of `synchronized` for suspension-friendly locking.
 * Maintains a recently-completed blacklist to reject stale duplicate chunks
 * that arrive after a message has already been finalized.
 */
class VoiceAssembler(
    private val chunkTimeoutSeconds: Int = 30,
    private val partialPlayOnTimeout: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val TAG = "VoiceAssembler"
        /** AMR-NB file header: "#!AMR\n" */
        val AMR_HEADER = byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A)
        /** AMR-NB NO_DATA silence frame (frame type 15) */
        private val SILENCE_FRAME = byteArrayOf(0x7C)
        /** How long to remember completed messages to reject late duplicates */
        private const val BLACKLIST_EXPIRY_MS = 60_000L
        /** Max blacklist size to prevent unbounded growth */
        private const val MAX_BLACKLIST_SIZE = 100
    }

    /**
     * Key for tracking in-progress voice messages: (senderNodeId, messageId).
     */
    private data class AssemblyKey(val from: String, val messageId: Int)

    /**
     * Internal state for an in-progress voice message assembly.
     */
    private class AssemblyState(
        val from: String,
        val messageId: Int,
        val totalChunks: Int,
        val bitrateIndex: Int,
        val destinationId: String = "",
        val channel: Int = 0,
        val startTime: Long = System.currentTimeMillis()
    ) {
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
        var timeoutJob: Job? = null

        val isComplete: Boolean
            get() = chunks.size == totalChunks

        val receivedCount: Int
            get() = chunks.size
    }

    private val mutex = Mutex()
    private val assemblies = mutableMapOf<AssemblyKey, AssemblyState>()

    /** Recently-completed message keys with their completion timestamp */
    private val recentlyCompleted = LinkedHashMap<AssemblyKey, Long>()

    private val _completedMessages = MutableSharedFlow<VoiceMessage>(extraBufferCapacity = 16)
    val completedMessages: SharedFlow<VoiceMessage> = _completedMessages.asSharedFlow()

    /**
     * Feed an incoming voice data chunk into the assembler.
     *
     * @param from sender node ID
     * @param chunkData raw chunk bytes (header + payload)
     * @param destinationId the destination node ID (or broadcast)
     * @param channel the Meshtastic channel index
     */
    fun onChunkReceived(from: String, chunkData: ByteArray, destinationId: String = "", channel: Int = 0) {
        val header = VoiceChunker.parseHeader(chunkData) ?: run {
            Log.w(TAG, "Invalid chunk header from $from")
            return
        }

        val payload = VoiceChunker.extractPayload(chunkData)
        val key = AssemblyKey(from, header.messageId)

        scope.launch {
            mutex.withLock {
                // Reject chunks for recently-completed messages (late duplicates)
                if (recentlyCompleted.containsKey(key)) {
                    Log.d(TAG, "Ignoring late chunk for completed msg ${key.messageId} from ${key.from}")
                    return@withLock
                }

                val state = assemblies.getOrPut(key) {
                    val newState = AssemblyState(
                        from = from,
                        messageId = header.messageId,
                        totalChunks = header.totalChunks,
                        bitrateIndex = header.bitrateIndex,
                        destinationId = destinationId,
                        channel = channel
                    )
                    // Start timeout timer
                    newState.timeoutJob = scope.launch {
                        delay(chunkTimeoutSeconds * 1000L)
                        onTimeout(key)
                    }
                    newState
                }

                // Store the chunk (ignore duplicates)
                if (!state.chunks.containsKey(header.chunkIndex)) {
                    state.chunks[header.chunkIndex] = payload
                    Log.d(TAG, "Chunk ${header.chunkIndex + 1}/${header.totalChunks} " +
                            "for msg ${header.messageId} from $from")
                }

                // Check if complete
                if (state.isComplete) {
                    state.timeoutJob?.cancel()
                    finalizeMessage(key, state, isPartial = false)
                }
            }
        }
    }

    /**
     * Called when the timeout elapses for an incomplete message.
     */
    private suspend fun onTimeout(key: AssemblyKey) {
        mutex.withLock {
            val state = assemblies[key] ?: return
            Log.w(TAG, "Timeout for msg ${key.messageId} from ${key.from}: " +
                    "${state.receivedCount}/${state.totalChunks} chunks received")

            if (partialPlayOnTimeout && state.receivedCount > 0) {
                finalizeMessage(key, state, isPartial = true)
            } else {
                assemblies.remove(key)
                Log.w(TAG, "Discarding incomplete message ${key.messageId}")
            }
        }
    }

    /**
     * Assemble audio data from received chunks and emit a completed VoiceMessage.
     * Must be called while holding [mutex].
     */
    private fun finalizeMessage(key: AssemblyKey, state: AssemblyState, isPartial: Boolean) {
        val audioData = assembleAudio(state)
        val message = VoiceMessage(
            messageId = state.messageId,
            from = state.from,
            to = state.destinationId,
            audioData = audioData,
            timestamp = state.startTime,
            isComplete = !isPartial,
            totalChunks = state.totalChunks,
            receivedChunks = state.receivedCount,
            bitrateIndex = state.bitrateIndex,
            channel = state.channel
        )
        assemblies.remove(key)

        // Add to blacklist to reject late duplicates
        addToBlacklist(key)

        _completedMessages.tryEmit(message)

        if (isPartial) {
            Log.i(TAG, "Emitting partial voice message ${state.messageId}: " +
                    "${state.receivedCount}/${state.totalChunks} chunks")
        } else {
            Log.i(TAG, "Emitting complete voice message ${state.messageId}")
        }
    }

    /**
     * Concatenate chunks in order, filling silence for missing ones.
     * Returns a valid AMR-NB byte stream (with file header).
     *
     * Missing chunks are replaced with the correct number of silence frames
     * based on the bitrate's frame size, preserving audio timeline alignment.
     */
    private fun assembleAudio(state: AssemblyState): ByteArray {
        val output = ByteArrayOutputStream()
        val bitrate = AmrNbBitrate.entries.getOrElse(state.bitrateIndex) { AmrNbBitrate.MR795 }

        // Write AMR-NB file header (chunks carry raw frames, no file header)
        output.write(AMR_HEADER)

        // Write chunks in order
        for (i in 0 until state.totalChunks) {
            val chunkPayload = state.chunks[i]
            if (chunkPayload != null) {
                output.write(chunkPayload)
            } else {
                // Calculate how many AMR frames would fit in a full chunk payload,
                // then emit that many silence frames to preserve timing.
                val expectedPayloadSize = VoiceChunker.MAX_PAYLOAD_SIZE
                val silenceFrameCount = bitrate.framesIn(expectedPayloadSize).coerceAtLeast(1)
                repeat(silenceFrameCount) {
                    output.write(SILENCE_FRAME)
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * Track a recently-completed message to reject late duplicate chunks.
     * Evicts entries older than [BLACKLIST_EXPIRY_MS] and caps at [MAX_BLACKLIST_SIZE].
     */
    private fun addToBlacklist(key: AssemblyKey) {
        val now = System.currentTimeMillis()

        // Evict expired entries
        val iterator = recentlyCompleted.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > BLACKLIST_EXPIRY_MS) {
                iterator.remove()
            } else {
                break // LinkedHashMap is insertion-ordered, so later entries are newer
            }
        }

        // Cap size
        while (recentlyCompleted.size >= MAX_BLACKLIST_SIZE) {
            recentlyCompleted.entries.iterator().let { it.next(); it.remove() }
        }

        recentlyCompleted[key] = now
    }

    /**
     * Get the number of messages currently being assembled.
     */
    fun pendingCount(): Int = runBlocking { mutex.withLock { assemblies.size } }

    /**
     * Clear all pending assemblies and cancel timeouts.
     */
    fun clear() {
        runBlocking {
            mutex.withLock {
                assemblies.values.forEach { it.timeoutJob?.cancel() }
                assemblies.clear()
                recentlyCompleted.clear()
            }
        }
    }

    /**
     * Shut down the assembler and release resources.
     */
    fun destroy() {
        clear()
        scope.cancel()
    }
}
