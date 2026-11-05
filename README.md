# Glasses Assistant

![Platform](https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-green)

A Russian-language voice assistant for smart glasses (Ray-Ban Meta or any Bluetooth glasses and headphones).
It runs in the background, listens through the glasses' microphone, answers out loud, and doesn't make up facts: time, weather, exchange rates and reference answers come from real sources, and the assistant names them.

> A personal, experimental project. It has not been tested on real glasses yet.

## Features

- **Background operation.** A foreground service with a persistent notification; the "Talk", "Microphone" and "Stop" buttons are right in it.
- **Wake word.** Your own word or phrase ("assistant, what time is it?" works as a single phrase). Can be turned off in favor of the button.
- **Short answers made for listening.** The point comes first, numbers and units are spelled out, no markdown. Ask "more detail" for a longer answer.
- **Verifiable facts.** The model calls tools and states the source and the time of the data:
  - date and time, from the phone;
  - weather: [Open-Meteo](https://open-meteo.com);
  - exchange rates: Bank of Russia ([cbr-xml-daily.ru](https://www.cbr-xml-daily.ru));
  - reference answers: Russian Wikipedia.
- **Voice commands handled locally, without calling the model.** "Stop" / "quiet", "repeat", "slower", "faster", "more detail".
- **Any OpenAI-compatible API.** DeepSeek, OpenRouter, Kimi, Yandex AI Studio and others: all you need is a URL, a key and a model. The model list loads with one button, with search and a `:free` filter.
- **Material 3.** Dynamic color, dark theme, an animated state indicator (waiting, listening, thinking, speaking, mic off), conversation history, quick commands, text input, haptic feedback and TalkBack support.
- **Clear errors.** "No connection", "Check your key", "Model is overloaded" instead of silence.

> The assistant speaks and understands **Russian**. The UI text is in Russian too.

## How it works

```
glasses mic ─► SpeechRecognizer (ru-RU) ─► LlmClient ──► tools (time, weather, rates, Wikipedia)
                                               │
glasses speakers ◄─ TextToSpeech (ru) ◄────────┘
```

| File | Purpose |
|---|---|
| `AssistantService.kt` | Background service: Bluetooth audio routing, the listen → think → speak loop, wake word, local commands, UI state |
| `LlmClient.kt` | `/chat/completions` client with history and a function-calling loop, plus friendly error mapping |
| `Tools.kt` | Tools and caching: time, Open-Meteo, Bank of Russia rates, Wikipedia |
| `AssistantScreen.kt` | Dialog screen: state indicator, buttons, history, text input |
| `SettingsScreen.kt` | Settings: voice, data, model and key, background work, privacy |
| `MainActivity.kt` | Theme and navigation |

## Requirements

- Android 12 or newer (`minSdk 31`)
- Bluetooth glasses or headphones (optional: the phone's microphone is used without them)
- An API key from any OpenAI-compatible provider
- To build: Android SDK 35, JDK 17–21, Gradle 8.9+

## Build and install

```bash
git clone https://github.com/grskst2012-cell/glasses-assistant.git
cd glasses-assistant
```

1. Open the folder in Android Studio, wait for Gradle sync, then **Run** on your phone.
2. Or build from the command line (`local.properties` must contain `sdk.dir=...` pointing to your Android SDK):

   ```bash
   gradle :app:assembleDebug
   ```

   The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

> JDK 25 does not work with Kotlin 2.0.21; use JDK 17–21. If the project path contains non-Latin characters, add `-Pandroid.overridePathCheck=true`.

## Setup

1. Pair the glasses with your phone over Bluetooth like regular headphones.
2. Open **Settings** → **Model and key** (labels in the app are in Russian) and enter the URL, key and model. "Show models" opens a list; your choice is saved immediately.
3. For weather, enter your city in the **Live data** section.
4. Tap **Remove battery restrictions** so the system does not put the assistant to sleep.
5. On the **Dialog** tab tap **Start assistant** and grant the microphone and notification permissions.

Example providers:

| Provider | Base URL | Model |
|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | any, including `:free` ones |
| Kimi (Moonshot) | `https://api.moonshot.ai/v1` | from the Moonshot console |

Fact checking through tools works only if the model supports function calling. If it does not, the assistant answers without sources.

## Usage

- Say the wake word: "**assistant**, what's the weather?", or just "assistant" and give the command after the beep.
- Or tap **Talk** in the app or in the notification.
- After an answer you can keep talking without the wake word; after a pause the assistant goes back to waiting for it.
- "Stop", "repeat", "slower", "more detail" take effect instantly, with no model request.

## Limitations

- The wake word is built on Android's built-in speech recognition: it drains the battery noticeably, may beep on restart on some firmware, and may use the network. The **Talk** button is more reliable.
- A microphone service cannot be started from the background (an Android 14+ rule), so it must be started from the open app.
- The microphone is off while the assistant is speaking, so you cannot interrupt it by voice (the "Quiet" button works).
- Bluetooth microphone quality depends on the glasses and codec; the behavior of Ray-Ban Meta as a headset has not been verified.
- Free models and providers can be unstable (HTTP 429), and availability depends on your region.

## Roadmap

- [ ] Stream the model's answer into speech sentence by sentence
- [ ] Barge-in: interrupt the assistant by voice
- [ ] Quick Settings tile, widget and a button on the glasses' frame
- [ ] Offline wake word (Vosk) instead of continuous recognition
- [ ] Web search and translation
- [ ] Glasses camera via the Meta Wearables Device Access Toolkit

## Privacy

Your API key and settings are stored only on the phone. Requests are sent to the model provider you choose; for weather only the city name is sent, never coordinates. Conversation history lives in the app's memory and can be cleared in settings. While the assistant is running, Android shows the microphone indicator.

## Research

The app's requirements are based on research into what smart-glasses owners need on the go (written in Russian):
[`reports/Ассистент для умных очков UX.md`](reports/%D0%90%D1%81%D1%81%D0%B8%D1%81%D1%82%D0%B5%D0%BD%D1%82%20%D0%B4%D0%BB%D1%8F%20%D1%83%D0%BC%D0%BD%D1%8B%D1%85%20%D0%BE%D1%87%D0%BA%D0%BE%D0%B2%20UX.md)
(source notes are in `research_notes/`).

## License

[MIT](LICENSE)
