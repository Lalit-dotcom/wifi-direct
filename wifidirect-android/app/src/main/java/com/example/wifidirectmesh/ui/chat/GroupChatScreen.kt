package com.example.wifidirectmesh.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wifidirectmesh.MainActivity
import com.example.wifidirectmesh.data.MeshManager
import com.example.wifidirectmesh.data.ChatMessage
import com.wifidirect.mesh.sync.TransferProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messagesMap by MeshManager.messages.collectAsState()
    val groupMessages = messagesMap["GROUP_CHAT"] ?: emptyList()
    val transfers by MeshManager.transfers.collectAsState()
    
    val imgCount = MeshManager.getDailyImageCount()
    val byteCount = MeshManager.getDailyByteCount()
    
    val remainingImages = maxOf(0, 20 - imgCount)
    val remainingMb = maxOf(0f, 100f - (byteCount.toFloat() / (1024f * 1024f)))

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(groupMessages.size) {
        if (groupMessages.isNotEmpty()) {
            listState.animateScrollToItem(groupMessages.size - 1)
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val context = MainActivity.currentContext
            if (context != null) {
                val name = getFileName(context, uri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    MeshManager.sendGroupFile(name, bytes)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Group Chat Room",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "All connected nodes are here",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Limits Quota Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Remaining images: $remainingImages/20",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Remaining quota: ${"%.1f".format(remainingMb)} MB / 100 MB",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (groupMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .wrapContentSize(Alignment.Center)
                        ) {
                            Text(
                                text = "Group Chat is empty.\nType below to message everyone in the mesh!",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(groupMessages) { msg ->
                        GroupMessageBubble(message = msg, transfers = transfers)
                    }
                }
            }

            // Input Row
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { pickerLauncher.launch("*/*") },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Group message...") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )

                    Button(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                MeshManager.sendGroupMessage(textInput)
                                textInput = ""
                            }
                        },
                        enabled = textInput.isNotBlank(),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Send", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun GroupMessageBubble(
    message: ChatMessage,
    transfers: Map<String, TransferProgress>
) {
    val bubbleColor = if (message.isSentByMe) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = if (message.isSentByMe) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    val bubbleShape = if (message.isSentByMe) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }

    val alignment = if (message.isSentByMe) Alignment.End else Alignment.Start
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Sender Name / Node ID label
        Text(
            text = "${message.senderName ?: "Unknown"} (${message.senderId.take(6)})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Box(
            modifier = Modifier
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 280.dp)
                .wrapContentHeight()
        ) {
            Column {
                if (message.filePath != null) {
                    val file = File(message.filePath)
                    if (message.isImage && file.exists()) {
                        val bitmap = remember(message.filePath) {
                            android.graphics.BitmapFactory.decodeFile(message.filePath)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Inline Image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Text("Image load failed", color = contentColor, fontSize = 14.sp)
                        }
                    } else {
                        // File attachment card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "📄 ${message.fileName ?: "Attachment"}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = contentColor
                                )
                                Text(
                                    text = "${message.fileSize / 1024} KB",
                                    fontSize = 11.sp,
                                    color = contentColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else if (message.fileName != null) {
                    // This is a manifest for an incomplete download or active transfer
                    val activeTransfer = transfers.values.find { it.fileName == message.fileName && !it.isComplete }
                    if (activeTransfer != null) {
                        val percentage = (activeTransfer.bytesTransferred.toFloat() / activeTransfer.totalBytes.toFloat()) * 100
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = "Transferring: ${message.fileName}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                            LinearProgressIndicator(
                                progress = { percentage / 100f },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${"%.1f".format(percentage)}%",
                                    fontSize = 11.sp,
                                    color = contentColor
                                )
                                Text(
                                    text = "${activeTransfer.bytesTransferred / 1024} KB / ${activeTransfer.totalBytes / 1024} KB",
                                    fontSize = 11.sp,
                                    color = contentColor
                                )
                            }

                            if (activeTransfer.isFailed) {
                                Button(
                                    onClick = { MeshManager.resumeFileTransfer(activeTransfer.peerId, activeTransfer.bundleId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally)
                                ) {
                                    Text("Resume", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Waiting for transfer: ${message.fileName}",
                            color = contentColor,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        color = contentColor,
                        fontSize = 15.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = timeFormat.format(Date(message.timestamp)),
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var name = "file"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex != -1) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}
