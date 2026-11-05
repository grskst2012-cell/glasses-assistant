package com.example.glassesassistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    inner: PaddingValues, prefs: android.content.SharedPreferences, ui: UiPrefs,
    models: List<String>, onModels: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var url by remember { mutableStateOf(prefs.getString("url", "https://api.deepseek.com") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "deepseek-chat") ?: "") }
    var wake by remember { mutableStateOf(prefs.getString("wake", "") ?: "") }
    var wakeOn by remember { mutableStateOf(prefs.getBoolean("wakeOn", true)) }
    var engine by remember { mutableStateOf(prefs.getString("engine", "android") ?: "android") }
    var barge by remember { mutableStateOf(prefs.getBoolean("barge", false)) }
    var media by remember { mutableStateOf(prefs.getBoolean("media", false)) }
    var lang by remember { mutableStateOf(prefs.getString("lang", Lang.systemDefault()) ?: "ru") }
    var city by remember { mutableStateOf(prefs.getString("city", "") ?: "") }
    var tools by remember { mutableStateOf(prefs.getBoolean("tools", true)) }
    var search by remember { mutableStateOf(prefs.getString("search", "ddg") ?: "ddg") }
    var tavily by remember { mutableStateOf(prefs.getString("tavily", "") ?: "") }
    var glasses by remember { mutableStateOf(prefs.getBoolean("useGlasses", true)) }
    var rate by remember { mutableFloatStateOf(prefs.getFloat("rate", 1.0f)) }
    var showKey by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var onlyFree by remember { mutableStateOf(false) }

    // слово пробуждения по умолчанию зависит от языка ассистента, пока пользователь его не менял
    LaunchedEffect(lang) {
        if (wake.isBlank() || wake == "ассистент" || wake == "assistant") wake = if (lang == "ru") "ассистент" else "assistant"
    }
    // настройки сохраняются сразу — сервис читает их при старте (город, скорость и поиск — на лету)
    LaunchedEffect(url, key, model, wake, wakeOn, engine, barge, media, lang, city, tools, search, tavily, glasses, rate) {
        prefs.edit().putString("url", url.trim()).putString("key", key.trim()).putString("model", model.trim())
            .putString("wake", wake.trim()).putBoolean("wakeOn", wakeOn).putString("engine", engine)
            .putBoolean("barge", barge).putBoolean("media", media).putString("lang", lang)
            .putString("city", city.trim()).putBoolean("tools", tools).putString("search", search)
            .putString("tavily", tavily.trim()).putBoolean("useGlasses", glasses).putFloat("rate", rate).apply()
    }

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    val sheetVisible = sheetState.targetValue != SheetValue.Hidden
    var closeRequest by remember { mutableIntStateOf(0) }
    var openRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(openRequest) { if (openRequest > 0) sheetState.partialExpand() }
    LaunchedEffect(closeRequest) { if (closeRequest > 0) sheetState.hide() }

    val failedPrefix = stringResource(R.string.models_load_failed, "")
    fun loadModels() {
        loading = true; info = ""
        val u = url; val k = key
        thread {
            val r = try { Result.success(LlmClient.listModels(u, k)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                loading = false
                r.onSuccess { onModels(it); query = ""; openRequest++ }
                r.onFailure { onModels(emptyList()); info = failedPrefix + it.message?.take(160) }
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = Modifier.padding(inner),
        sheetPeekHeight = 340.dp,
        sheetContent = {
            ModelsSheet(models, model, query, onlyFree, { query = it }, { onlyFree = it }, { model = it }, { closeRequest++ })
        },
    ) { sheetInner ->
        Column(
            // sheetInner.bottom всегда равен высоте шторки, даже когда она скрыта, — берём только верх;
            // место под открытую шторку резервирует Spacer внизу
            Modifier.padding(top = sheetInner.calculateTopPadding()).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- внешний вид ----------
            SectionCard(stringResource(R.string.sec_appearance)) {
                Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.bodyLarge)
                Segmented(listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark),
                    ui.theme) { ui.changeTheme(it) }
                SwitchRow(stringResource(R.string.dynamic_title), stringResource(R.string.dynamic_desc), ui.dynamic) { ui.changeDynamic(it) }
                if (!ui.dynamic) {
                    Text(stringResource(R.string.accent_label), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        accentHues.forEachIndexed { i, (nameRes, hue) ->
                            val name = stringResource(nameRes)
                            val c = schemeFromHue(hue, false, false).primary
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(c)
                                    .then(if (ui.accent == i) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), CircleShape) else Modifier)
                                    .clickable { ui.changeAccent(i) }.semantics { contentDescription = name },
                                contentAlignment = Alignment.Center,
                            ) { if (ui.accent == i) Icon(Icons.Default.Check, null, tint = Color.White) }
                        }
                    }
                }
                SwitchRow(stringResource(R.string.amoled_title), stringResource(R.string.amoled_desc), ui.amoled) { ui.changeAmoled(it) }
                Column {
                    Text(stringResource(R.string.font_scale, Math.round(ui.fontScale * 100)), style = MaterialTheme.typography.bodyLarge)
                    Slider(ui.fontScale, { ui.changeFontScale(Math.round(it * 20) / 20f) }, valueRange = 0.85f..1.3f)
                }
                SwitchRow(stringResource(R.string.haptics_title), stringResource(R.string.haptics_desc), ui.haptics) { ui.changeHaptics(it) }
            }

            // ---------- язык ----------
            SectionCard(stringResource(R.string.sec_language)) {
                Text(stringResource(R.string.ui_lang_label), style = MaterialTheme.typography.bodyLarge)
                Segmented(listOf("system" to R.string.ui_lang_system, "ru" to R.string.lang_ru, "en" to R.string.lang_en),
                    ui.uiLang) { if (it != ui.uiLang) { ui.changeUiLang(it); (ctx as? Activity)?.recreate() } }
                Text(stringResource(R.string.assistant_lang_label), style = MaterialTheme.typography.bodyLarge)
                Segmented(listOf("ru" to R.string.lang_ru, "en" to R.string.lang_en), lang) { lang = it }
                Text(stringResource(R.string.lang_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---------- голос ----------
            SectionCard(stringResource(R.string.sec_voice)) {
                SwitchRow(stringResource(R.string.wake_title),
                    stringResource(if (wakeOn) R.string.wake_on_desc else R.string.wake_off_desc), wakeOn) { wakeOn = it }
                if (wakeOn) {
                    OutlinedTextField(wake, { wake = it }, label = { Text(stringResource(R.string.wake_field)) },
                        supportingText = { Text(stringResource(R.string.wake_support)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.engine_label), style = MaterialTheme.typography.bodyLarge)
                    Segmented(listOf("android" to R.string.engine_android, "vosk" to R.string.engine_vosk), engine) { engine = it }
                    if (engine == "vosk") VoskSection(lang)
                }
                SwitchRow(stringResource(R.string.barge_title), stringResource(R.string.barge_desc), barge) { barge = it }
                SwitchRow(stringResource(R.string.glasses_title), stringResource(R.string.glasses_desc), glasses) { glasses = it }
                SwitchRow(stringResource(R.string.media_title), stringResource(R.string.media_desc), media) { media = it }
                Column {
                    Text(stringResource(R.string.rate_label, "%.2f".format(rate)), style = MaterialTheme.typography.bodyLarge)
                    Slider(rate, { rate = Math.round(it * 20) / 20f }, valueRange = 0.6f..1.6f)
                    Text(stringResource(R.string.rate_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---------- данные ----------
            SectionCard(stringResource(R.string.sec_data)) {
                SwitchRow(stringResource(R.string.tools_title), stringResource(R.string.tools_desc), tools) { tools = it }
                OutlinedTextField(city, { city = it }, label = { Text(stringResource(R.string.city_label)) },
                    supportingText = { Text(stringResource(R.string.city_support)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.search_label), style = MaterialTheme.typography.bodyLarge)
                Segmented(listOf("off" to R.string.search_off, "ddg" to R.string.search_ddg, "tavily" to R.string.search_tavily), search) { search = it }
                if (search == "tavily") OutlinedTextField(tavily, { tavily = it }, label = { Text(stringResource(R.string.tavily_key_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                Text(stringResource(R.string.search_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---------- модель ----------
            SectionCard(stringResource(R.string.sec_model)) {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.url_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.key_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton({ showKey = !showKey }) { Text(stringResource(if (showKey) R.string.key_hide else R.string.key_show)) } })
                OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.model_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(
                    onClick = {
                        when {
                            loading -> {}
                            models.isEmpty() -> loadModels()
                            sheetVisible -> closeRequest++
                            else -> openRequest++
                        }
                    },
                    enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.btn_loading)) }
                    else Text(when {
                        models.isEmpty() -> stringResource(R.string.btn_show_models)
                        sheetVisible -> stringResource(R.string.btn_hide_models)
                        else -> stringResource(R.string.btn_show_models_n, models.size)
                    })
                }
                if (models.isNotEmpty()) TextButton({ loadModels() }, enabled = !loading) { Text(stringResource(R.string.btn_refresh)) }
                if (info.isNotEmpty()) Text(info, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            // ---------- фон, быстрый запуск, приватность ----------
            BackgroundCard()
            SectionCard(stringResource(R.string.sec_shortcuts)) {
                Text(stringResource(R.string.shortcuts_desc), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(stringResource(R.string.sec_privacy)) {
                Text(stringResource(R.string.privacy_desc), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ AssistantService.log.clear() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_clear)) }
            }

            Spacer(Modifier.height(if (sheetVisible) 340.dp else 24.dp))
        }
    }
}

/** Управление офлайн-моделью Vosk: скачать, удалить, состояние. */
@Composable
private fun VoskSection(lang: String) {
    val ctx = LocalContext.current
    val info = VoskModels.info(lang)
    val ver = VoskModels.version
    val installed = remember(ver, lang) { VoskModels.installed(ctx, lang) }
    val busy = VoskModels.downloading
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.engine_vosk_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            busy != null -> {
                Text(stringResource(R.string.vosk_downloading, VoskModels.progress), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { VoskModels.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.vosk_installed)) },
                    leadingIcon = { Icon(Icons.Default.Check, null) })
                Spacer(Modifier.weight(1f))
                TextButton({ VoskModels.delete(ctx, lang) }) { Text(stringResource(R.string.vosk_delete)) }
            }
            else -> {
                Text(stringResource(R.string.vosk_not_installed, info.mb), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton({ VoskModels.download(ctx, lang) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vosk_download))
                }
            }
        }
        if (VoskModels.error.isNotEmpty()) Text(stringResource(R.string.vosk_error, VoskModels.error),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<String, Int>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = selected == value, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun BackgroundCard() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
    SectionCard(stringResource(R.string.sec_background)) {
        Text(stringResource(R.string.bg_desc), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ignoring) AssistChip(onClick = {}, label = { Text(stringResource(R.string.bg_ok)) },
            leadingIcon = { Icon(Icons.Default.Check, null) })
        else OutlinedButton(
            onClick = {
                launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }, modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.bg_btn)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ModelsSheet(
    models: List<String>, current: String, query: String, onlyFree: Boolean,
    onQuery: (String) -> Unit, onOnlyFree: (Boolean) -> Unit, onPick: (String) -> Unit, onClose: () -> Unit,
) {
    val shown = remember(models, query, onlyFree) {
        models.filter { (!onlyFree || it.endsWith(":free")) && it.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sheet_title, shown.size, models.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClose) { Icon(Icons.Default.Close, stringResource(R.string.cd_close)) }
        }
        OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text(stringResource(R.string.sheet_search)) },
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
        FilterChip(onlyFree, { onOnlyFree(!onlyFree) }, label = { Text(stringResource(R.string.chip_free)) },
            modifier = Modifier.padding(vertical = 8.dp))
        if (shown.isEmpty()) Text(stringResource(R.string.sheet_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(shown, key = { it }) { m ->
                ListItem(
                    headlineContent = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (m == current) Icon(Icons.Default.Check, stringResource(R.string.cd_selected), tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor =
                        if (m == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.clickable(onClick = { onPick(m) }),
                )
            }
        }
    }
}
