package com.example.glassesassistant

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    inner: PaddingValues, prefs: android.content.SharedPreferences,
    models: List<String>, onModels: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var url by remember { mutableStateOf(prefs.getString("url", "https://api.deepseek.com") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "deepseek-chat") ?: "") }
    var wake by remember { mutableStateOf(prefs.getString("wake", "ассистент") ?: "") }
    var wakeOn by remember { mutableStateOf(prefs.getBoolean("wakeOn", true)) }
    var city by remember { mutableStateOf(prefs.getString("city", "") ?: "") }
    var tools by remember { mutableStateOf(prefs.getBoolean("tools", true)) }
    var glasses by remember { mutableStateOf(prefs.getBoolean("useGlasses", true)) }
    var rate by remember { mutableFloatStateOf(prefs.getFloat("rate", 1.0f)) }
    var showKey by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var onlyFree by remember { mutableStateOf(false) }

    // настройки сохраняются сразу — сервис читает их при старте (город, инструменты и скорость — на лету)
    LaunchedEffect(url, key, model, wake, wakeOn, city, tools, glasses, rate) {
        prefs.edit().putString("url", url.trim()).putString("key", key.trim()).putString("model", model.trim())
            .putString("wake", wake.trim()).putBoolean("wakeOn", wakeOn).putString("city", city.trim())
            .putBoolean("tools", tools).putBoolean("useGlasses", glasses).putFloat("rate", rate).apply()
    }

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    val sheetVisible = sheetState.targetValue != SheetValue.Hidden
    var closeRequest by remember { mutableIntStateOf(0) }
    var openRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(openRequest) { if (openRequest > 0) sheetState.partialExpand() }
    LaunchedEffect(closeRequest) { if (closeRequest > 0) sheetState.hide() }

    fun loadModels() {
        loading = true; info = ""
        val u = url; val k = key
        thread {
            val r = try { Result.success(LlmClient.listModels(u, k)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                loading = false
                r.onSuccess { onModels(it); query = ""; openRequest++ }
                r.onFailure { onModels(emptyList()); info = "Не удалось загрузить: ${it.message?.take(160)}" }
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
            // sheetInner.bottom всегда равен высоте шторки (340 dp), даже когда она скрыта, — берём только верх;
            // место под открытую шторку резервирует Spacer внизу
            Modifier.padding(top = sheetInner.calculateTopPadding()).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Голос") {
                SwitchRow("Слово пробуждения",
                    if (wakeOn) "Ассистент молчит, пока вы не назовёте слово" else "Выключено: слушает постоянно, отвечает на всё",
                    wakeOn) { wakeOn = it }
                if (wakeOn) OutlinedTextField(
                    wake, { wake = it }, label = { Text("Слово или фраза") },
                    supportingText = { Text("Например «ассистент». Можно сразу с вопросом: «ассистент, который час?». Работает через распознавание речи Android, поэтому заметно тратит батарею. Надёжнее — кнопка «Говорить» или плитка. Применяется после перезапуска.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow("Микрофон очков", "Звук и микрофон идут через Bluetooth-очки, если они подключены. Выключите, если очки плохо слышат вас.",
                    glasses) { glasses = it }
                Column {
                    Text("Скорость речи: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyLarge)
                    Slider(rate, { rate = Math.round(it * 20) / 20f }, valueRange = 0.6f..1.6f)
                    Text("Голосом: «помедленнее», «побыстрее»", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard("Актуальные данные") {
                SwitchRow("Проверять факты по источникам",
                    "Время, погода (Open-Meteo), курсы ЦБ РФ, Википедия. Ассистент называет источник и время данных. Нужна модель с поддержкой вызова функций; если её нет, он ответит без источников.",
                    tools) { tools = it }
                OutlinedTextField(city, { city = it }, label = { Text("Ваш город") },
                    supportingText = { Text("Для погоды. В модель уходит только название города — без координат.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            SectionCard("Модель и ключ") {
                OutlinedTextField(url, { url = it }, label = { Text("API base URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(key, { key = it }, label = { Text("API key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton({ showKey = !showKey }) { Text(if (showKey) "Скрыть" else "Показать") } })
                OutlinedTextField(model, { model = it }, label = { Text("Модель") },
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
                    if (loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Загружаю…") }
                    else Text(if (models.isEmpty()) "Показать модели" else if (sheetVisible) "Скрыть модели" else "Показать модели (${models.size})")
                }
                if (models.isNotEmpty()) TextButton({ loadModels() }, enabled = !loading) { Text("Обновить список") }
                if (info.isNotEmpty()) Text(info, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            BackgroundCard()

            SectionCard("Приватность") {
                Text("Пока ассистент запущен, Android показывает значок микрофона. Запросы уходят выбранному провайдеру модели; " +
                    "история хранится только в памяти приложения.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ AssistantService.log.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Очистить историю диалога") }
            }

            Spacer(Modifier.height(if (sheetVisible) 340.dp else 24.dp))
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
    SectionCard("Работа в фоне") {
        Text(
            "Ассистент живёт в уведомлении (там же кнопки «Говорить», «Микрофон», «Стоп») и слушает при выключенном экране. " +
                "Запускать его нужно из открытого приложения — это требование Android 14+.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ignoring) AssistChip(onClick = {}, label = { Text("Ограничения батареи сняты") },
            leadingIcon = { Icon(Icons.Default.Check, null) })
        else OutlinedButton(
            onClick = {
                launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }, modifier = Modifier.fillMaxWidth(),
        ) { Text("Снять ограничения батареи") }
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
            Text("Модели (${shown.size} из ${models.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClose) { Icon(Icons.Default.Close, "Закрыть") }
        }
        OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text("Поиск") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
        FilterChip(onlyFree, { onOnlyFree(!onlyFree) }, label = { Text("Только :free") },
            modifier = Modifier.padding(vertical = 8.dp))
        if (shown.isEmpty()) Text("Ничего не найдено", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(shown, key = { it }) { m ->
                ListItem(
                    headlineContent = { Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (m == current) Icon(Icons.Default.Check, "Выбрана", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor =
                        if (m == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.clickable(onClick = { onPick(m) }),
                )
            }
        }
    }
}
