package com.example.glassesassistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.glassesassistant.AssistantService.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssistantScreen(inner: PaddingValues, ui: UiPrefs, prefs: android.content.SharedPreferences, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val running = AssistantService.running
    val state = AssistantService.uiState
    val muted = AssistantService.muted
    val log = AssistantService.log
    var typed by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    val searchOn = (prefs.getString("search", "ddg") ?: "ddg") != "off"

    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    LaunchedEffect(state) {
        if (state == UiState.LISTENING && ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun buzz() { if (ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    fun ask(q: String) {
        if (q.isBlank() || !running) return
        buzz()
        AssistantService.send(ctx, AssistantService.ACTION_ASK, q.trim())
    }

    val quick = buildList {
        add(stringResource(R.string.chip_time) to stringResource(R.string.q_time))
        add(stringResource(R.string.chip_weather) to stringResource(R.string.q_weather))
        add(stringResource(R.string.chip_rates) to stringResource(R.string.q_rates))
        if (searchOn) add(stringResource(R.string.chip_news) to stringResource(R.string.q_news))
        add(stringResource(R.string.chip_more) to stringResource(R.string.q_more))
    }
    val translatePrefix = stringResource(R.string.translate_prefix)

    Column(
        Modifier.padding(inner).consumeWindowInsets(inner).imePadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- состояние: орб, подпись, статус ---
        Orb(state, running, Modifier.size(168.dp))
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stateTitle(state, running), style = MaterialTheme.typography.headlineSmall)
            if (running) Text(AssistantService.status, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        // --- управление ---
        if (running) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { buzz(); AssistantService.send(ctx, AssistantService.ACTION_PTT) },
                    modifier = Modifier.weight(2f).height(64.dp),
                ) { Text(stringResource(R.string.btn_talk), style = MaterialTheme.typography.titleMedium) }
                FilledTonalButton(
                    onClick = { AssistantService.send(ctx, AssistantService.ACTION_HUSH) },
                    modifier = Modifier.weight(1f).height(64.dp), contentPadding = PaddingValues(4.dp),
                ) { Text(stringResource(R.string.btn_hush), textAlign = TextAlign.Center) }
                FilledTonalButton(
                    onClick = { AssistantService.send(ctx, AssistantService.ACTION_MUTE) },
                    colors = if (muted) ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.weight(1f).height(64.dp), contentPadding = PaddingValues(4.dp),
                ) { Text(stringResource(if (muted) R.string.btn_mic_on else R.string.btn_mic_off), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item { SuggestionChip({ AssistantService.send(ctx, AssistantService.ACTION_REPEAT) }, { Text(stringResource(R.string.chip_repeat)) }) }
                items(quick) { (label, q) -> SuggestionChip({ ask(q) }, { Text(label) }) }
                item { SuggestionChip({ typed = translatePrefix; inputFocus.requestFocus() }, { Text(stringResource(R.string.chip_translate)) }) }
                item { SuggestionChip({ ctx.stopService(android.content.Intent(ctx, AssistantService::class.java)) },
                    { Text(stringResource(R.string.chip_stop)) }, colors = SuggestionChipDefaults.suggestionChipColors(
                        labelColor = MaterialTheme.colorScheme.error)) }
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(stringResource(R.string.btn_start), style = MaterialTheme.typography.titleMedium)
            }
        }

        // --- история ---
        Surface(
            Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            if (log.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (running) stringResource(R.string.hint_empty_running, prefs.getString("wake", "") ?: "")
                    else stringResource(R.string.hint_empty_stopped),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(log) { Bubble(it) }
            }
        }

        // --- ввод текстом ---
        OutlinedTextField(
            typed, { typed = it }, enabled = running, singleLine = true,
            placeholder = { Text(stringResource(R.string.input_placeholder)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(inputFocus),
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { ask(typed); typed = "" }),
            trailingIcon = { TextButton({ ask(typed); typed = "" }, enabled = running && typed.isNotBlank()) { Text(stringResource(R.string.btn_send)) } },
        )
    }
}

@Composable
private fun stateTitle(s: UiState, running: Boolean) = stringResource(if (!running) R.string.state_not_running else when (s) {
    UiState.WAITING -> R.string.state_waiting
    UiState.LISTENING -> R.string.state_listening
    UiState.THINKING -> R.string.state_thinking
    UiState.SPEAKING -> R.string.state_speaking
    UiState.MUTED -> R.string.state_muted
    UiState.IDLE -> R.string.state_connecting
})

@Composable
private fun Bubble(m: AssistantService.Msg) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when {
        m.error -> cs.errorContainer to cs.onErrorContainer
        m.fromUser -> cs.primaryContainer to cs.onPrimaryContainer
        else -> cs.surfaceContainerHighest to cs.onSurface
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = bg, contentColor = fg, modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (m.fromUser) 20.dp else 4.dp, bottomEnd = if (m.fromUser) 4.dp else 20.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.time)),
                    style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/** Анимированный индикатор состояния: у каждого состояния свои цвет и движение. */
@Composable
private fun Orb(state: UiState, running: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val s = if (running) state else UiState.IDLE
    val target = when (s) {
        UiState.IDLE -> cs.outline
        UiState.WAITING -> cs.secondary
        UiState.LISTENING -> cs.primary
        UiState.THINKING -> cs.tertiary
        UiState.SPEAKING -> cs.primary
        UiState.MUTED -> cs.error
    }
    val color by animateColorAsState(target, tween(400), label = "color")
    val amp by animateFloatAsState(when (s) {
        UiState.IDLE, UiState.MUTED -> 0f
        UiState.WAITING -> 0.35f
        UiState.THINKING -> 0.25f
        UiState.LISTENING -> 1f
        UiState.SPEAKING -> 0.8f
    }, tween(400), label = "amp")
    val period = when (s) { UiState.LISTENING -> 900; UiState.SPEAKING -> 600; UiState.THINKING -> 1400; else -> 3000 }
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "spin")

    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val core = size.minDimension * 0.28f
        for (i in 1..3) {
            val r = core * (1f + 0.22f * i * (0.4f + amp * pulse))
            drawCircle(color.copy(alpha = (0.22f - 0.05f * i) * (0.5f + amp)), r, c)
        }
        drawCircle(color, core * (1f + 0.06f * amp * pulse), c)
        if (s == UiState.THINKING) {
            val r = core * 1.55f
            drawArc(color, spin, 110f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
