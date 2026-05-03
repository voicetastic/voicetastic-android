package re.chasam.voicetastic.voice

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import re.chasam.voicetastic.model.VoiceMessage
import java.io.ByteArrayOutputStream

/**
 * Reassembles incoming voice chunks into complete voice messages.
 *
 * Handles out-of-order delivery, missing chunks (with timeout + partial play),
 * and concurrent messages from different senders.
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

    private val assemblies = mutableMapOf<AssemblyKey, AssemblyState>()

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

        synchronized(assemblies) {
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

    /**
     * Called when the timeout elapses for an incomplete message.
     */
    private fun onTimeout(key: AssemblyKey) {
        synchronized(assemblies) {
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
     */
    private fun assembleAudio(state: AssemblyState): ByteArray {
        val output = ByteArrayOutputStream()

        // Write AMR-NB file header
        output.write(AMR_HEADER)

        // Write chunks in order
        for (i in 0 until state.totalChunks) {
            val chunkPayload = state.chunks[i]
            if (chunkPayload != null) {
                output.write(chunkPayload)
            } else {
                // Fill with silence frame for missing chunk
                // AMR-NB silence frame (NO_DATA mode, ~1 byte)
                output.write(createSilenceFrame())
            }
        }

        return output.toByteArray()
    }

    /**
     * Create a minimal AMR-NB silence frame.
     * Frame type 15 (NO_DATA) = single byte 0x7C
     */
    private fun createSilenceFrame(): ByteArray {
        // AMR-NB NO_DATA frame: frame type 15, padded
        return byteArrayOf(0x7C)
    }

    /**
     * Get the number of messages currently being assembled.
     */
    fun pendingCount(): Int = synchronized(assemblies) { assemblies.size }

    /**
     * Clear all pending assemblies and cancel timeouts.
     */
    fun clear() {
        synchronized(assemblies) {
            assemblies.values.forEach { it.timeoutJob?.cancel() }
            assemblies.clear()
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


