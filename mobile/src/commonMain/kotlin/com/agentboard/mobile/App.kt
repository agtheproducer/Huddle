package com.agentboard.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentboard.core.AgentState
import com.agentboard.core.Conflict
import com.agentboard.core.EventType
import com.agentboard.core.PostedEvent
import com.agentboard.mobile.resources.Res
import com.agentboard.mobile.resources.bob_logo
import com.agentboard.mobile.resources.otter_wave
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

// ---- BOB on iPhone: palette ----
private val Bg = Color(0xFFFAFAFA)
private val Ink = Color(0xFF1F2328)
private val Muted = Color(0xFF6B7280)
private val Line = Color(0xFFE4E6EA)
private val Blue = Color(0xFF2F6BD8)
private val BlueBg = Color(0xFFF3F6FE)
private val BlueChip = Color(0xFFE3EBFD)
private val DeepTeal = Color(0xFF26454D)
private val Teal = Color(0xFF0B5357)
private val GreyChip = Color(0xFFEEF0F3)
private val GreyChipText = Color(0xFF4B5563)
private val GreenChip = Color(0xFFE6F2EA)
private val GreenChipText = Color(0xFF2E7D4F)
private val Orange = Color(0xFFE06A00)
private val ConflictBg = Color(0xFFFFF1E4)
private val Field = Color(0xFFF4F4F5)
private val OtherBubble = Color(0xFFF0F1F3)

private val Named = mapOf(
    "julieta" to Color(0xFF177A62),
    "amit" to Color(0xFFB23A6E),
    "codex" to Color(0xFFA55B16),
    "gelinas" to Color(0xFF3C6E9E),
)
private val People = listOf(0xFF177A62, 0xFFB23A6E, 0xFFA55B16, 0xFF3C6E9E, 0xFF7A4C7A, 0xFF48494B).map { Color(it) }

private fun personOf(agent: String) = agent.substringBefore('-').lowercase()
private fun colorFor(agent: String) = Named[personOf(agent)] ?: People[(personOf(agent).hashCode() and 0x7fffffff) % People.size]
private fun initials(agent: String) =
    agent.split('-', '_', ' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
private fun providerOf(agent: String): String? = when {
    agent.endsWith("claude") -> "Claude"
    agent.endsWith("junie") -> "Junie"
    agent.endsWith("codex") -> "Codex"
    else -> null
}
private fun PostedEvent.isAuto() = event.message?.trimStart()?.startsWith("[auto]") == true

/**
 * iOS keeps the keyboard up until focus leaves the text field. Tapping outside a field or dragging
 * a list closes it, as in native iOS apps. Programmatic scrolls (a new message arriving) do not.
 */
@Composable
private fun Modifier.dismissKeyboard(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val connection = remember(focus, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    focus.clearFocus()
                    keyboard?.hide()
                }
                return Offset.Zero
            }
        }
    }
    return this.nestedScroll(connection).pointerInput(focus, keyboard) {
        detectTapGestures(onTap = {
            focus.clearFocus()
            keyboard?.hide()
        })
    }
}

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val model = remember { BoardModel(scope) }
    LaunchedEffect(model.name) { if (model.name != null) model.start() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Bg, surface = Color.White)) {
        if (model.name == null) WelcomeScreen(model) else MainScreen(model)
    }
}

// ---- welcome ----

@Composable
private fun WelcomeScreen(model: BoardModel) {
    var asking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val clean = BoardModel.clean(name).ifBlank { "name" }
    Column(
        Modifier.fillMaxSize().background(Bg).dismissKeyboard().safeDrawingPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Image(painterResource(Res.drawable.bob_logo), "bob", Modifier.width(220.dp))
        Spacer(Modifier.weight(1.2f))
        if (!asking) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(40.dp)).background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(40.dp)).clickable { asking = true }
                    .padding(horizontal = 26.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Join your team's board", fontSize = 17.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("Your agents and teammates, live", fontSize = 13.sp, color = Muted)
                }
                Text("›", fontSize = 26.sp, color = Muted)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(Res.drawable.otter_wave), null, Modifier.height(56.dp))
                Spacer(Modifier.width(12.dp))
                Text("What's your name?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Field)
                    .border(1.dp, Line, RoundedCornerShape(30.dp)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text("Your first name, e.g. amit", color = Muted, fontSize = 17.sp)
                    BasicTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = Ink), cursorBrush = SolidColor(Blue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { model.join(name) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SendButton(enabled = name.isNotBlank()) { model.join(name) }
            }
            Text(
                "You post as \"$clean\", your agent is \"$clean-claude\".",
                fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Skip", fontSize = 17.sp, color = Ink,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { model.join("guest") }.padding(14.dp),
        )
        Text(
            "bob coordinates AI coding agents. By using it you agree to our Terms and Privacy Policy.",
            fontSize = 12.sp, color = Teal, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp, top = 6.dp),
        )
    }
}

// ---- main screen ----

@Composable
private fun MainScreen(model: BoardModel) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(model) { showSettings = false }
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var autoHint by remember { mutableStateOf(false) }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            DrawerContent(model, onPick = { model.conversation = it; tab = 0; scope.launch { drawer.close() } })
        },
    ) {
        Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().dismissKeyboard().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(onClick = { scope.launch { drawer.open() } }) { MenuIcon() }
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).clickable { autoHint = !autoHint }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp, 24.dp).clip(CircleShape).background(Color(0xFFDDE3EA)).padding(3.dp)) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-reply", fontSize = 17.sp, color = Muted)
                }
                Spacer(Modifier.weight(1f))
                CircleButton(onClick = { model.refresh() }) { RefreshIcon() }
                Spacer(Modifier.width(10.dp))
                CircleButton(onClick = { showSettings = true }) { GearIcon() }
            }
            if (autoHint) {
                Text(
                    "Auto-reply runs on your computer: turn it on in the BOB panel in IntelliJ, and your agent answers the team while you're away.",
                    fontSize = 13.sp, color = Ink, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(BlueBg).clickable { autoHint = false }.padding(12.dp),
                )
            }
            Tabs(tab, model.state?.agents?.size ?: 0) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth().dismissKeyboard()) {
                if (tab == 0) Overview(model) else AgentsList(model) { model.conversation = it; tab = 0 }
            }
            model.error?.let {
                Text(
                    if (model.source == Source.HTTP && !model.live) "Offline: $it" else it,
                    fontSize = 12.sp, color = Orange, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Composer(model)
            Footer(model)
        }
    }
}

@Composable
private fun Tabs(tab: Int, agents: Int, onTab: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            TabItem("Overview", null, tab == 0) { onTab(0) }
            Spacer(Modifier.width(56.dp))
            TabItem("Agents", agents, tab == 1) { onTab(1) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(Line))
    }
}

@Composable
private fun TabItem(title: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick).padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, fontSize = 18.sp, color = if (selected) Blue else Muted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(GreyChip).padding(horizontal = 7.dp, vertical = 1.dp)) {
                    Text("$count", fontSize = 14.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Blue else Color.Transparent))
    }
}

// ---- overview: conflicts, the latest handoff, and the conversation ----

@Composable
private fun Overview(model: BoardModel) {
    val state = model.state
    if (state == null) {
        Centered(if (model.error != null) "Can't reach the board right now." else "Loading the board…")
        return
    }
    val c = model.conversation
    val messages = state.timeline.filter { p ->
        (p.event.type == EventType.MESSAGE || p.event.type == EventType.PROGRESS) &&
            (c == null || p.event.agent == c || p.event.toAgent == c)
    }.takeLast(120)
    val handoff = state.timeline.lastOrNull { it.event.type == EventType.HANDOFF }
        ?.takeIf { c == null || it.event.agent == c || it.event.toAgent == c }
    val conflicts = state.openConflicts
    if (messages.isEmpty() && handoff == null && conflicts.isEmpty() && c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(painterResource(Res.drawable.bob_logo), "bob", Modifier.width(200.dp))
        }
        return
    }
    val total = (if (c != null) 1 else 0) + conflicts.size + (if (handoff != null) 1 else 0) + messages.size
    val list = rememberLazyListState()
    LaunchedEffect(total, c) { if (total > 0) list.animateScrollToItem(total - 1) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        if (c != null) {
            item {
                Row(
                    Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(20.dp)).background(BlueChip)
                        .clickable { model.conversation = null }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Conversation with $c", fontSize = 13.sp, color = Blue, fontWeight = FontWeight.SemiBold)
                    Text("   ✕", fontSize = 13.sp, color = Blue)
                }
            }
        }
        items(conflicts) { ConflictCard(it) }
        if (handoff != null) item { HandoffCard(handoff) }
        items(messages.size, key = { messages[it].commentId }) { i ->
            val p = messages[i]
            if (p.isAuto()) SystemLine("${p.event.agent} is on it", p) else Bubble(model, p, continues(messages.getOrNull(i - 1), p))
        }
    }
}

private fun continues(prev: PostedEvent?, p: PostedEvent): Boolean =
    prev != null && !prev.isAuto() && prev.event.agent == p.event.agent && prev.event.toAgent == p.event.toAgent

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, fontSize = 15.sp, color = Muted) }
}

@Composable
private fun HandoffCard(p: PostedEvent) {
    val e = p.event
    val to = e.toAgent ?: "?"
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("→", fontSize = 20.sp, color = Blue)
            Spacer(Modifier.width(10.dp))
            Text("Work handed over", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            Text(Platform.clock(p.createdAt), fontSize = 13.sp, color = Muted)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(e.agent, 24); Spacer(Modifier.width(8.dp))
            Text(e.agent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colorFor(e.agent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("   →   ", fontSize = 15.sp, color = Muted)
            Avatar(to, 24); Spacer(Modifier.width(8.dp))
            Text(to, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colorFor(to), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        e.summary?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 15.sp, color = Ink, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            e.taskId?.let { Chip(it, GreyChip, GreyChipText); Spacer(Modifier.width(6.dp)) }
            e.branch?.let { Chip(it, GreyChip, GreyChipText) }
            Spacer(Modifier.weight(1f))
            e.commit?.let { commit ->
                Text(
                    "commit $commit ↗", fontSize = 13.sp, color = Blue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { runCatching { uri.openUri("https://github.com/${BoardModel.REPO}/commit/$commit") } }
                        .padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun ConflictCard(c: Conflict) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(ConflictBg)
            .border(1.dp, Orange, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Orange)
            Spacer(Modifier.width(10.dp))
            Text("Conflict" + (c.taskId?.let { " · $it" } ?: ""), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Orange)
        }
        Text(
            "${c.claimant} tried to claim what ${c.currentOwner} already owns" +
                (c.overlappingPaths.takeIf { it.isNotEmpty() }?.let { ": " + it.joinToString(", ") { p -> p.substringAfterLast('/') } } ?: "."),
            fontSize = 15.sp, color = Ink, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Bubble(model: BoardModel, p: PostedEvent, grouped: Boolean) {
    val e = p.event
    val mine = model.isMine(e.agent)
    val text = (e.message ?: e.summary ?: "").trim()
    val to = e.toAgent?.let { if (it == model.name) "you" else if (it == model.agentId) "your agent" else it }
    Column(
        Modifier.fillMaxWidth().padding(top = if (grouped) 3.dp else 12.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (mine) {
            if (!grouped) {
                Text(
                    (if (e.agent == model.name) "You" else "Your agent") + (to?.let { " → $it" } ?: ""),
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(end = 6.dp, bottom = 3.dp),
                )
            }
            Box(
                Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(Teal)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) { Text(text, color = Color.White, fontSize = 16.sp, lineHeight = 21.sp) }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                if (grouped) Spacer(Modifier.width(34.dp)) else {
                    Avatar(e.agent, 28)
                    Spacer(Modifier.width(6.dp))
                }
                Column {
                    if (!grouped) {
                        Row(Modifier.padding(start = 6.dp, bottom = 3.dp)) {
                            Text(e.agent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colorFor(e.agent))
                            to?.let { Text("  → $it", fontSize = 12.sp, color = Muted) }
                        }
                    }
                    val forYou = e.toAgent != null && (e.toAgent == model.name || e.toAgent == model.agentId)
                    val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                    Box(
                        Modifier.widthIn(max = 280.dp).clip(shape).background(if (forYou) BlueBg else OtherBubble)
                            .then(if (forYou) Modifier.border(1.5.dp, Blue, shape) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) { Text(text, color = Ink, fontSize = 16.sp, lineHeight = 21.sp) }
                }
            }
        }
        Text(
            Platform.ago(p.createdAt), fontSize = 11.sp, color = Muted,
            modifier = Modifier.padding(top = 2.dp, start = if (mine) 0.dp else 40.dp, end = if (mine) 6.dp else 0.dp),
        )
    }
}

@Composable
private fun SystemLine(text: String, p: PostedEvent) {
    Text(
        "$text · ${Platform.ago(p.createdAt)}", fontSize = 12.sp, color = Muted,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 24.dp),
    )
}

// ---- agents ----

@Composable
private fun AgentsList(model: BoardModel, onPick: (String) -> Unit) {
    val state = model.state
    if (state == null) {
        Centered(if (model.error != null) "Can't reach the board right now." else "Loading the board…")
        return
    }
    val agents = state.agents.values.sortedWith(compareBy({ it.agent != model.agentId }, { it.agent }))
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("AGENTS ${agents.size}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
        if (agents.isEmpty()) item { Text("No agents yet. They appear when they post.", fontSize = 15.sp, color = Muted) }
        items(agents, key = { it.agent }) { a -> AgentCard(model, a) { onPick(a.agent) } }
    }
}

@Composable
private fun AgentCard(model: BoardModel, a: AgentState, onClick: () -> Unit) {
    val mine = a.agent == model.agentId
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (mine) BlueBg else Color.White)
            .border(if (mine) 1.5.dp else 1.dp, if (mine) Blue else Line, shape)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(a.agent, 36)
            Spacer(Modifier.width(12.dp))
            Text(
                a.agent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colorFor(a.agent),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            providerOf(a.agent)?.let { Spacer(Modifier.width(8.dp)); Chip(it, GreyChip, GreyChipText) }
            if (mine) { Spacer(Modifier.width(6.dp)); Chip("you", BlueChip, Blue) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(Platform.ago(a.lastSeen), fontSize = 13.sp, color = Muted, maxLines = 1)
        }
        Row(Modifier.padding(start = 48.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (a.activeTasks.isEmpty()) {
                Text("Free, no task claimed", fontSize = 14.sp, color = Muted)
            } else {
                a.activeTasks.take(2).forEach { Chip(it, GreenChip, GreenChipText, bold = true); Spacer(Modifier.width(8.dp)) }
                a.branch?.let { Text(it, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---- composer and footer ----

@Composable
private fun Composer(model: BoardModel) {
    var text by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val live = model.source == Source.HTTP
    val agents = model.state?.agents?.keys.orEmpty().filter { it != model.agentId }.sorted()
    val send: () -> Unit = { if (model.send(text)) { text = "" } }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(shape).background(Field)
            .border(1.5.dp, Color(0xFFDADCE0), shape).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(model.agentId.ifBlank { "?" }, 28)
            Spacer(Modifier.width(10.dp))
            Text(
                if (live) "You post as \"${model.name}\"" else "Switch to HTTP to message the agents.",
                fontSize = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { picking = true }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("To:", fontSize = 15.sp, color = Muted)
                    Text(
                        model.conversation ?: "Everyone", fontSize = 15.sp, color = Ink, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    ChevronDown()
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    DropdownMenuItem(text = { Text("Everyone") }, onClick = { model.conversation = null; picking = false })
                    agents.forEach { a ->
                        DropdownMenuItem(text = { Text(a) }, leadingIcon = { Avatar(a, 22) }, onClick = { model.conversation = a; picking = false })
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (text.isEmpty()) Text("Message the agents...", color = Muted, fontSize = 17.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontSize = 17.sp, color = Ink),
                    cursorBrush = SolidColor(Blue),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SendButton(enabled = live && text.isNotBlank() && !model.sending, onClick = send)
        }
    }
}

@Composable
private fun Footer(model: BoardModel) {
    var open by remember { mutableStateOf(false) }
    val status = when {
        model.source != Source.HTTP -> "Demo stage"
        model.error != null && !model.live -> "Offline"
        model.lastUpdated != null -> "Last updated ${model.lastUpdated}"
        else -> "Loading…"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(8.dp))
                    .clickable { open = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(model.source.label, fontSize = 16.sp, color = Ink)
                Spacer(Modifier.width(16.dp))
                ChevronDown()
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Source.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label, color = if (s == model.source) Blue else Ink) },
                        trailingIcon = { if (s == model.source) Text("✓", color = Blue) },
                        onClick = { model.pick(s); open = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(status, fontSize = 14.sp, color = Muted, maxLines = 1)
    }
}

// ---- drawer ----

@Composable
private fun DrawerContent(model: BoardModel, onPick: (String?) -> Unit) {
    val timeline = model.state?.timeline.orEmpty()
    val agents = model.state?.agents?.keys.orEmpty().filter { !model.isMine(it) }.sorted()
    ModalDrawerSheet(drawerContainerColor = Color(0xFFF2F2F3), modifier = Modifier.width(310.dp)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Image(painterResource(Res.drawable.bob_logo), "bob", Modifier.padding(top = 16.dp).width(84.dp))
            Text("Conversations", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                DrawerItem("Everyone", preview(timeline, null), model.conversation == null, null) { onPick(null) }
                agents.forEach { a -> DrawerItem(a, preview(timeline, a), model.conversation == a, a) { onPick(a) } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(model.agentId.ifBlank { "?" }, 36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.name.orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("Your agent: ${model.agentId}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun preview(timeline: List<PostedEvent>, with: String?): String =
    timeline.lastOrNull { p ->
        p.event.type == EventType.MESSAGE && !p.isAuto() && (with == null || p.event.agent == with || p.event.toAgent == with)
    }?.let { "${it.event.agent}: ${it.event.message.orEmpty()}" } ?: "No messages yet"

@Composable
private fun DrawerItem(title: String, subtitle: String, selected: Boolean, agent: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) BlueChip else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (agent != null) Avatar(agent, 32) else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
            Text("★", color = Color.White, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 16.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- settings ----

@Composable
private fun SettingsScreen(model: BoardModel, onClose: () -> Unit) {
    var url by remember { mutableStateOf(model.boardUrl) }
    var name by remember { mutableStateOf(model.name.orEmpty()) }
    Column(Modifier.fillMaxSize().background(Bg).dismissKeyboard().safeDrawingPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 8.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(onClick = onClose) { BackIcon() }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Agent Board", fontSize = 17.sp, color = Muted)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FieldLabel("HTTP server URL")
            InputBox(url, { url = it }, keyboardType = KeyboardType.Uri)
            Help("Real-time mode: point to the Agent Board server (e.g., http://localhost:3000). Your team's address is in the repo's .bob/board.json.")
            Spacer(Modifier.height(24.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Your name")
                    InputBox(name, { name = it })
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("Your agent id")
                    InputBox(BoardModel.clean(name).ifBlank { "name" } + "-claude", {}, enabled = false)
                }
            }
            Help("You post as your name. Your agent posts as your agent id.")
            Spacer(Modifier.height(24.dp))
            FieldLabel("GitHub Issue board")
            Help("Repository, Issue and GitHub token are set in the IntelliJ plugin, where the token is kept in the macOS Keychain. On the phone, BOB uses the HTTP board.")
            Spacer(Modifier.height(24.dp))
            Text(
                "Sign out", fontSize = 16.sp, color = Orange, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { model.signOut(); onClose() }.padding(vertical = 8.dp),
            )
        }
        Row(Modifier.padding(vertical = 12.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable(onClick = onClose).padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.weight(1.8f).clip(RoundedCornerShape(16.dp)).background(DeepTeal)
                    .clickable {
                        model.changeUrl(url)
                        if (BoardModel.clean(name).isNotEmpty()) model.join(name)
                        onClose()
                    }.padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Save changes", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Help(text: String) {
    Text(text, fontSize = 14.sp, color = Muted, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun InputBox(value: String, onChange: (String) -> Unit, enabled: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text) {
    val focus = LocalFocusManager.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 15.dp),
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
            textStyle = TextStyle(fontSize = 16.sp, color = if (enabled) Ink else Muted), cursorBrush = SolidColor(Blue),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- small pieces ----

@Composable
private fun Avatar(agent: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(colorFor(agent)), contentAlignment = Alignment.Center) {
        Text(initials(agent), color = Color.White, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, bold: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, fontSize = 14.sp, color = fg, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (enabled) DeepTeal else DeepTeal.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SendIcon() }
}

@Composable
private fun CircleButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color.White).border(1.dp, Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

// Icons drawn as vectors, so the app needs no icon font.
private val IconStroke = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable private fun MenuIcon() = Canvas(Modifier.size(20.dp)) {
    drawLine(Ink, Offset(size.width * 0.1f, size.height * 0.35f), Offset(size.width * 0.9f, size.height * 0.35f), 4.5f, StrokeCap.Round)
    drawLine(Ink, Offset(size.width * 0.1f, size.height * 0.65f), Offset(size.width * 0.6f, size.height * 0.65f), 4.5f, StrokeCap.Round)
}

@Composable private fun ChevronDown() = Canvas(Modifier.size(12.dp)) {
    val p = Path().apply {
        moveTo(size.width * 0.1f, size.height * 0.3f)
        lineTo(size.width * 0.5f, size.height * 0.7f)
        lineTo(size.width * 0.9f, size.height * 0.3f)
    }
    drawPath(p, Muted, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable private fun BackIcon() = Canvas(Modifier.size(18.dp)) {
    val p = Path().apply {
        moveTo(size.width * 0.65f, size.height * 0.1f)
        lineTo(size.width * 0.25f, size.height * 0.5f)
        lineTo(size.width * 0.65f, size.height * 0.9f)
    }
    drawPath(p, Ink, style = IconStroke)
}

@Composable private fun RefreshIcon() = Canvas(Modifier.size(22.dp)) {
    drawArc(Ink, -60f, 300f, false, style = IconStroke)
    val p = Path().apply {
        moveTo(size.width * 0.78f, size.height * 0.02f)
        lineTo(size.width * 0.78f, size.height * 0.30f)
        lineTo(size.width * 0.50f, size.height * 0.30f)
    }
    drawPath(p, Ink, style = IconStroke)
}

@Composable private fun GearIcon() = Canvas(Modifier.size(22.dp)) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension / 2
    for (i in 0 until 8) {
        val a = i * kotlin.math.PI / 4
        drawLine(
            Ink,
            Offset(c.x + (r * 0.62f) * kotlin.math.cos(a).toFloat(), c.y + (r * 0.62f) * kotlin.math.sin(a).toFloat()),
            Offset(c.x + (r * 0.98f) * kotlin.math.cos(a).toFloat(), c.y + (r * 0.98f) * kotlin.math.sin(a).toFloat()),
            5f, StrokeCap.Round,
        )
    }
    drawCircle(Ink, r * 0.66f, c, style = IconStroke)
    drawCircle(Ink, r * 0.24f, c, style = IconStroke)
}

@Composable private fun SendIcon() = Canvas(Modifier.size(18.dp)) {
    val p = Path().apply {
        moveTo(size.width * 0.95f, size.height * 0.05f)
        lineTo(size.width * 0.05f, size.height * 0.45f)
        lineTo(size.width * 0.45f, size.height * 0.55f)
        lineTo(size.width * 0.55f, size.height * 0.95f)
        close()
        moveTo(size.width * 0.95f, size.height * 0.05f)
        lineTo(size.width * 0.45f, size.height * 0.55f)
    }
    drawPath(p, Color.White, style = Stroke(width = 3.5f, join = StrokeJoin.Round, cap = StrokeCap.Round))
}
