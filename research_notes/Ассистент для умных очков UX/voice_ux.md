# Голосовой UX (VUI) для ассистента умных очков: eyes-free / hands-free при ходьбе

Примечание по методу: поиск дал в основном фрагменты; полные документы Google Conversation Design (страницы Confirmations/Errors), Apple HIG, Material 3 для аудио-first, Яндекс Алиса и Meta developer docs не были открыты. Всё, чего нет в источниках, вынесено в Gaps.

## 1. Длина и структура голосового ответа; числа, время, адреса, единицы в русском TTS

### Takeaway
Официальные гайды (Google, Amazon) сходятся: коротко, по делу, без монологов; Amazon даёт проверяемый критерий "один вдох". Длинные данные (адреса, телефоны) выносить на экран/в приложение. Для русского TTS главный риск - нормализация текста (числа, падежи, сокращения), её надо делать до синтеза.

### Cited Findings
- Google: "Be informative, but keep responses concise"; не вдаваться в детали, если пользователь явно не выиграет; "Let users take their turn" — [Google Conversation Design: Language](https://developers.google.com/assistant/conversation-design/language)
- Google Interactive Canvas: избегать слишком длинных голосовых ответов, чтобы снизить когнитивную нагрузку (вторичное изложение поиска) — [Google Interactive Canvas design](https://developers.google.cn/assistant/interactivecanvas/design?hl=en)
- Google Research: у Ассистента много eyes-free взаимодействий, поэтому нужны отдельные критерии оценки речи (грамматика, просодия, произношение, лаконичность) — [Evaluation of Speech for the Google Assistant](https://research.google/blog/evaluation-of-speech-for-the-google-assistant/)
- Amazon: "Users need Alexa to speak concisely without extra words"; тест одного вдоха: "If you can say the words at a conversational pace with one breath, the length is probably good"; "Longer responses tend to be more difficult to follow and remember"; паузы-вдохи только между идеями — [Alexa Voice Design Guide: What Alexa says](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)
- Amazon (через поиск): ответ, который человек прочитает за 1-2 вдоха; предлагать мало вариантов, не меню; активный залог; длинные данные вроде телефона/email отправлять в карточку приложения, а не зачитывать — [Alexa: Best Practices for Text Responses](https://developer.amazon.com/en-US/docs/alexa/custom-skills/best-practice-text-response.html)
- Amazon про follow-up: заканчивать вопросом с подсказкой, не "Чем ещё помочь?"; на экранных Echo "Let me know if you'd like to hear more" и ожидание, сессия закрывается через 30 с; при повторном запросе перефразировать вопрос, а не говорить "не расслышал/не понял" (это заставляет повторять громче/медленнее) — [Alexa Voice Design Guide](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)
- Amazon: нельзя задавать вопрос и продолжать говорить — пользователь думает, что ассистент уже слушает — [там же](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)
- Русская нормализация неоднозначна: "2 части" -> "две части" или "второй части"; "длиной до 2 км" и "едем до 2 км" требуют разных падежей; аббревиатуры контекстны — [Habr: нормализатор для Open_STT](https://habr.com/ru/post/491260/)
- Нужны определение границ предложений, раскрытие сокращений, расстановка ударений; точка может быть частью сокращения ("132,7 тыс.") — [Habr: синтез речи](https://habr.com/ru/post/679838)
- Пользователь Silero TTS: числа в датах теряются ("18 мая" -> "мая", "2022 года" -> "года") без нормализации — [Habr, комментарии](https://habr.com/en/articles/660565/comments)
- Открытая библиотека RUNorm (числа в текст, раскрытие сокращений, транслитерация, аббревиатуры по буквам; модели на FRED-T5/ruT5, Apache 2.0) — [RUNorm на PyPI](https://pypi.org/project/runorm/)

### Inferences
- Рабочее правило для LLM-ответов: 1-2 предложения (~до 25-30 слов, "один вдох"), ответ первым, детали по запросу "расскажи подробнее", полные данные - в историю в приложении на телефоне. (Конкретные "25-30 слов" - моя оценка из критерия "один вдох", не цитата.)
- Чтобы не зависеть от нормализатора TTS, промпт LLM должен выдавать текст "как произносится": числа и время словами с правильным падежом ("в четырнадцать тридцать", "через триста метров"), единицы без символов ("километров в час"), без markdown, URL, эмодзи. Тестировать на падежах после предлогов.
- Адреса: называть минимально нужное (улица + номер дома), не индекс и не полный адрес; остальное на экран.

### Gaps
- Не найдены официальные правила Google/Amazon по озвучиванию чисел, времени, адресов (на открытых страницах их нет).
- Не нашёл русскоязычных гайдов (Яндекс Алиса/SpeechKit) по написанию голосовых реплик и единицам измерения; нужен отдельный поиск.
- Нет эмпирических данных об оптимальном числе слов/секунд для ходьбы.

## 2. Задержки и обратная связь: earcons, "thinking", потоковый TTS, barge-in, команды stop/repeat/slower

### Takeaway
Классические пороги Nielsen (0,1 / 1 / 10 с) плюс исследования ассистентов: >1 с заметно, ~4-5 с - порог недовольства. Задержку скрывают быстрым звуковым подтверждением и потоковым TTS; barge-in должен реагировать за ~сотни мс, но без ложных срабатываний.

### Cited Findings
- Nielsen: 0,1 с - ощущение мгновенности; 1 с - поток мыслей не прерван, но задержка замечена; 10 с - предел внимания, нужна обратная связь о прогрессе — [NN/g: 3 Response Time Limits](https://www.nngroup.com/videos/3-response-time-limits-interaction-design/)
- Медианная пауза между репликами людей около 200 мс в 10 языках (Stivers et al., 2009); ответ через 2 с воспринимается как неловкая тишина — [Picovoice: Voice UX](https://picovoice.ai/guide/voice-agents/voice-ux-latency-turn-taking/)
- Исследование в симуляторе вождения: латентность голосового ассистента в авто не должна превышать 5 с для удовлетворённости водителей; эксперты терпимее к долгим ответам в навигации — [TRID: Importance of Timing](https://trid.trb.org/View/2754236)
- VR-исследование 2025 с LLM-агентами: задержка >4 с ухудшает QoE; естественные речевые "филлеры" улучшают воспринимаемое время ответа (вторичное изложение, не проверено по первоисточнику) — [arXiv 2507.22352](https://arxiv.org/html/2507.22352v1)
- Picovoice: метрика barge-in latency (от первого слога перебивания до тишины ассистента) напрямую связана с ощущением "меня слушают"; Orca TTS 106 мс до первого звука против 335 мс у ElevenLabs Streaming (данные вендора) — [Picovoice](https://picovoice.ai/guide/voice-agents/voice-ux-latency-turn-taking/)
- Рекомендация вендорского блога: требовать 200-300 мс устойчивого голоса и confidence >0,7 для barge-in; ложное прерывание хуже слегка медленного (вендорский источник, не рецензируемый) — [Future AGI](https://futureagi.com/blog/voice-ai-barge-in-turn-taking-2026/)
- Фраза-подтверждение вроде "Сейчас проверю" выигрывает секунды, но числа не даны — [Picovoice](https://picovoice.ai/guide/voice-agents/voice-ux-latency-turn-taking/)
- Вендорский тезис "каждая секунда задержки снижает удовлетворённость на 15-20%" не подтверждён первоисточником — [channel.tel](https://www.channel.tel/blog/latency-kills-satisfaction-16-percent-rule) (использовать с осторожностью)

### Inferences
- Бюджет (моя оценка, опирается на Nielsen и 4-5 с): подтверждение приёма (earcon) <300 мс после конца речи; первый звук ответа <1-1,5 с; если ответ >3 с - голосовая фраза ("Ищу...") или повторяющийся тихий "thinking"-звук; не молчать дольше ~5 с.
- Стримить LLM -> TTS по предложениям: начинать озвучивать после первого законченного предложения; первое предложение делать самым коротким и содержательным.
- Команды "стоп", "повтори", "помедленнее", "подробнее" должны обрабатываться локально/на устройстве с приоритетом и работать во время речи ассистента (barge-in). Для очков с эхо: подавление эха (AEC) обязательно, иначе ассистент слышит себя.
- Конкретных исследований earcons именно для очков не найдено.

### Gaps
- Не найдено первоисточников по earcons (длительность, громкость, дизайн) - поисковые результаты их не охватили.
- Нет проверенных числовых значений endpointing (таймаут тишины); Picovoice их не указывает.
- Нет измеренных данных по barge-in в Alexa/Google; цифры 150/200 мс из вендорских блогов.

## 3. Wake word и обмен репликами: FA/FR, окно follow-up, подтверждения, ошибки, шум, Bluetooth-микрофон

### Takeaway
Wake word оценивают как FRR при фиксированном FAR (часто 1 ложное на час или меньше). Meta использует окно ответа без wake word (около 5 с) и режим Live AI; для шума и Bluetooth-микрофона главный риск - узкополосный HFP (8 кГц) и потери пакетов.

### Cited Findings
- Стандартная методика: FRR при фиксированном FAR, например 1 ложное срабатывание в час — [Picovoice: wake word benchmarks](https://picovoice.ai/blog/wake-word-benchmarks/)
- Сертификация Alexa (ETSI-доклад): тест ложных срабатываний - не более 3 событий на 24-часовой записи (около 0,125/час) — [ETSI workshop 2022](https://docbox.etsi.org/Workshop/2022/11STQWORKSHOP/SESSION01/SURFACEINK_BEROL.pdf)
- Пример: FRR 2,00% (близкий микрофон) против 5,11% (дальнее поле) при 1 ложном/час; исследование PETS 2020 - 0,95 ложных активаций колонок в час рядом с работающим ТВ — [Voxbooster](https://voxbooster.com/blog/far-field-smart-speaker-beamforming-wake-accuracy-statistics-2026/); [Sensory/PETS summary](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9899021) (вторичные/вендорские данные, условия разные)
- Meta: настройка "Respond without Hey Meta" - очки слушают ответ около 5 секунд после ответа; Live AI позволяет перебивать и менять тему без wake word; "Pause Live AI" или касание тачпада временно отключает микрофон и камеру; сессия Live AI около 30 минут на заряде — [Android Central](https://www.androidcentral.com/wearables/meta-smart-glasses-ai-tips-and-tricks); [TechCrunch](https://techcrunch.com/2024/12/16/meta-updates-its-smart-glasses-with-real-time-ai-video/?guccounter=1); [etcentric](https://www.etcentric.org/?p=189739)
- Amazon: при пере-запросе перефразировать вопрос, не сообщать "не понял" - иначе люди повторяют медленнее/громче (ухудшая распознавание) — [Alexa Voice Design Guide](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)
- Bluetooth: полоса HFP/HSP 8 кГц считается слишком узкой для распознавания (утверждение патентной заявки); широкополосная речь (WBS) - HFP 1.6+, 16 кГц, кодек mSBC; поддержка зависит от гарнитуры; потеря пакетов мало влияет на звонки, но заметно ухудшает распознавание — [Justia patent](https://patents.justia.com/patent/20180295656); [Ubuntu Weekly Recipe](https://gihyo.jp/admin/serial/01/ubuntu-recipe/0681)
- Обзор Macworld: гарнитура с HFP 1.6 wideband давала точность на уровне лучших проводных микрофонов (анекдотично) — [Macworld](https://www.macworld.com/article/223591/review-roundup-3-wireless-microphones-for-speech-recognition.html)

### Inferences
- Для ходьбы лучше полагаться на комбинацию: wake word + кнопка/жест на очках (push-to-talk как надёжный путь в шуме) + окно follow-up 5-8 с (ориентир - 5 с у Meta).
- Подтверждения: для необратимых/дорогих действий (звонок, отправка сообщения, оплата) - явное подтверждение; для безопасных (время, погода) - без. Подтверждение вариантом "да/нет", короткое, с повтором ключевого слова ("Позвонить Ивану?").
- Ошибки: 1-й сбой - тихий reprompt с перефразировкой; 2-й - предложить альтернативу (повторить жест/написать в приложении); не допускать петель.
- Ветер: ветрозащита/beamforming в очках, повышение порога вместо повышения громкости TTS; при плохом SNR предлагать push-to-talk. Это инженерные выводы, не цитаты.
- Если очки отдают узкополосный SCO (8 кГц), качество ASR упадёт; предпочесть микрофон очков с WBS/mSBC или стримить аудио иным путём (BLE Audio/LC3 - не проверено).

### Gaps
- Нет измеренного WER для CVSD (8 кГц) и mSBC (16 кГц) - найденные источники только качественные.
- Нет данных по ветру/уличному шуму именно для очков.
- Нет исследований оптимальной длины окна follow-up (кроме 5 с у Meta и 30 с у Amazon Echo Show).
- Meta: подтверждения поведения "стоп" в Live AI не найдено.

## 4. Безопасность и когнитивная нагрузка: ходьба/вождение, прерывания, что не говорить при переходе дороги

### Takeaway
Голос нагружает меньше, чем набор текста (при ходьбе голос не менял походку), но не нулевая нагрузка: сложность разговора и ошибки распознавания повышают отвлечение. Прямых исследований голосового ассистента у пешеходов найти не удалось.

### Cited Findings
- Конференц-исследование (SAGE, HFES): при наборе сообщений при ходьбе походка замедлялась и шаги укорачивались, при голосовом разговоре значимой разницы с базовой ходьбой не было (небольшое пилотное) — [SAGE Journals](https://journals.sagepub.com/doi/10.1177/1541931214581455)
- Gait & Posture: набор текста вызывает большее вмешательство в ходьбу, чем разговор по телефону — [Cell phones change the way we walk](https://dx.doi.org/10.1016/j.gaitpost.2011.12.005)
- Сложные темы разговора ухудшают когнитивные показатели - важна сама нагрузка беседы, а не только канал — [PMC7740995](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7740995/)
- Источник (вторичный) приводит: отвлечённые набором текста в 3,9 раза чаще совершают хотя бы одно небезопасное действие при переходе дороги — [Nature Sci Rep 2018](https://www.nature.com/articles/s41598-018-36230-5)
- AAA Foundation: системы с плохим распознаванием дали высокий уровень отвлечения (категория 3 из 5); даже идеальная диктовка сообщений - категория 3 из-за усилий по составлению; качество синтетического голоса почти не влияло; Entune 1,7 (как аудиокнига) против MyLink 3,7 — [AAA Phase II report](https://newsroom.aaa.com/wp-content/uploads/2014/10/Cognitive-Distraction-Phase-II-Report-Foundation-October-2014.pdf); [EurekAlert](https://www.eurekalert.org/news-releases/910103)
- Amazon: многословные и многоэтапные ответы труднее воспринимать и запоминать — [Alexa Voice Design Guide](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)

### Inferences
- Основной вывод для дизайна: уменьшать ошибки распознавания (они главный драйвер отвлечения), не давать длинных ответов и меню, не требовать составления текста голосом при ходьбе.
- Рекомендации (инженерные, не из источников): при движении по улице - только короткие ответы; никаких уведомлений/чтения сообщений без запроса при переходе дороги; не начинать новый диалог по инициативе ассистента при быстром движении (по акселерометру/GPS); критические предупреждения - короткий сигнал, не речь; уметь мгновенно замолчать по "стоп" и по краткой паузе при резком шуме трафика; громкость адаптивная, но не маскирующая окружающие звуки (открытые динамики очков).
- Прерывания: откладывать неважные сообщения до остановки; приоритетная очередь.

### Gaps
- Не найдено исследований голосового ассистента (не телефонного звонка) у пешеходов; нет данных по безопасной длине фразы при переходе дороги.
- Свежий отчёт AAA "Measuring Cognitive Distraction in the Automobile III" (янв. 2026) виден только фрагментарно — [AAA PDF](https://aaafoundation.org/wp-content/uploads/2026/01/MeasuringCognitiveDistractionintheAutomobileIIIReport.pdf), выводы не проверены.
- Нет русскоязычных исследований.

## 5. Гайды Google/Alexa/Apple/Meta и Android/Material для аудио-first и приложения-компаньона

### Takeaway
Google и Amazon дают принципы лаконичности, не перебивать пользователя, давать взять слово; для телефонного UI аналогом служит Android Auto: "glanceable and simple", media-уведомления с минимумом элементов. Apple HIG и Material 3 в этом исследовании не подтверждены.

### Cited Findings
- Google "Keep it short"/"Let users take their turn" — [Google Conversation Design](https://developers.google.com/assistant/conversation-design/language); сводка принципов — [principles.design](https://principles.design/examples/google-conversation-principles) (вторичный)
- Android Auto: интерфейсы должны быть "glanceable and simple"; вождение требует полного внимания, ПО в авто - нет — [Android Auto Developer Overview (архив)](https://spot.pcc.edu/~mgoodman/developer.android.com/auto/overview.html)
- Android Auto: media-уведомления только как heads-up, не сохраняются в центре уведомлений; максимум иконка приложения, основной и вторичный текст, до двух кнопок действий — [Android Auto: Notifications](https://developers.google.cn/cars/design/android-auto/product-experience/system-ui/notifications?hl=en)
- Amazon: экран должен дополнять голосовой ответ, а не дублировать; проверять на расстоянии (Echo Show 7 футов) — [Alexa Voice Design Guide](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)
- Amazon: приоритет атрибутов по важности для пользователя (адрес, телефон, часы работы) для карточек — [там же](https://developer.amazon.com/fr/designing-for-voice/what-alexa-says/)

### Inferences
- Экран телефона-компаньона (мои выводы): крупный статус "слушает / думает / говорит / ошибка / микрофон выключен" с цветом и иконкой; крупные кнопки stop / повторить / mute / push-to-talk (большие touch targets, Material рекомендует минимум 48dp - из общих знаний, не из найденных источников); история диалога с полными данными (адреса, номера, ссылки), которые не стоит зачитывать; быстрые действия и настройки (громкость, скорость речи, окно follow-up); media-style уведомление в шторке с 2 действиями.
- Экран не дублирует речь, а дополняет (принцип Amazon).

### Gaps
- Apple HIG (голос/Siri), Material 3 для аудио-first, Meta developer docs - не найдены/не открыты.
- Нет специфичных гайдов Яндекса для русских голосовых интерфейсов.

## 6. Приватность, индикаторы и доверие при always-listening

### Takeaway
У Meta на очках приватность решается белым светодиодом при записи камерой и обновлением 2026 года, отключающим камеру при блокировке LED; для микрофона wake word - самостоятельный выбор пользователя (можно отключить "Hey Meta").

### Cited Findings
- Meta AI реагирует на "Hey Meta"; белый LED мигает при использовании камеры — [The Star, июль 2026](https://www.thestar.com.my/tech/tech-news/2026/07/14/meta-updates-ray-ban-smart-glasses-to-prevent-hidden-recording)
- Лазейка: запись, а затем закрытие LED продолжала запись; обновление отключает камеру при блокировке/повреждении LED; заявление Meta не проверено независимо, неясно для старых моделей и Oakley — [aiweekly](https://aiweekly.co/alerts/meta-to-disable-ray-ban-camera-when-privacy-led-is-tampered-with); [The Star](https://www.thestar.com.my/tech/tech-news/2026/07/14/meta-updates-ray-ban-smart-glasses-to-prevent-hidden-recording)
- Розничные рекомендации: отключить "Hey Meta" в настройках, чтобы прекратить прослушивание команд (сторонний совет, не заявление Meta) — [EZContacts](https://ezcontacts.com/blog/ray-ban-meta-glasses-are-there-privacy-risks)
- Meta: "Pause Live AI" / касание тачпада временно выключает микрофон и камеры — [Android Central](https://www.androidcentral.com/wearables/meta-smart-glasses-ai-tips-and-tricks)

### Inferences
- Для собственного ассистента: всегда виден статус микрофона на телефоне (и звуковой сигнал начала/конца записи), аппаратный или быстрый программный mute, локальная обработка wake word, журнал того, что отправлено в облако, возможность удалить историю. (Инженерные рекомендации; источников по доверию в always-listening не найдено.)

### Gaps
- Нет исследований доверия пользователей к always-listening в очках; нет первичных документов Meta по приватности микрофона.
- Нет данных о правилах Android по индикатору микрофона (Android 12+ индикатор микрофона есть в системе - из общих знаний, не проверено поиском).
