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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    text = "Mesh: $connectionState",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showChannelPicker = true }) {
                    Icon(Icons.Default.Forum, contentDescription = "Select channel", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    val channelLabel = availableChannels.find { it.first == selectedChannel }?.second
                        ?: "Ch $selectedChannel"
                    Text(channelLabel)
                }
                TextButton(onClick = { showNodePicker = true }) {
                    Icon(Icons.Default.People, contentDescription = "Select node", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(selectedNode?.shortName ?: "Broadcast")
                }
            }
        }

        // Sending progress for voice
        sendingProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text = "Sending voice message… ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
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
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                    Text(
                        text = "🔴 Recording…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    FilledTonalButton(
                        onClick = { viewModel.stopRecordingAndSend() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        Spacer(Modifier.width(4.dp))
                        Text("Send")
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
                        placeholder = { Text("Type a message…") },
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
                            contentDescription = "Record voice message",
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
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
                        contentDescription = if (isPlaying) "Stop" else "Play"
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
                        text = "🎤 Voice · ${item.audioData.size / 1024}KB" +
                                if (!item.isComplete) " (${item.receivedChunks}/${item.totalChunks})" else "",
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
                        contentDescription = "Incomplete",
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
        title = { Text("Send to") },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("Broadcast (all nodes)") },
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("Select Channel") },
        text = {
            LazyColumn {
                items(channels) { (index, name) ->
                    ListItem(
                        headlineContent = { Text(name.ifBlank { "Channel $index" }) },
                        supportingContent = { Text("Channel $index") },
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

