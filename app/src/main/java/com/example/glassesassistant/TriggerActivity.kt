package com.example.glassesassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Невидимая активность для плитки, виджета и ярлыка «Говорить».
 * Микрофонный foreground-сервис (Android 14+) нельзя запустить из фона, а из видимого окна можно.
 * Если ассистент уже работает — просто «Говорить»; если нет — запуск; если нет разрешений — открываем приложение.
 */
class TriggerActivity : Activity() {
    private var handled = false

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        val perms = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        when {
            AssistantService.running -> AssistantService.send(this, AssistantService.ACTION_PTT)
            perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ->
                startForegroundService(Intent(this, AssistantService::class.java))
            else -> startActivity(Intent(this, MainActivity::class.java).putExtra("autostart", true))
        }
        finish()
    }
}
