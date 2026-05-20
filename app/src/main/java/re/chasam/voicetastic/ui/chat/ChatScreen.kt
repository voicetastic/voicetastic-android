package re.chasam.voicetastic.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import re.chasam.voicetastic.model.MeshNode
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MessagingViewModel) {
    val chatItems by viewModel.chatItems.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val availableChannels by viewModel.availableChannels.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
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
                // Recording mode UI
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
                    FilledTonalButton(
                        onClick = { viewModel.stopRecordingAndSend() },
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
                Text(
                    text = timeFormat.format(Date(item.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End)
                )
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
    onNodeSelected: (MeshNode?) -> Unit,
    onDismiss: () -> Unit
) {
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

