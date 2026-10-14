package com.ahaverty.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AssistTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Full-screen overlay: a light scrim that does nothing when touched, and the panel at the bottom. */
@Composable
fun AssistOverlay(convo: Conversation, onClose: () -> Unit, onOpenClaude: () -> Unit, onOpenUrl: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            // Swallow taps on the scrim: dismissing is only ever deliberate.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        AssistPanel(
            convo = convo,
            onClose = onClose,
            onOpenClaude = onOpenClaude,
            onOpenUrl = onOpenUrl,
            modifier = Modifier.navigationBarsPadding().imePadding(),
        )
    }
}

@Composable
fun AssistPanel(
    convo: Conversation,
    onClose: () -> Unit,
    onOpenClaude: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by convo.state.collectAsState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxPanel = maxHeight * 0.82f
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .heightIn(max = maxPanel)
                .animateContentSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                Header(state, onClose = onClose, onNew = convo::newChat, onOpenClaude = onOpenClaude, onToggleSpeak = convo::toggleSpeak)
                Messages(state, onOpenUrl, Modifier.weight(1f, fill = false))
                state.notice?.let { Notice(it, onDismiss = convo::clearNotice) }
                InputRow(state, convo)
            }
        }
    }
}

@Composable
private fun Header(state: UiState, onClose: () -> Unit, onNew: () -> Unit, onOpenClaude: () -> Unit, onToggleSpeak: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Config.ASSISTANT_NAME, style = MaterialTheme.typography.titleMedium)
            val status = when (state.phase) {
                Phase.Listening -> "Listening…"
                Phase.Thinking -> "Thinking…"
                Phase.Speaking -> "Speaking"
                Phase.Idle -> null
            }
            AnimatedVisibility(status != null) {
                Text(status ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onToggleSpeak) {
            Icon(
                if (state.speakReplies) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (state.speakReplies) "Spoken replies on" else "Spoken replies off",
            )
        }
        if (state.messages.isNotEmpty()) {
            TextButton(onClick = onOpenClaude) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Claude")
            }
            IconButton(onClick = onNew) { Icon(Icons.Filled.EditNote, contentDescription = "New chat") }
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
}

@Composable
private fun Messages(state: UiState, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val lastLen = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLen, state.partial) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (state.messages.isEmpty() && state.partial.isEmpty()) {
            item {
                Text(
                    if (state.phase == Phase.Listening) "Go ahead, I'm listening" else "Hi Alan. What can I do?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
        items(state.messages, key = { it.id }) { m ->
            if (m.fromUser) UserBubble(m.text) else Reply(m, onOpenUrl)
        }
        if (state.partial.isNotEmpty()) {
            item(key = "partial") { UserBubble(state.partial, live = true) }
        }
    }
}

@Composable
private fun UserBubble(text: String, live: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            color = if (live) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The bridge streams "Checking where Sam is…" progress paragraphs into the reply. They show as small
 * status lines (with a spinner on the newest while still working) instead of being part of the answer.
 */
@Composable
private fun Reply(m: Msg, onOpenUrl: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val paragraphs = m.text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth().padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (paragraphs.isEmpty() && m.streaming) TypingDots()
        paragraphs.forEachIndexed { i, p ->
            val isStatus = p.endsWith("…") && p.length < 80 && !p.contains('\n')
            if (isStatus) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.streaming && i == paragraphs.lastIndex) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(p, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                }
            } else {
                SelectionContainer {
                    Text(
                        remember(p, linkColor) { richText(p, linkColor, onOpenUrl) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (m.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), label = "dot$i",
            )
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = a)))
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onDismiss),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InputRow(state: UiState, convo: Conversation) {
    var typed by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = typed,
            onValueChange = {
                if (typed.isEmpty() && it.isNotEmpty()) convo.typingStarted()
                typed = it
            },
            placeholder = { Text("Ask ${Config.ASSISTANT_NAME}") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(26.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                convo.sendTyped(typed)
                typed = ""
            }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        Spacer(Modifier.width(10.dp))
        if (typed.isNotBlank()) {
            FilledIconButton(onClick = { convo.sendTyped(typed); typed = "" }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        } else {
            MicButton(state, onClick = convo::micPressed)
        }
    }
}

@Composable
private fun MicButton(state: UiState, onClick: () -> Unit) {
    val listening = state.phase == Phase.Listening
    val ring by animateFloatAsState(if (listening) 1f + state.level * 0.35f else 1f, tween(90), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        AnimatedVisibility(listening, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut()) {
            Box(
                Modifier.size(56.dp).scale(ring).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            )
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = if (listening) IconButtonDefaults.filledIconButtonColors()
            else IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        ) {
            Icon(
                when (state.phase) {
                    Phase.Listening -> Icons.Filled.GraphicEq
                    Phase.Speaking -> Icons.Filled.Stop
                    else -> Icons.Filled.Mic
                },
                contentDescription = when (state.phase) {
                    Phase.Listening -> "Done talking"
                    Phase.Speaking -> "Stop speaking and listen"
                    else -> "Talk"
                },
            )
        }
    }
}
