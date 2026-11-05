package com.example.glassesassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        setContent { AppTheme { Root(prefs) } }
    }
}

/** Material 3 с динамическими цветами (minSdk 31 — доступны всегда). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(prefs: android.content.SharedPreferences) {
    val ctx = LocalContext.current
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

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(if (tab == 0) "Ассистент" else "Настройки") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Диалог") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
            }
        },
    ) { inner ->
        if (tab == 0) AssistantScreen(inner, start)
        else SettingsScreen(inner, prefs, models) { models = it }
    }
}
