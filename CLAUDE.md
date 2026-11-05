# Glasses Assistant — контекст проекта

Android-приложение (Kotlin, Jetpack Compose, Material 3 с динамическими цветами, minSdk 31): голосовой ассистент на русском для Ray-Ban Meta через Bluetooth. Телефон: Nothing Phone 3(a). Пользователь из СНГ, зарубежные сервисы часто недоступны. Исследование и требования: `reports/Ассистент для умных очков UX.md` (заметки в `research_notes/`).

## Архитектура
- `AssistantService` — foreground (microphone) + wake lock. Состояние для UI в companion (`running`, `uiState`, `status`, `muted`, `log` — Compose-state, меняется только в main). Команды сервису: `AssistantService.send(ctx, ACTION_*)` (PTT, MUTE, HUSH, REPEAT, ASK). Кнопки в уведомлении: Говорить / Микрофон / Стоп. Микрофонный FGS нельзя стартовать из фона (Android 14+) — запуск только из Activity.
- Цикл: SpeechRecognizer (ru-RU) → `LlmClient` → TTS. Режим пробуждения (`wakeOn`, `wake`): слово ищется в тексте распознавания (расходует батарею, Google не рекомендует для непрерывной работы). После ответа — окно продолжения диалога; тишина → снова ждёт слово.
- Локальные команды без модели: стоп/тихо, повтори, помедленнее/побыстрее (prefs `rate`), подробнее. Звуковые сигналы через ToneGenerator; через 3,5 с ожидания говорит «Секунду».
- `LlmClient` — OpenAI-совместимый, цикл function calling (до 4 итераций), системный промпт с датой/временем/городом и правилами устной речи; если провайдер отвечает 400 на tools — переходит на ответы без инструментов. Ошибки → `LlmException.Kind` → понятная фраза.
- `Tools` — get_current_time, get_weather (Open-Meteo, кэш 10 мин), get_currency_rates (cbr-xml-daily.ru, кэш 3 ч), wikipedia_summary. Результат содержит источник и время данных.
- UI: `MainActivity.kt` (тема, навигация Диалог/Настройки), `AssistantScreen.kt` (анимированный Orb по состояниям, кнопки Говорить/Тихо/Микрофон, чипы быстрых команд, история-пузыри, ввод текстом, haptics, liveRegion), `SettingsScreen.kt` (голос, данные, модель/ключ с немодальной шторкой моделей через `BottomSheetScaffold`, фон, приватность). Настройки пишутся в SharedPreferences "cfg" сразу.

## Статус
- v0.4: собирается; на очках и телефоне не проверялось. GigaChat убран. Настройки провайдера задаёт пользователь (OpenRouter `:free`, DeepSeek и др.); поддержка function calling зависит от модели.
- НЕ сделано из отчёта: стриминг LLM→TTS по предложениям, barge-in (во время речи микрофон выключен — «тихо» только кнопкой/в уведомлении), плитка/виджет, MediaSession-кнопка очков, офлайн wake word (Vosk), Material 3 Expressive (нужен material3 1.5.0-rc), веб-поиск.
- Нужно проверить на устройстве: что Ray-Ban отдаёт как BT-гарнитура (кодек микрофона, long-press), поведение Nothing OS в фоне, расход батареи в режиме wake word, доступность провайдеров из СНГ.

## Сборка
Android SDK 35 (`local.properties`: `sdk.dir=<путь к Android SDK>`), JDK 17–21 (JDK 25 из Android Studio не подходит для Kotlin 2.0.21), Gradle 8.9+ (установленный локально). Путь проекта содержит кириллицу — собирать с `-Pandroid.overridePathCheck=true`. В Git Bash `curl` к некоторым https-хостам падает на SSL — проверять сеть через PowerShell.
