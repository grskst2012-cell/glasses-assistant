package com.example.glassesassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** Язык интерфейса: «система» или выбранный в настройках. Смена языка пересоздаёт активность. */
    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("cfg", MODE_PRIVATE).getString("uiLang", "system") ?: "system"
        if (lang == "system") { super.attachBaseContext(base); return }
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale(lang))
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ui = UiPrefs(prefs)
        val autostart = intent.getBooleanExtra("autostart", false)
        setContent { AppTheme(ui) { Root(prefs, ui, autostart) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(prefs: android.content.SharedPreferences, ui: UiPrefs, autostart: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var models by remember { mutableStateOf(listOf<String>()) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.RECORD_AUDIO] == true) {
            ctx.startForegroundService(Intent(ctx, AssistantService::class.java))
        }
    }
    val start = {
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    }
    // запуск с плитки, виджета или ярлыка, когда не хватало разрешений
    LaunchedEffect(Unit) { if (autostart && !AssistantService.running) start() }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(if (tab == 0) R.string.title_assistant else R.string.title_settings)) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.tab_dialog)) })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
            }
        },
    ) { inner ->
        if (tab == 0) AssistantScreen(inner, ui, prefs, start)
        else SettingsScreen(inner, prefs, ui, models) { models = it }
    }
}
