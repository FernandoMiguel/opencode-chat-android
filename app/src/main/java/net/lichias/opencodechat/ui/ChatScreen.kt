@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.lichias.opencodechat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lichias.opencodechat.R
import net.lichias.opencodechat.data.ChatMessage
import net.lichias.opencodechat.data.Effort
import net.lichias.opencodechat.data.Privacy
import net.lichias.opencodechat.data.Session
import net.lichias.opencodechat.ui.theme.GradientBackdrop

@Composable
fun ChatApp(vm: ChatViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    if (showSettings) {
        SettingsScreen(vm = vm, onBack = { showSettings = false })
    } else {
        ChatScreen(vm = vm, onOpenSettings = { showSettings = true })
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                windowInsets = WindowInsets(0),
                modifier = Modifier.width(300.dp),
            ) {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.id == vm.currentId,
                            onClick = {
                                vm.selectSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteSession(session.id) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GradientBackdrop(Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                TopPanel(
                    vm = vm,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
                MessageList(vm, Modifier.weight(1f))
                InputBar(vm)
            }
        }
    }
}

@Composable
private fun TopPanel(vm: ChatViewModel, onOpenDrawer: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Filled.Menu, contentDescription = "Sessions")
        }
        ModelSelector(vm, Modifier.weight(1f))
        val ephemeralActive = vm.current?.ephemeral == true
        IconButton(
            onClick = vm::newEphemeralChat,
            modifier = Modifier.background(
                if (ephemeralActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else Color.Transparent,
                CircleShape,
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_ghost),
                contentDescription = if (ephemeralActive) "Ephemeral chat active" else "New ephemeral chat",
                tint = if (ephemeralActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = vm::newChat) {
            Icon(Icons.Filled.Edit, contentDescription = "New chat")
        }
    }
}

@Composable
private fun ModelSelector(vm: ChatViewModel, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = vm.model,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            placeholder = {
                Text(if (vm.modelsLoading) "Loading models…" else "Select a model", maxLines = 1)
            },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_dropdown), contentDescription = null)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(14.dp))
                .clickable { showPicker = true }
        )
    }
    if (showPicker) {
        ModelPickerSheet(vm = vm, onDismiss = { showPicker = false })
    }
}

@Composable
private fun ModelPickerSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var query by remember { mutableStateOf("") }

    val all = vm.orderedModels()
    val queryFiltered = if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) }
    val filtered = queryFiltered
    val freeTotal = vm.orderedModels(unfiltered = true).count { vm.isFree(it) }
    val zdrTotal = vm.zdrCount()
    val trainsTotal = vm.trainsCount()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Choose a model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("${filtered.size} models")
                    if (freeTotal > 0) append(" · $freeTotal free")
                    if (zdrTotal > 0) append(" · $zdrTotal ZDR")
                    if (trainsTotal > 0) append(" · $trainsTotal train")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.setFreeOnly(!vm.showFreeOnly) }) {
                Text(if (vm.showFreeOnly) "FREE ✓" else "FREE")
            }
            if (zdrTotal > 0) {
                TextButton(onClick = { vm.setZdrOnly(!vm.showZdrOnly) }) {
                    Text(if (vm.showZdrOnly) "ZDR ✓" else "ZDR")
                }
            }
        }
        if (zdrTotal > 0 || trainsTotal > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (zdrTotal > 0) {
                    Icon(
                        painterResource(R.drawable.ic_shield_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "ZDR endpoint available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trainsTotal > 0) {
                    Icon(
                        painterResource(R.drawable.ic_shield_cross),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "May train on prompts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(filtered, key = { it }) { id ->
                val pinned = id in vm.favorites
                val free = vm.isFree(id)
                val privacy = vm.privacyOf(id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.selectModel(id)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pinned) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (pinned) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (id == vm.model) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (free) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                "FREE",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    when (privacy) {
                        Privacy.ZDR -> {
                            Icon(
                                painterResource(R.drawable.ic_shield_check),
                                contentDescription = "ZDR endpoint available",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Privacy.TRAINS -> {
                            Icon(
                                painterResource(R.drawable.ic_shield_cross),
                                contentDescription = "May train on prompts",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Privacy.UNKNOWN -> {}
                    }
                    IconButton(onClick = { vm.toggleFavorite(id) }) {
                        Icon(
                            if (pinned) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (pinned) "Unpin" else "Pin to top",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "No models match",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val messages = vm.current?.messages ?: emptyList()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, vm.streamingText?.length, vm.currentId) {
        val target = messages.size + (if (vm.streamingText != null) 0 else -1)
        if (target >= 0) listState.animateScrollToItem(target.coerceAtLeast(0))
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && vm.streamingText == null) {
                item {
                    Text(
                        text = when {
                            vm.provider.needsApiKey && vm.apiKey.isBlank() ->
                                "Add your ${vm.provider.label} API key in Settings to start chatting."
                            vm.modelsError != null -> vm.modelsError!!
                            else -> "Say something to get started."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.92f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                    )
                }
            }
            itemsIndexed(messages) { _, message -> Bubble(message) }
            vm.streamingText?.let { partial ->
                item { Bubble(ChatMessage("assistant", partial.ifEmpty { "…" })) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val colorScheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) colorScheme.primary else colorScheme.surfaceContainer,
            contentColor = if (isUser) colorScheme.onPrimary else colorScheme.onSurface,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp).combinedClickable(
                onClick = {},
                onLongClick = {
                    if (message.text.isNotBlank()) {
                        clipboard.setText(AnnotatedString(message.text))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                message.imageDataUrl?.let { dataUrl ->
                    val bitmap by produceState<ImageBitmap?>(null, dataUrl) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.NO_WRAP)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Attached photo",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (message.text.isNotEmpty()) Text(message.text, color = if (isUser) colorScheme.onPrimary else colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun InputBar(vm: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val attachedDataUrl = vm.pendingImage

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { loadDataUrl(context, uri) }
            if (dataUrl != null) vm.putPendingImage(dataUrl)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        attachedDataUrl?.let {
            AttachedPreview(it) { vm.clearPendingImage() }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(painterResource(R.drawable.ic_photo), contentDescription = "Attach photo", tint = MaterialTheme.colorScheme.onSurface)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            EffortSelector(vm)
            IconButton(
                enabled = !vm.busy && (text.isNotBlank() || vm.pendingImage != null),
                onClick = {
                    val body = text
                    val image = attachedDataUrl
                    text = ""
                    vm.send(body, image)
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (!vm.busy && (text.isNotBlank() || vm.pendingImage != null)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

@Composable
private fun EffortSelector(vm: ChatViewModel) {
    val efforts = vm.effortsFor(vm.model).map(::Effort)
    if (efforts.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(Effort(vm.effort).label, style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(R.drawable.ic_dropdown), contentDescription = "Effort level")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Reasoning effort",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            efforts.forEach { e ->
                DropdownMenuItem(
                    text = {
                        Text(
                            e.label,
                            fontWeight = if (e.id == vm.effort) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        vm.changeEffort(e.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachedPreview(dataUrl: String, onRemove: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, dataUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Photo attached", style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove photo", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: Session,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLowest
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                val subtitle = buildString {
                    append(if (session.messages.size == 1) "1 message" else "${session.messages.size} messages")
                    if (session.ephemeral) append(" · ephemeral")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.ephemeral) {
                Icon(
                    painterResource(R.drawable.ic_ghost),
                    contentDescription = "Ephemeral session",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun loadDataUrl(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()
