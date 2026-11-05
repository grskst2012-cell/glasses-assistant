# Meta Mockup — контекст проекта

Android-приложение (Kotlin, Jetpack Compose, Material 3, minSdk 31): голосовой ассистент (русский и английский) для Ray-Ban Meta и других Bluetooth-очков. Телефон: Nothing Phone 3(a). Пользователь из СНГ, зарубежные сервисы часто недоступны. Исследование и требования: `reports/Ассистент для умных очков UX.md` (заметки в `research_notes/`). Пакет остался `com.example.glassesassistant` (смена applicationId сделала бы обновление отдельным приложением); отображаемое имя — «Meta Mockup».

## Архитектура
- `AssistantService` — foreground (microphone) + wake lock; состояние для UI в companion (Compose-state, меняется только в main). Команды: `AssistantService.send(ctx, ACTION_*)`. Микрофонный FGS нельзя стартовать из фона (Android 14+) — запуск из Activity / `TriggerActivity` (невидимая прослойка для плитки, виджета, ярлыка).
- Слово пробуждения: Android SpeechRecognizer (по тексту) или Vosk офлайн (`VoskModels` — модель скачивается из alphacephei.com в filesDir; Vosk ловит только слово, команду потом распознаёт Android). Перебивание голосом (`barge`): во время TTS слушаем слова остановки. Кнопка очков (`media`): MediaSession (play/pause = Говорить, next = повтор, prev = тихо).
- Язык: `lang` (ассистент: распознавание, TTS, промпт, команды, Википедия) и `uiLang` (интерфейс; `attachBaseContext` + `recreate()`). Строки — `res/values` (en) и `res/values-ru`; сервис берёт их через `Lang.ctx(...)`.
- `LlmClient` — OpenAI-совместимый, function calling (до 4 итераций), промпт собирается из строковых ресурсов. `Tools` — время, Open-Meteo, ЦБ РФ, Википедия, `web_search` (DuckDuckGo HTML или Tavily). Перевод: модель начинает ответ с `[lang=xx]`, сервис озвучивает на этом языке.
- Внешний вид: `UiPrefs` (тема, динамические цвета или акцент из 6, AMOLED, масштаб текста, вибро), `AppTheme`. Экраны: `AssistantScreen` (Orb, кнопки, чипы, история), `SettingsScreen` (шторка моделей — `BottomSheetScaffold`, использовать только верхний отступ `sheetInner`).
- Настройки — SharedPreferences "cfg", пишутся сразу.

## Статус
- v0.5: собирается; на очках и телефоне не проверялось. Сделано всё из плана, кроме стриминга ответа в речь и камеры (Meta Wearables Device Access Toolkit).
- Нужно проверить на устройстве: что Ray-Ban отдаёт как BT-гарнитура (кодек, media-кнопки), работает ли Vosk/AudioRecord через BT-микрофон, эхо при перебивании, поведение Nothing OS в фоне, расход батареи, слово пробуждения должно быть в словаре Vosk, DuckDuckGo из СНГ.

## Сборка
Android SDK 35 (`local.properties`: `sdk.dir=<путь к SDK>`), JDK 17–21 (JDK 25 из Android Studio не подходит для Kotlin 2.0.21), Gradle 8.9+. Путь проекта содержит кириллицу — собирать с `-Pandroid.overridePathCheck=true`. В Git Bash `curl` к некоторым https-хостам падает на SSL — проверять сеть через PowerShell. ABI ограничены arm64-v8a и armeabi-v7a (Vosk). Релизы на GitHub — debug-сборки.
