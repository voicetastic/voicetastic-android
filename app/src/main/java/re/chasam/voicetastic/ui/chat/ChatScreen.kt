package re.chasam.voicetastic.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import re.chasam.voicetastic.R
import kotlinx.coroutines.launch
import re.chasam.voicetastic.model.ChatItem
import re.chasam.voicetastic.service.DeliveryStatus
import re.chasam.voicetastic.model.MeshNode
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MessagingViewModel) {
    val chatItems by viewModel.chatItems.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val nodeHistory by viewModel.nodeHistory.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val availableChannels by viewModel.availableChannels.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val previewFile by viewModel.previewFile.collectAsState()
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playingItemId by viewModel.playingItemId.collectAsState()
    val sendingProgress by viewModel.sendingProgress.collectAsState()
    val incomingProgress by viewModel.incomingProgress.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showNodePicker by remember { mutableStateOf(false) }
    var showChannelPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) {
            listState.animateScrollToItem(chatItems.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Connection status bar
        Surface(
            color = when (connectionState) {
                "CONNECTED" -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.chat_status_connected, connectionState),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showChannelPicker = true }) {
                    Icon(Icons.Default.Forum, contentDescription = stringResource(R.string.chat_select_channel), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    val channelLabel = availableChannels.find { it.first == selectedChannel }?.second
                        ?: stringResource(R.string.chat_channel_label, selectedChannel)
                    Text(channelLabel)
                }
                TextButton(onClick = { showNodePicker = true }) {
                    Icon(Icons.Default.People, contentDescription = stringResource(R.string.chat_select_node), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedNode?.shortName ?: stringResource(R.string.chat_broadcast))
                }
            }
        }

        // Both progress banners are scoped to the currently-visible
        // conversation, matching the same `(contactKey, channel)` pair the
        // chatItems filter uses. Sending to a node you've since switched
        // away from won't leak its progress into the new conversation.
        val currentConversationKey = selectedNode?.nodeId ?: "broadcast"

        // Sending progress for voice (only when sending in this conversation)
        sendingProgress
            ?.takeIf { it.contactKey == currentConversationKey && it.channel == selectedChannel }
            ?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    text = stringResource(R.string.chat_sending_voice, progress.sent, progress.total),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

        // Receiving progress: one row per in-flight inbound voice message
        // belonging to *this* conversation.
        val visibleIncoming = incomingProgress.values.filter {
            it.contactKey == currentConversationKey && it.channel == selectedChannel
        }
        if (visibleIncoming.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                visibleIncoming.forEach { rx ->
                    LinearProgressIndicator(
                        progress = { rx.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.chat_receiving_voice,
                            rx.from,
                            rx.received,
                            rx.total
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(chatItems, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.Text -> TextMessageBubble(item)
                    is ChatItem.Voice -> VoiceMessageBubble(
                        item = item,
                        isPlaying = isPlaying && playingItemId == item.id,
                        onPlayClick = { viewModel.playVoiceMessage(item) }
                    )
                }
            }
        }

        // Input bar
        Surface(
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRecording) {
                // Recording mode UI: Stop drops the clip into Preview.
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { viewModel.cancelRecording() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_cancel))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_cancel))
                    }
                    Text(
                        text = stringResource(R.string.chat_recording),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    FilledTonalButton(onClick = { viewModel.stopRecordingToPreview() }) {
                        Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.chat_stop))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_stop))
                    }
                }
            } else if (previewFile != null) {
                // Preview mode: Listen / Delete / Send the captured clip
                // before transmitting. Mirrors desktop's VoiceCompose::Preview.
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { viewModel.discardPreview() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.chat_preview_delete))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_preview_delete))
                    }
                    if (isPreviewPlaying) {
                        OutlinedButton(onClick = { viewModel.stopPreviewPlayback() }) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.chat_stop))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.chat_stop))
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.playPreview() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.chat_preview_listen))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.chat_preview_listen))
                        }
                    }
                    FilledTonalButton(
                        onClick = { viewModel.sendPreview() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_send))
                    }
                }
            } else {
                // Normal text input + mic button
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    // Mic button – start voice recording
                    IconButton(
                        onClick = { viewModel.startRecording() },
                        enabled = connectionState == "CONNECTED"
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = stringResource(R.string.chat_record_voice),
                            tint = if (connectionState == "CONNECTED")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    // Send text button
                    FilledIconButton(
                        onClick = {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                            scope.launch {
                                if (chatItems.isNotEmpty()) {
                                    listState.animateScrollToItem(chatItems.lastIndex)
                                }
                            }
                        },
                        enabled = inputText.isNotBlank() && connectionState == "CONNECTED"
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }

    // Node picker dialog
    if (showNodePicker) {
        NodePickerDialog(
            nodes = nodes,
            selectedNode = selectedNode,
            nodeHistory = nodeHistory,
            onNodeSelected = { node ->
                viewModel.selectNode(node)
                showNodePicker = false
            },
            onDismiss = { showNodePicker = false }
        )
    }

    // Channel picker dialog
    if (showChannelPicker) {
        ChannelPickerDialog(
            channels = availableChannels,
            selectedChannel = selectedChannel,
            onChannelSelected = { ch ->
                viewModel.selectChannel(ch)
                showChannelPicker = false
            },
            onDismiss = { showChannelPicker = false }
        )
    }
}

@Composable
private fun TextMessageBubble(item: ChatItem.Text) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val alignment = if (item.isOutgoing) Alignment.End else Alignment.Start
    val bgColor = if (item.isOutgoing)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bgColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!item.isOutgoing) {
                    Text(
                        text = item.from,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeFormat.format(Date(item.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                    )
                    item.deliveryStatus?.let { status ->
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when (status) {
                                DeliveryStatus.Pending -> "⏳"
                                DeliveryStatus.Delivered -> "✓"
                                DeliveryStatus.Failed -> "❌"
                                DeliveryStatus.TimedOut -> "⏱"
                                DeliveryStatus.Cancelled -> "⊘"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageBubble(
    item: ChatItem.Voice,
    isPlaying: Boolean,
    onPlayClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val alignment = if (item.isOutgoing) Alignment.End else Alignment.Start
    val bgColor = if (item.isOutgoing)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bgColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlayClick) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.chat_stop) else stringResource(R.string.chat_play)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (!item.isOutgoing) {
                        Text(
                            text = item.from,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_voice_label, item.audioData.size / 1024) +
                                if (!item.isComplete) " ${stringResource(R.string.chat_voice_incomplete, item.receivedChunks, item.totalChunks)}" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = timeFormat.format(Date(item.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp
                    )
                }
                if (!item.isComplete) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.chat_incomplete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NodePickerDialog(
    nodes: List<MeshNode>,
    selectedNode: MeshNode?,
    nodeHistory: Map<Int, List<re.chasam.voicetastic.service.NodeSample>>,
    onNodeSelected: (MeshNode?) -> Unit,
    onDismiss: () -> Unit
) {
    var detailNode by remember { mutableStateOf<MeshNode?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_send_to_title)) },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_broadcast_all)) },
                        modifier = Modifier.clickable { onNodeSelected(null) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selectedNode == null)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    )
                }
                items(nodes) { node ->
                    ListItem(
                        headlineContent = { Text(node.longName) },
                        supportingContent = { Text(node.nodeId) },
                        leadingContent = { Text(node.shortName, fontWeight = FontWeight.Bold) },
                        // Info button opens the detail dialog; the row's
                        // body still selects the node for messaging.
                        trailingContent = {
                            IconButton(onClick = { detailNode = node }) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource(R.string.chat_node_detail_title),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onNodeSelected(node) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selectedNode?.nodeId == node.nodeId)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        }
    )
    detailNode?.let { node ->
        val samples = re.chasam.voicetastic.core.NodeIds.nodeIdToNum(node.nodeId)
            ?.let { nodeHistory[it] }
            ?: emptyList()
        NodeDetailDialog(node = node, samples = samples, onDismiss = { detailNode = null })
    }
}

@Composable
private fun NodeDetailDialog(
    node: MeshNode,
    samples: List<re.chasam.voicetastic.service.NodeSample>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.longName.ifBlank { node.nodeId }) },
        text = {
            // A scrollable column of label / value pairs so the dialog
            // never overflows on devices with smaller heights.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                NodeDetailRow("ID", node.nodeId)
                NodeDetailRow("Short name", node.shortName)
                NodeDetailRow("HW model", node.hwModel.toString())
                NodeDetailRow("Role", node.role.toString())
                if (node.isLicensed) NodeDetailRow("HAM", "yes")
                NodeDetailRow("Channel", node.channel.toString())
                if (node.lastHeard != 0L) NodeDetailRow(
                    "Last heard",
                    "${formatRelativeAge(node.lastHeard)} (${node.lastHeard})",
                )
                node.snr?.let { NodeDetailRow("SNR", "%.1f dB".format(it)) }
                if (node.latitudeI != null && node.longitudeI != null) {
                    NodeDetailRow(
                        "Position",
                        "%.5f, %.5f".format(node.latitudeI / 1e7, node.longitudeI / 1e7),
                    )
                }
                node.altitude?.let { NodeDetailRow("Altitude", "$it m") }
                node.batteryLevel?.let {
                    NodeDetailRow("Battery", if (it == 101) "AC" else "$it%")
                }
                node.voltage?.let { NodeDetailRow("Voltage", "%.2f V".format(it)) }
                node.channelUtilization?.let { NodeDetailRow("Ch util", "%.1f%%".format(it)) }
                node.airUtilTx?.let { NodeDetailRow("Air util TX", "%.1f%%".format(it)) }
                node.uptimeSeconds?.let { NodeDetailRow("Uptime", formatUptime(it)) }
                if (node.viaMqtt) NodeDetailRow("Via MQTT", "yes")
                if (node.isFavorite) NodeDetailRow("Favorite", "yes")
                if (samples.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Trends",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val battery = samples.mapNotNull { it.battery?.toFloat() }
                    if (battery.size >= 2) {
                        SparklineRow("Battery", battery, lo = 0f, hi = 100f, color = MaterialTheme.colorScheme.primary)
                    }
                    val snr = samples.map { it.snr }
                    SparklineRow("SNR", snr, lo = -20f, hi = 20f, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )
}

@Composable
private fun NodeDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(112.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatRelativeAge(lastHeard: Long): String {
    if (lastHeard == 0L) return "—"
    val now = System.currentTimeMillis() / 1000L
    val age = (now - lastHeard).coerceAtLeast(0)
    return when {
        age < 60 -> "${age}s ago"
        age < 3600 -> "${age / 60}m ago"
        age < 86_400 -> "${age / 3600}h ago"
        else -> "${age / 86_400}d ago"
    }
}

private fun formatUptime(secs: Int): String {
    val s = secs % 60
    val m = (secs / 60) % 60
    val h = (secs / 3600) % 24
    val d = secs / 86_400
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
private fun ChannelPickerDialog(
    channels: List<Pair<Int, String>>,
    selectedChannel: Int,
    onChannelSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_select_channel_title)) },
        text = {
            LazyColumn {
                items(channels) { (index, name) ->
                    ListItem(
                        headlineContent = { Text(name.ifBlank { stringResource(R.string.chat_channel_entry, index) }) },
                        supportingContent = { Text(stringResource(R.string.chat_channel_entry, index)) },
                        modifier = Modifier.clickable { onChannelSelected(index) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selectedChannel == index)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        }
    )
}


@Composable
private fun SparklineRow(
    label: String,
    values: List<Float>,
    lo: Float,
    hi: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(112.dp),
        )
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .height(24.dp)
                .width(160.dp)
        ) {
            val span = (hi - lo).coerceAtLeast(0.0001f)
            val w = size.width
            val h = size.height
            // Background frame
            drawRect(
                color = color.copy(alpha = 0.25f),
                size = size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
            )
            if (values.size < 2) return@Canvas
            val step = w / (values.size - 1).coerceAtLeast(1)
            var prev: androidx.compose.ui.geometry.Offset? = null
            values.forEachIndexed { i, v ->
                val norm = ((v - lo) / span).coerceIn(0f, 1f)
                val pt = androidx.compose.ui.geometry.Offset(step * i, h - norm * h)
                prev?.let {
                    drawLine(color = color, start = it, end = pt, strokeWidth = 2f)
                }
                prev = pt
            }
        }
    }
}
