# Надёжная и актуальная информация в голосовом ассистенте (LLM через OpenAI-совместимый API, пользователь РФ/СНГ)

Дата исследования: 2026-11-05. Большинство цен/лимитов взято из сторонних обзоров (официальные страницы часто не открывались) - везде, где источник вторичный или данные расходятся, это помечено.

## 1. Борьба с галлюцинациями и устаревшими знаниями (паттерны)

### Takeaway
Самая надёжная схема: LLM не "знает" факты, а вызывает инструменты (function calling) к детерминированным API (погода, курс, время) и/или веб-поиску, а ответ строится по полученным данным с указанием источника и даты. Поисковый grounding бывает встроенный (Yandex web_search, OpenRouter web plugin, Kimi $web_search) или самодельный (поиск + чтение страницы + передача в контекст).

### Cited Findings
- Yandex AI Studio: инструмент `web_search` включается в Responses API; модель сама решает, нужен ли поиск; параметры `allowed_domains` (до 5 доменов), `user_location` (регион), `search_context_size` (low/medium/high); в ответе `annotations` с `url_citation` для показа источников — [Yandex AI Studio docs](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/agents/tools/websearch)
- OpenRouter: веб-поиск включается суффиксом `:online` (ярлык плагина `web`), плагином `{"id":"web"}` (опции engine, max_results=5 по умолчанию, include/exclude_domains) или серверным инструментом `openrouter:web_search`, где модель сама решает, когда искать (рекомендуется документацией). Ответ содержит `annotations` типа `url_citation` (url, title, content, start_index, end_index) — [OpenRouter docs](https://openrouter.ai/docs/features/web-search)
- Плагин web у OpenRouter: запрос -> поисковик -> сниппеты оборачиваются в system-сообщение -> модель (описание третьей стороны, не официальное) — [Exa/third-party write-up](https://exa.ai/blog/openrouter-and-exa)
- Moonshot Kimi: встроенный `$web_search` — токены + ~$0.005 за успешный вызов + результаты поиска как входные токены; по одному из руководств, `$web_search` не работает с thinking-режимом K2.6, возможна депрекация 20.10.2026 в пользу `/v1/tools/search` (нужно проверить на сайте Moonshot) — [Puter Kimi pricing](https://developer.puter.com/tutorials/kimi-api-pricing/)
- DeepSeek V4-Flash/V4-Pro поддерживают tool calls и режим рассуждений, контекст до 1M токенов; алиасы deepseek-chat/deepseek-reasoner отключаются 24.07.2026 (по русскоязычным обзорам) — [РБК Компании](https://companies.rbc.ru/news/0Su2q3GJoP/kak-oplatit-deepseek-api-iz-rossii-v-2026-godu-popolnenie-balansa/)

### Inferences
Рекомендуемые паттерны (синтез, не из источников):
1. System prompt: текущие дата/время с часовым поясом, город/координаты (по согласию), язык, правило "для любых изменяющихся фактов (погода, курс, новости, расписание, цены, счёт, должности) обязательно вызывай инструмент; без данных инструмента не называй цифр".
2. Набор узких инструментов: get_weather, get_time, get_currency_rates, search_web, get_news, wiki_summary, geocode/route. Узкий инструмент с типизированным JSON надёжнее "поиска по всему".
3. Ответ всегда содержит "по данным <источник>, на <дата/время>" для изменчивых фактов (в голосе - коротко: "по данным Центробанка на сегодня").
4. Явно разрешить "не уверен/не нашёл" и запретить выдумывать; при пустом/ошибочном результате инструмента - так и сказать.
5. Температура 0-0.3 для фактологических ответов; короткие ответы для голоса.
6. Для tool calling выбирать модель, где он стабилен (DeepSeek, Kimi, Mistral, Qwen-классы); бесплатные "тонкие" модели чаще ломают JSON аргументов - валидировать и ограничивать число повторов.

### Gaps
- Количественных замеров снижения галлюцинаций при grounding не найдено.

## 2. Бесплатные источники данных реального времени, доступные из РФ/СНГ

### Takeaway
Погода (Open-Meteo), курсы ЦБ (cbr-xml-daily.ru), OSM-сервисы, RSS российских СМИ, Wikipedia доступны без платных западных аккаунтов. Для веб-поиска бесплатных и надёжных вариантов мало: Tavily (1000 кредитов/мес), Яндекс Search API (платный), Brave (бесплатный тариф, вероятно, убран).

### Cited Findings
- Open-Meteo бесплатный тариф: 600 вызовов/мин, 5000/час, 10 000/день, 300 000/мес; только некоммерческое использование; без гарантии аптайма; лицензия CC BY 4.0 (нужна атрибуция) — [Open-Meteo pricing](https://open-meteo.com/en/pricing)
- Курсы ЦБ: зеркало cbr-xml-daily.ru: `daily_json.js`, `daily_utf8.xml`, `daily_eng_utf8.xml`, `latest.js`; курсы меняются не чаще раза в сутки, рекомендуется кэш и обновление раз в несколько часов; лимиты: не более 1 запроса/сек, 30/мин и 10 000/сутки с IP; требуется обратная ссылка; сам сайт отмечает, что официальные сервисы ЦБ бывают нестабильны — [cbr-xml-daily.ru](https://www.cbr-xml-daily.ru/)
- Nominatim (OSM): публичный сервис на донорских серверах, ограниченная ёмкость, лимит относится ко всему приложению суммарно (не к пользователю), периодические запросы приложения считаются bulk-геокодингом и не приветствуются; политика может меняться без уведомления. Числовой лимит (1 запрос/сек) в найденном тексте не подтверждён — проверить на [официальной странице](https://operations.osmfoundation.org/policies/nominatim/)
- OSRM публичный demo: без гарантий, обязателен User-Agent с идентификацией приложения, атрибуция ODbL/OSRM, чрезмерное использование запрещено, коммерческим сервисам доступ могут закрыть; современные условия — на routing.openstreetmap.de — [OSRM API usage policy](https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy)
- Tavily: 1000 бесплатных кредитов/мес без карты; pay-as-you-go $0.008/кредит (сходится в нескольких обзорах; доступность из РФ не проверена) — [Tavily docs](https://docs.tavily.com/guides/api-credits), [CostBench](https://costbench.com/software/web-scraping/tavily/free-plan/)
- Jina: s.jina.ai - 100 RPM, фиксированная стоимость от 10 000 токенов за запрос; размер бесплатных токенов расходится (1M против 10M); r.jina.ai без ключа 20 RPM, с ключом 500 RPM — [Jina pricing](https://jina.ai/api-dashboard/pricing/) и агрегатор
- Brave Search API: источники расходятся; несколько обзоров 2026 года говорят, что бесплатный тариф (~2000 запросов/мес) убран в феврале 2026 и заменён $5 кредитами с картой ($0.003-0.005/запрос); CostBench: бесплатного плана нет на август 2026 — [CostBench Brave](https://costbench.com/software/ai-search-apis/brave-search-api/free-plan/). Для РФ карта - проблема.
- Яндекс Search API v2: синхронные запросы 480 руб/1000 с НДС; нетарифицируемый объём v1 (1000 ночных запросов) на v2 не распространяется; есть ночная скидка 00:00-07:59 МСК — [Yandex Search API pricing](https://yandex.cloud/ru/docs/search-api/pricing); пример в документации чуть расходится со ставкой - сверить
- Яндекс web_search-инструмент использует индекс Яндекса и работает вне контура, сертифицированного по ФЗ-152 (персональные данные не отправлять) — [Yandex AI Studio docs](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/agents/tools/websearch)

### Inferences
Из общих знаний (в этой сессии НЕ проверялось, перепроверить перед реализацией):
- Время/часовой пояс: брать с устройства (java.time), сетевой источник не нужен. Open-Meteo возвращает timezone вместе с прогнозом.
- Open-Meteo: `api.open-meteo.com/v1/forecast?latitude=..&longitude=..&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&hourly=...&timezone=auto`; геокодинг `geocoding-api.open-meteo.com/v1/search?name=...&language=ru`. Без ключа.
- Wikipedia REST: `ru.wikipedia.org/api/rest_v1/page/summary/{title}` и MediaWiki `action=query&list=search` - бесплатно, нужен User-Agent.
- RSS: Lenta (lenta.ru/rss), РИА (ria.ru/export/rss2/...), Meduza (meduza.io/rss/all), BBC Русская (feeds.bbci.co.uk/russian/rss.xml) - адреса и доступность из РФ (особенно Meduza/BBC, заблокированы Роскомнадзором, нужен VPN/прокси) проверить; для РФ-юзера надёжнее Lenta/РИА/ТАСС/Интерфакс. Для озвучки брать заголовок + дату + источник.
- Яндекс.Погода API и 2GIS API: платные/по ключу, условия не проверены; Open-Meteo достаточно. Яндекс Карты/2GIS - маршруты с пробками точнее OSRM (OSRM не знает пробок), но ключи/лимиты не проверены.
- DuckDuckGo Instant Answer API ограничен и не заменяет поиск; SearXNG - самохостинг (нужен свой сервер) либо публичные инстансы нестабильны.
- Перевод: сама LLM переводит лучше любого бесплатного API; отдельный API не нужен.
- Общественный транспорт: единого бесплатного API нет; для городов РФ - Яндекс.Расписания (платно/по ключу), для остального - навигационные приложения через Intent. Не проверено.

### Gaps
- Не проверены: Яндекс.Погода, 2GIS API, Яндекс.Расписания, доступность RSS из РФ, DuckDuckGo/SearXNG, Wikipedia лимиты.

## 3. OpenAI-совместимые провайдеры, доступные из РФ

### Takeaway
Ситуация нестабильная: OpenRouter, по сообщениям, перестал работать для российских аккаунтов/платежей в 2026 г.; DeepSeek работает, но российской картой не оплатить; Yandex AI Studio - единственный из рассмотренных, нативно доступный и оплачиваемый в рублях, с OpenAI-совместимым Responses API и встроенным web_search. Бесплатные лимиты у западных провайдеров (Groq, Cerebras, Mistral) формально есть, но географическая доступность из РФ в источниках не подтверждена.

### Cited Findings
- OpenRouter и РФ: сообщения, что платежи/аккаунты из России блокируются (12 мая 2026 по Habr; 27 июня 2026 по другому источнику - даты расходятся, официального заявления OpenRouter не найдено) — [Skillbox](https://skillbox.ru/media/code/polzovateli-pozhalovalis-na-blokirovku-platezhey-v-openrouter-dlya-akkauntov-iz-rossii/), [Habr](https://habr.com/ru/articles/1034012/), [DTF: аналоги](https://dtf.ru/id2187199/4918321-analogi-openrouter-dlya-dostupa-k-llm)
- Рублёвые агрегаторы (ProxyAPI, VseGPT) упомянуты как работающие, но с наценкой — тот же поиск, [DTF](https://dtf.ru/id2187199/4918321-analogi-openrouter-dlya-dostupa-k-llm); надёжность не проверена
- OpenRouter free: 20 запросов/мин; 50 запросов/день, при покупке кредитов на $10+ - 1000/день; сбойные запросы могут считаться в квоту; часть 429 приходит от апстрим-провайдера, а не от шлюза; отрицательный баланс даёт 402 — [TrueFoundry](https://www.truefoundry.com/es/blog/openrouter-rate-limits-your-free-tier-depends-on-what-youve-already-paid), [OpenRouter blog](https://openrouter.ai/blog/tutorials/free-llm-apis-compared/)
- Обзор бесплатных API (OpenRouter blog, 2026; про tool calling и регионы в обзоре ничего нет): Groq 30 RPM/1000 RPD; Cerebras 30 RPM/~1M токенов/день; Mistral ~1B токенов/мес (Experiment); Cloudflare Workers AI ~10K neurons/день; Google AI Studio 5-15 RPM; GitHub Models 15 RPM; DeepSeek - 10M токенов пробных — [OpenRouter blog](https://openrouter.ai/blog/tutorials/free-llm-apis-compared/)
- Данные по Groq/Cerebras противоречивы: другие источники дают для Groq иные цифры (в т.ч. 14 400 запросов/день - устаревшее; Groq перешёл на лимиты по моделям); Cerebras: по одному источнику открытый бесплатный тариф закончился 21.07.2026 (остался разовый $5 после привязки карты), по другим - всё ещё есть free tier (5 RPM) — [Free LLM API overview](https://dev.to/0012303/9-free-llm-apis-in-2026-you-can-use-without-a-credit-card-26g8), [ClaudeLog](https://claudelog.com/faqs/is-cerebras-free/)
- DeepSeek: проблема в оплате - российские карты не проходят; обходные пути: карты Казахстана/Армении/Турции с 3-D Secure, PayPal, Alipay, WeChat, посредники (есть предупреждения о мошенниках); по источнику РКН заявил (24.05.2026), что ограничений на DeepSeek не вводил; цена V4 Flash ~$0.14/$0.28 за 1M токенов (вход/выход) - один источник, другой даёт цены старой V3.2 — [РБК Компании](https://companies.rbc.ru/news/0Su2q3GJoP/kak-oplatit-deepseek-api-iz-rossii-v-2026-godu-popolnenie-balansa/), [vc.ru](https://vc.ru/ai/3012545-oplata-deepseek-api-iz-rossii)
- Kimi/Moonshot: $web_search и отдельный Search API (Web Search Basic $0.002/вызов, Pro $0.003, URL Fetch $0.002 по стороннему руководству); оплата из РФ не проверена — [Puter](https://developer.puter.com/tutorials/kimi-api-pricing/)
- Yandex AI Studio: OpenAI-совместимые Chat/Responses/Realtime API (SDK содержит sdk.chat), web_search как инструмент; тарификация идёт через квоты Search API — [Yandex AI Studio docs](https://aistudio.yandex.ru/docs/en/ai-studio/concepts/agents/tools/websearch), [SDK](https://github.com/yandex-cloud/yandex-ai-studio-sdk). Точный список моделей и поддержка function calling у YandexGPT/других моделей в AI Studio не проверены.
- Платный поиск OpenRouter (если доступен): Exa $7/1000 запросов (до 10 результатов), Parallel $1-5/1000, Perplexity $0.005/запрос, Firecrawl $0.005 + $0.0125/результат — [OpenRouter docs](https://openrouter.ai/docs/features/web-search)

### Inferences
- Для РФ практичнее всего: (а) Yandex AI Studio (рубли, web_search, ФЗ-152 оговорка), (б) DeepSeek при наличии способа оплаты, (в) российские агрегаторы-прокси как запасной вариант, (г) бесплатные Groq/Mistral/Cloudflare - проверять регистрацию с реальным аккаунтом; архитектуру строить провайдер-независимой (baseUrl + apiKey + model в настройках, цепочка fallback).
- Free-модели годятся для прототипа, но 50 запросов/день и 429 делают их непригодными как единственный бэкенд ассистента.

### Gaps
- Официальные страницы Groq/Cerebras/Mistral/Together/SiliconFlow/Cloudflare о списке поддерживаемых стран не найдены; доступность для РФ не подтверждена.
- GigaChat пропущен по решению пользователя. Together, SiliconFlow не исследовались.
- Perplexity-style "online" модели (Sonar) напрямую не проверены.

## 4. Реализация на Android

### Takeaway
(Из общих знаний и практики; в сессии источниками не подтверждалось.) Цикл function calling прост: отправить messages+tools -> если ответ содержит tool_calls, выполнить локально/по сети, добавить сообщения role=tool, повторить (лимит 3-4 итерации) -> финальный текст.

### Cited Findings
- Формат citations OpenAI-совместимых ответов (`annotations[].url_citation`) поддерживают и OpenRouter, и Yandex — см. ссылки выше.
- Кэш курсов ЦБ рекомендуется самим сервисом (раз в несколько часов) — [cbr-xml-daily.ru](https://www.cbr-xml-daily.ru/)

### Inferences
(не проверено источниками, инженерные рекомендации)
- OkHttp: connectTimeout ~5 с, readTimeout для не-стриминга ~20-30 с; предпочтительно SSE-стриминг, чтобы TTS начинал говорить с первого предложения.
- Параллельное выполнение независимых tool_calls (coroutines async).
- Кэш: погода 10-15 мин по ключу (округлённые координаты), курсы 3-6 ч, новости 5-10 мин; хранить timestamp и озвучивать "данные от ...". Stale-while-revalidate при отсутствии сети.
- Fallback: цепочка провайдеров; при 429 - Retry-After + экспоненциальная пауза с jitter, затем следующий провайдер; при 402/401 - ошибка настройки, сказать пользователю.
- Офлайн: определять через ConnectivityManager; без сети отвечать только по локальным данным (время, батарея, последние кэши с пометкой возраста), иначе: "Нет связи, не могу проверить актуальные данные".
- Что говорить при сбое: коротко и честно ("Не удалось получить погоду, сервис не отвечает. Повторить?"), не подставлять данные из памяти модели для изменчивых фактов.
- Валидация JSON аргументов tool_calls, белый список функций, ограничение числа вызовов, санитизация текста из веб-страниц (prompt injection из результатов поиска: помечать как недоверенные данные).

### Gaps
- Не искались официальные примеры OkHttp/Android; рекомендации выше - инженерное мнение.

## 5. Сенсоры и контекст Android; приватность

### Takeaway
(Из общих знаний, не проверялось в сессии.) Полезный контекст: время/часовой пояс, локация (FusedLocationProviderClient), батарея (BatteryManager/ACTION_BATTERY_CHANGED), подключённое BT-устройство (BluetoothProfile proxies; Android 12+ требует BLUETOOTH_CONNECT), активность (ActivityRecognition API, разрешение ACTIVITY_RECOGNITION, Google Play Services).

### Inferences
- Локация: достаточно грубой (ACCESS_COARSE_LOCATION) для погоды; точную давать только для навигации; в LLM-запрос передавать город/округлённые координаты, а не точные. Фоновая локация (Android 10+/11+) требует отдельного разрешения - избегать, брать локацию по запросу.
- Activity recognition: движение/в машине -> короче ответы, не показывать длинный текст; но Play Services могут быть недоступны на устройствах без GMS - предусмотреть деградацию.
- Приватность: всё, что попало в system prompt, уходит стороннему провайдеру (для Yandex web_search - вне контура ФЗ-152, по документации выше); минимизировать, давать пользователю переключатели по каждому источнику контекста, не логировать содержимое, не отправлять точные координаты и имя BT-устройства без необходимости.

### Gaps
- Официальные документы Android по разрешениям не открывались; названия API и требования проверить по developer.android.com.
