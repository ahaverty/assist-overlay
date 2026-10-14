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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AssistTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Full-screen overlay: a light scrim that does nothing when touched, and the panel at the bottom. */
@Composable
fun AssistOverlay(
    convo: Conversation,
    onClose: () -> Unit,
    onTuck: (left: Boolean) -> Unit,
    onOpenClaude: () -> Unit,
    onOpenUrl: (String) -> Unit,
    windowPan: () -> Int = { 0 },
) {
    var overlay by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The screenshot button's grab: the screen shrinks from full size into the button's spot in the text bar.
    val grab = remember { Animatable(1f) }
    var grabFrom by remember { mutableStateOf<Rect?>(null) }
    var grabTo by remember { mutableStateOf<Rect?>(null) }
    var grabShot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            // Undo the system's pan of the window: imePadding() below already clears the keyboard.
            .graphicsLayer { translationY = windowPan().toFloat() }
            .onGloballyPositioned { overlay = it }
            .background(Color.Black.copy(alpha = 0.32f))
            // Swallow taps on the scrim: dismissing is only ever deliberate.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        AssistPanel(
            convo = convo,
            onClose = onClose,
            onTuck = onTuck,
            onOpenClaude = onOpenClaude,
            onOpenUrl = onOpenUrl,
            modifier = Modifier.navigationBarsPadding().imePadding(),
            onGrab = { button, shot ->
                val root = overlay
                if (root != null && root.isAttached && button.isAttached) {
                    // The screenshot is the whole display, and the overlay window covers it all too.
                    grabFrom = Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
                    grabTo = root.localBoundingBoxOf(button)
                    grabShot = shot
                    scope.launch {
                        grab.snapTo(0f)
                        grab.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                    }
                }
            },
        )
        val from = grabFrom
        val to = grabTo
        if (grab.value < 1f && from != null && to != null) {
            Box(Modifier.matchParentSize()) { GrabFlight(grab.value, from, to, grabShot) }
        }
    }
}

/** One frame of the grab: a white flash and a little shrink at full size, then the flight into the text bar. */
@Composable
private fun GrabFlight(t: Float, from: Rect, to: Rect, shot: android.graphics.Bitmap?) {
    val density = LocalDensity.current
    val hold = 0.22f
    val fly = ((t - hold) / (1f - hold)).coerceIn(0f, 1f)
    val pop = (t / hold).coerceIn(0f, 1f)
    val start = Rect(from.left + from.width * 0.05f, from.top + from.height * 0.05f, from.right - from.width * 0.05f, from.bottom - from.height * 0.05f)
    val r = if (t < hold) lerp(from, start, pop) else lerp(start, to, fly)
    val corner = lerp(0.dp, 10.dp, maxOf(pop, fly))
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .graphicsLayer { shadowElevation = 24f * (1f - fly); this.shape = shape; clip = true }
            .border(2.dp, Color.White.copy(alpha = 0.9f), shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val img = remember(shot) { shot?.asImageBitmap() }
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Smartphone, contentDescription = null, modifier = Modifier.size(lerp(96.dp, 18.dp, fly)))
        // Camera-style flash as the screen is caught.
        val flash = (1f - t / 0.18f).coerceIn(0f, 1f)
        if (flash > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.75f * flash)))
    }
}

@Composable
fun AssistPanel(
    convo: Conversation,
    onClose: () -> Unit,
    onOpenClaude: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTuck: ((left: Boolean) -> Unit)? = null,
    onGrab: ((button: LayoutCoordinates, shot: android.graphics.Bitmap?) -> Unit)? = null,
) {
    val state by convo.state.collectAsState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxPanel = maxHeight * 0.82f
        // Fling the panel left or right, from anywhere on it, to tuck it into that edge. The panel watches
        // touches before its children do (Initial pass) and only takes one over once it is clearly sideways
        // (well past touch slop, twice as far across as down), so scrolling, taps and typing are untouched.
        // A held mic button keeps its finger. Then it still needs a real fling or a drag 40% of the way.
        val widthPx = constraints.maxWidth.toFloat()
        val drag = remember { Animatable(0f) }
        val dragScope = rememberCoroutineScope()
        val micDown = remember { mutableStateOf(false) }
        // The panel stays off screen after a tuck (snapping back before the window hides flashes it);
        // it comes back to the middle when the panel is shown again.
        LifecycleResumeEffect(Unit) {
            dragScope.launch { drag.snapTo(0f) }
            onPauseOrDispose { }
        }
        val swipeToTuck = if (onTuck == null) Modifier else Modifier.pointerInput(onTuck) {
            val tracker = VelocityTracker()
            val claimSlop = viewConfiguration.touchSlop * 2f
            val flingSpeed = 900.dp.toPx()
            val flingMin = 56.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                var moved = Offset.Zero
                var claimed = false
                var lifted = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        lifted = true
                        if (claimed) {
                            val x = drag.value
                            val v = tracker.calculateVelocity().x
                            val flung = abs(v) > flingSpeed && abs(x) > flingMin && sign(v) == sign(x)
                            dragScope.launch {
                                if (flung || abs(x) > widthPx * 0.4f) {
                                    drag.animateTo(sign(x) * widthPx * 1.1f, tween(140))
                                    onTuck(x < 0)
                                } else {
                                    drag.animateTo(0f)
                                }
                            }
                        }
                        break
                    }
                    val delta = change.position - change.previousPosition
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!claimed) {
                        moved += delta
                        if (micDown.value || abs(moved.y) > viewConfiguration.touchSlop * 1.5f) break
                        if (abs(moved.x) > claimSlop && abs(moved.x) > 2 * abs(moved.y)) claimed = true
                        else continue
                    }
                    change.consume()
                    dragScope.launch { drag.snapTo(drag.value + delta.x) }
                }
                if (claimed && !lifted) {
                    dragScope.launch { drag.animateTo(0f) }
                }
            }
        }
        Surface(
            modifier = Modifier
                .graphicsLayer {
                    translationX = drag.value
                    alpha = 1f - (abs(drag.value) / widthPx).coerceIn(0f, 0.6f)
                }
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .heightIn(max = maxPanel)
                .animateContentSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(swipeToTuck.padding(top = 8.dp, bottom = 12.dp)) {
                Header(
                    state,
                    modifier = Modifier,
                    onClose = onClose,
                    onNew = convo::newChat,
                    onOpenClaude = onOpenClaude,
                    onToggleSpeak = convo::toggleSpeak,
                    onHistory = { convo.showHistory(!state.showHistory) },
                )
                if (state.showHistory) {
                    val chats by convo.history.collectAsState()
                    HistoryList(chats, onOpen = convo::resume, onDelete = convo::deleteChat, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Messages(state, onOpenUrl, Modifier.weight(1f, fill = false))
                    state.notice?.let { Notice(it, onDismiss = convo::clearNotice) }
                    state.attachment?.takeIf { it.shared }?.let { AttachmentChip(it, onToggle = convo::toggleAttachment, onDrop = convo::dropAttachment) }
                    InputRow(state, convo, micDown, onGrab)
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: UiState,
    modifier: Modifier,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onOpenClaude: () -> Unit,
    onToggleSpeak: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // The title opens the less used options (recent chats, Claude, spoken replies) so the header stays clear.
            var menu by remember { mutableStateOf(false) }
            if (state.showHistory) {
                Text("Recent chats", style = MaterialTheme.typography.titleMedium)
            } else Box {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Setup.assistantName, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "More options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Recent chats") },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                        onClick = { menu = false; onHistory() },
                    )
                    if (state.messages.isNotEmpty()) DropdownMenuItem(
                        text = { Text("Continue in Claude") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                        onClick = { menu = false; onOpenClaude() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.speakReplies) "Mute spoken replies" else "Speak replies") },
                        leadingIcon = {
                            Icon(if (state.speakReplies) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        },
                        onClick = { menu = false; onToggleSpeak() },
                    )
                }
            }
            val status = when (state.phase) {
                Phase.Listening -> when {
                    state.transcribing -> "Transcribing…"
                    state.holding -> "Listening until you let go…"
                    else -> "Listening…"
                }
                Phase.Thinking -> "Thinking…"
                Phase.Speaking -> "Speaking"
                Phase.Idle -> null
            }
            AnimatedVisibility(status != null) {
                Text(status ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (state.showHistory) IconButton(onClick = onHistory) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the chat")
        }
        if (state.messages.isNotEmpty() && !state.showHistory) {
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
                    if (state.phase == Phase.Listening) "Go ahead, I'm listening" else Setup.userName.let { if (it.isEmpty()) "Hi. What can I do?" else "Hi $it. What can I do?" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
        items(state.messages, key = { it.id }) { m ->
            if (m.fromUser) UserBubble(m.text, screen = m.screen) else Reply(m, onOpenUrl)
        }
        if (state.partial.isNotEmpty()) {
            item(key = "partial") { UserBubble(state.partial, live = true) }
        }
    }
}

@Composable
private fun UserBubble(text: String, live: Boolean = false, screen: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
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
        if (screen != null) Row(Modifier.padding(top = 3.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Screenshot, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("with your screen in $screen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // Paragraphs, with any ![name](ha:entity) line pulled out as its own picture/card block.
    val paragraphs = m.text.split("\n\n").flatMap { para ->
        val out = mutableListOf<String>()
        val text = StringBuilder()
        para.lines().forEach { line ->
            if (HA_EMBED.matches(line.trim())) {
                if (text.isNotBlank()) out += text.toString().trim()
                text.clear()
                out += line.trim()
            } else {
                text.append(line).append('\n')
            }
        }
        if (text.isNotBlank()) out += text.toString().trim()
        out
    }.filter { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth().padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (paragraphs.isEmpty() && m.streaming) TypingDots()
        paragraphs.forEachIndexed { i, p ->
            val embed = HA_EMBED.matchEntire(p)
            val isStatus = p.endsWith("…") && p.length < 80 && !p.contains('\n')
            if (embed != null) {
                val target = embed.groupValues[2]
                if (PhoneActions.isAction(target)) ActionChip(embed.groupValues[1], m.actions[target]) else Embed(embed.groupValues[1], target)
            } else if (isStatus) {
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
private fun InputRow(
    state: UiState,
    convo: Conversation,
    micDown: androidx.compose.runtime.MutableState<Boolean>,
    onGrab: ((LayoutCoordinates, android.graphics.Bitmap?) -> Unit)?,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val screen = state.attachment?.takeIf { !it.shared }
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
            placeholder = { Text(if (screen?.on == true) "Ask about this screen" else "Ask ${Setup.assistantName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = if (screen == null) null else {
                { ScreenButton(screen, onGrab = { button -> convo.grabScreen(); onGrab?.invoke(button, screen.shot) }, onDrop = convo::dropAttachment) }
            },
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
            MicButton(state, micDown, onClick = convo::micPressed, onHoldStart = convo::holdStart, onHoldEnd = convo::holdEnd)
        }
    }
}

@Composable
private fun MicButton(state: UiState, micDown: androidx.compose.runtime.MutableState<Boolean>, onClick: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val listening = state.phase == Phase.Listening
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ring by animateFloatAsState(if (listening) 1f + state.level * 0.35f else 1f, tween(90), label = "ring")
    val level = animateFloatAsState(if (listening && !state.transcribing) state.level else 0f, tween(80), label = "level")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        AnimatedVisibility(listening, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut()) {
            Box(contentAlignment = Alignment.Center) {
                Ripples(MaterialTheme.colorScheme.primary)
                Box(
                    Modifier.size(56.dp).scale(ring).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                )
            }
        }
        // Tap: talk (stops by itself at a pause). Hold: walkie-talkie, listens until you let go.
        val bg = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
        val fg = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(if (state.holding) 64.dp else 56.dp).clip(CircleShape).background(bg)
                .pointerInput(Unit) {
                    // Hold-to-talk follows the finger anywhere on screen: it only ends when the last finger
                    // lifts, not when it slides off the button.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        micDown.value = true
                        var held = false
                        val timer = scope.launch {
                            kotlinx.coroutines.delay(350)
                            held = true
                            onHoldStart()
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        timer.cancel()
                        micDown.value = false
                        if (held) onHoldEnd() else onClick()
                    }
                },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) {
            if (listening) VoiceBars(level, fg, Modifier.size(26.dp)) else Icon(
                when (state.phase) {
                    Phase.Speaking -> Icons.Filled.Stop
                    else -> Icons.Filled.Mic
                },
                contentDescription = when (state.phase) {
                    Phase.Listening -> "Done talking"
                    Phase.Speaking -> "Stop speaking and listen"
                    else -> "Talk, or hold to talk until you let go"
                },
            )
            }
        }
    }
}

/** Rings that keep spreading out from the mic while it listens, so it never looks frozen, even in silence. */
@Composable
private fun Ripples(color: Color) {
    val t = rememberInfiniteTransition(label = "ripples")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ripple")
    Canvas(Modifier.size(56.dp)) {
        repeat(3) { i ->
            val q = (p + i / 3f) % 1f
            drawCircle(color.copy(alpha = 0.35f * (1f - q)), radius = size.minDimension / 2f * (1f + 0.45f * q))
        }
    }
}

/** Four bars in place of the mic icon: they idle in a slow wave and jump with the voice level. */
@Composable
private fun VoiceBars(level: State<Float>, color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "wave")
    Canvas(modifier) {
        val n = 4
        val w = size.width / (n * 2f - 1f)
        val shape = floatArrayOf(0.7f, 1f, 0.85f, 0.6f)
        for (i in 0 until n) {
            val idle = 0.22f + 0.13f * (1f + sin(phase + i * 1.3f)) / 2f
            val voice = level.value.coerceIn(0f, 1f) * shape[i] * (0.75f + 0.25f * sin(phase * 2f + i))
            val h = size.height * (idle + voice).coerceIn(0.18f, 1f)
            drawRoundRect(color, topLeft = Offset(i * 2f * w, (size.height - h) / 2f), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = CornerRadius(w / 2f))
        }
    }
}

/**
 * The screenshot button at the right of the text bar. Tapping it catches the screen (the overlay plays the
 * grab) and the button becomes a little thumbnail of it; tapping the thumbnail lets it go again.
 */
@Composable
private fun ScreenButton(a: Attachment, onGrab: (LayoutCoordinates) -> Unit, onDrop: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(Modifier.size(48.dp).onGloballyPositioned { coords = it }, contentAlignment = Alignment.Center) {
        if (!a.on) {
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                coords?.let(onGrab)
            }) {
                Icon(Icons.Filled.Screenshot, contentDescription = "Ask about what's on screen (${a.label})", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // Fades in as the flying screenshot lands on it.
            val shown = remember { Animatable(0f) }
            LaunchedEffect(Unit) { shown.animateTo(1f, tween(120, delayMillis = 430)) }
            val img = remember(a.shot) { a.shot?.asImageBitmap() }
            val label = "Screen of ${a.label} goes with your question. Tap to remove"
            Box(
                Modifier.size(width = 26.dp, height = 38.dp)
                    .graphicsLayer { alpha = shown.value }
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onDrop),
                contentAlignment = Alignment.Center,
            ) {
                if (img != null) Image(img, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.Smartphone, contentDescription = label, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun HistoryList(chats: List<SavedChat>, onOpen: (SavedChat) -> Unit, onDelete: (SavedChat) -> Unit, modifier: Modifier = Modifier) {
    if (chats.isEmpty()) {
        Text(
            "No saved chats yet. Each conversation is kept here for ${History.DAYS} days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        items(chats, key = { it.id }) { c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(c) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ago(c.updated)} · ${c.snippet}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(c) }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun ago(t: Long): String {
    val mins = (System.currentTimeMillis() - t) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        mins < 48 * 60 -> "yesterday"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK).format(java.util.Date(t))
    }
}

/**
 * What goes along with the next question. Screen text is off until tapped (it also goes along by itself
 * when the question says "this", "page" and so on); shared text is on.
 */
@Composable
private fun AttachmentChip(a: Attachment, onToggle: () -> Unit, onDrop: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = a.on,
            onClick = onToggle,
            label = {
                Text(
                    if (a.shared) "Shared from ${a.label}" else if (a.on) "Sending screen: ${a.label}" else "Use screen: ${a.label}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { Icon(if (a.shared) Icons.Filled.Share else Icons.Filled.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onDrop) { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp)) }
    }
}
