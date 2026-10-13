# SOTA Research Agent — безпечний локальний MVP

Окремий компонент `sotaos.agent.research` у наявному Gradle-модулі `agent`.
Репозиторій: https://github.com/Khmelyuk/SOTA-OS.
Граф залежностей `agent → application/domain` збережено; ADR-018 дозволяє
composition root `api → agent` для локального CLI.

## Мінімальний план та реалізована межа

1. Локальна база кандидатів знань та пошук за текстом/назвою.
2. Імпорт публічних дискусій, версії джерел, provenance, аргументи та контраргументи.
3. Український Markdown-звіт, незмінні чернетки та погодження конкретного SHA-256 людиною.
4. Read-only Moltbook GET, явний `GEO_BLOCKED`, без реєстрації, публікації чи обходу обмежень.
5. Інтеграційні тести з чинним local-passphrase provider, README та архітектурне трасування.

Компонент викликається людиною через довірений локальний composition root.
Локальний CLI підключено за ADR-018; графічний UI та LLM extraction ще не підключені. Аргументи додаються
явно; автоматичного встановлення істинності, канонізації чи виконання ACTION немає.

## Перший запуск з термінала

З кореня checkout на Linux із JDK 21. Налаштуйте `JAVA_HOME` на свою встановлену JDK:

```bash
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :api:installDist
export SOTA_RESEARCH_CLI="$PWD/api/build/install/api/bin/api"
mkdir -p -m 700 work/research-run
export SOTA_RESEARCH_DB="$PWD/work/research-run/sota.db"
"$SOTA_RESEARCH_CLI" auth create --handle researcher --db "$SOTA_RESEARCH_DB"
"$SOTA_RESEARCH_CLI" research setup --handle researcher --db "$SOTA_RESEARCH_DB"
```

`auth create` попросить нову парольну фразу двічі (прихований ввід).
`setup` попросить `СТВОРИТИ RESEARCH CORE`, потім парольну фразу, і покаже
`Research authority: UUID`. Ця команда створює новий дослідницький Core та
відкличне повноваження на 24 години; наявні колективи вона не представляє.
Скопіюйте показаний UUID:

```bash
export SOTA_RESEARCH_AUTHORITY='ВСТАВТЕ_UUID_ПОВНОВАЖЕННЯ'
"$SOTA_RESEARCH_CLI" research import --handle researcher --authority "$SOTA_RESEARCH_AUTHORITY" \
  --db "$SOTA_RESEARCH_DB" --file examples/research/discussions.json
"$SOTA_RESEARCH_CLI" research search --handle researcher --authority "$SOTA_RESEARCH_AUTHORITY" \
  --db "$SOTA_RESEARCH_DB" --query 'доказів'
"$SOTA_RESEARCH_CLI" research report --handle researcher --authority "$SOTA_RESEARCH_AUTHORITY" \
  --db "$SOTA_RESEARCH_DB" --out work/research-run/report.md
```

Кожна команда просить парольну фразу. Sample містить явно позначені синтетичні
дані й `example.org`, а не реальну дискусію. Замініть їх законно отриманими
публічними текстами. JSON імпорту — масив 1–20 записів з полями
`url`, `externalId`, `author`, `publishedAt` (ISO Instant або null), `title`, `text`.
Обмеження файлу: 1 MiB, окремого тексту: 32 000 символів.

Аргумент передається файлом через `research argument --file argument.json`
з тими самими `--handle`, `--authority`, `--db`:

```json
{
  "sourceId": "UUID_ДЖЕРЕЛА_З_IMPORT",
  "kind": "ARGUMENT",
  "text": "Довіра має спиратися на перевірні докази.",
  "quote": "Довіра потребує доказів."
}
```

Для контраргументу: `kind: COUNTERARGUMENT`, додаткове `counters: UUID_АРГУМЕНТУ`.
Цитата має дослівно міститися в джерелі. Текст/цитата подаються файлом, не
аргументами оболонки. `research draft --file draft.txt --evidence SOURCE_UUID,ARGUMENT_UUID`
створює незмінну чернетку та показує її UUID. `research approve --draft DRAFT_UUID`
після входу показує точний текст і SHA-256, а тоді вимагає фразу
`ПОГОДЖУЮ SHA256`. Відмова не записує погодження. Небезпечні термінальні
control/format символи блокують preview. Погодження не публікує текст.

`research revoke` з тими самими загальними параметрами потребує
`ВІДКЛИКАТИ RESEARCH` і парольної фрази: людина добровільно відмовляється лише
від власного setup-повноваження. Воно та дочірні делегування стають REVOKED.
Новий setup після явного підтвердження створює новий Core/нове повноваження;
автоматичного продовження старого немає.

Для цього сценарію використовуйте окрему research-базу, як у прикладі вище:
setup/revoke записують непідписані локальні governance Events, які strict P09
не експортує. Це не спосіб додати research до вже підписаного sync-журналу.

Дані зберігаються біля обраної бази: `<DB>.research/<SHA256_UNIT>/research.jsonl`.
Звіт створюється з правами 0600; наявний файл не перезаписується — оберіть новий шлях.
Moltbook викликається лише явно через `research fetch-moltbook`; geo-block зупиняє імпорт.
Опції `--password`, `--yes`, довільні provider IDs та публікація не підтримуються.

## Інваріанти

- A01–A20 та наявні domain/application/security правила не змінюються.
- A21 зберігається як чернетка: жодних арбітражних або примусових повноважень.
- ADR-006: лише observe/propose, `KNOWLEDGE_CANDIDATE`; AI не PRINCIPAL.
- ADR-009: кожна команда споживає свіжий provider-neutral challenge через чинний
  `AuthenticationService`; Person визначається перевіреним binding, а не переданим ID.
- `ResearchAuthorization` перевіряє actor, контекст, ресурс, operation, validity,
  чинний ланцюжок делегування та `RightsConstraint` перед операцією і повторно
  в новій транзакції безпосередньо перед локальним комітом. HTTP-запит та очікування
  підтвердження не тримають SQLite-транзакцію: інший процес може відкликати дозвіл,
  і завершена перевірка тоді не запише чернетку чи погодження.
- Немає API для grant, ACTION, canonical knowledge, реєстрації чи публікації.
  Human approval означає лише локальну перевірку незмінної чернетки.
- Публічні тексти — недовірені дані; вони не виконуються, не передаються tools/LLM
  і не змінюють policy. Зовнішній author є атрибуцією джерела, не SOTA Person.

## Локальне сховище й аудит

`ResearchJournal` використовує окрему приватну директорію на локальній POSIX FS:
`research.jsonl` (0600), директорія (0700), sidecar lock.
Кожен запис містить ID, час отримання, стабільний PersonId, SotaId, provider ID,
authority ID, контекст, застосовані rights policies, purpose, payload та SHA-256 chain. Proof, passphrase, credential revision,
provider subject і HTTP diagnostic body не записуються. Доступ до workspace потрібно
надавати лише довіреному локальному процесу; purpose/content не повинні містити секретів.

SOURCE містить URL, external ID, атрибуцію автора, час публікації (якщо є), сирий текст,
SHA-256 тексту та статус кандидата. Повторний ідентичний імпорт повертає попередній ID;
змінений вміст зберігається новим записом. ARGUMENT містить source ID і точну цитату;
COUNTERARGUMENT посилається на попередній аргумент. DRAFT містить evidence IDs;
APPROVAL — draft ID та точний hash. Звіт показує provenance та межі висновків.

Batch і його аудит комітяться одним atomic rename з fsync. Thread/process lock
серіалізує доступ; до 16 MiB на журнал і до 20 джерел на batch. Неповний або пошкоджений
журнал відхиляється без автоматичного виправлення. Hash chain виявляє випадкову зміну,
але не є цифровим підписом чи захистом від власника файлової системи, який перепише весь
журнал. Це окремий research journal, не канонічний P06 EventStore і не P09 replication.
Відмови auth/authority повертаються composition root без запису дослідницьких даних.

## Підключення в локальному host

Використовуйте наявні `AuthenticationService`, `AuthorityRepository`, `Clock`,
`RightsConstraint`, Sota unit та явно обраний research `Context`.
`ResearchAuthorization` не має default allow-policy. Host повинен передати чинний
rights layer і authority repository з транзакцією, що серіалізує відкликання.
Не приймайте `ResearchAccess` або transport adapter напряму з віддаленого API.

Людський делегат потребує незалежно виданої authority з ресурсом
`research-workspace` і `research-unit:<SotaId>` та потрібними діями.
Обидва ресурси обов’язкові: автентифікація тієї самої Person в іншому unit
не переносить дозвіл на інший workspace:

- `research.read`: пошук і український звіт;
- `research.ingest`: ручний імпорт і opt-in Moltbook GET;
- `research.analyze`: аргументи/контраргументи;
- `research.draft`: створення чернетки;
- `research.approve`: людська перевірка точного тексту.

Схема виклику з composition root (змінні `auth`, `authorities`, `clock`, `unit`,
`context`, `rights`, `workspace` надає host):

```kotlin
val guard = ResearchAuthorization(auth, authorities, clock, unit, context, rights)
val research = ResearchAgent(ResearchJournal(workspace), guard)
// Перед КОЖНОЮ командою: auth.begin(...), новий proof з прихованого локального вводу.
val access = ResearchAccess(challenge, proof, authorityId, "Дослідження публічної дискусії")
val sourceIds = research.ingest(access, publicDiscussions)
// Інші команди: search, addArgument, report, createDraft, approveDraft.
// report повертає Markdown; host може зберегти його локально.
// approveDraft потребує draft ID та SHA-256 точного UTF-8 тексту, показаного людині.
```

Кожний challenge одноразовий; `AuthenticationService` очищає `CharArray` proof.
Не виводьте proof та не додавайте його в журнали host.

## Moltbook

`fetchMoltbook(access, MoltbookAdapter())` робить один opt-in GET до фіксованого
`https://www.moltbook.com/api/v1/posts?limit=20`. Загальний deadline 15 с охоплює заголовки й усе тіло відповіді;
зависле читання скасовується, клієнт закривається. Redirects заборонені,
response buffering обмежений 1 MiB. Власні URL, credentials, VPN/proxy retry,
registration і POST відсутні. `geo_blocked` при 403 повертає `GEO_BLOCKED` та аудит статусу;
інші помилки повертають `FORBIDDEN`, `UNAVAILABLE` або `INVALID_RESPONSE`.

Parser підтримує fixture-contract `{posts: [{id, author: {name}, title, content,
created_at?}]}`. Live schema не підтверджено через регіональне блокування; comments,
pagination та інші envelope варіанти не заявлені. Невідомий schema відхиляється.
MVP не звертався до live Moltbook під час розробки; тести використовують fixtures.
За geo-block працює ручний імпорт законно отриманих публічних текстів.

## Перевірка

На JDK 21 з кореня репозиторію:

```sh
./gradlew build --continue --console=plain
```

Тести охоплюють перезапуск, дедуплікацію/версії, цитати та зв'язки,
provider proof/replay, revocation/scope/expiry, draft hash approval, пошкодження
журналу, недовірені тексти, ізоляцію Sota units, відкликання під час review,
geo-block без повторів та HTTP-відповідь, що зависла після заголовків. Наявні архітектурні тести
потрібно запускати разом із ними при інтеграції host.

CLI smoke після збірки дистрибутива:

```sh
./gradlew :api:distZip
python3 scripts/research-cli-smoke.py
```

Використовує лише тимчасову базу й синтетичні публічні дані; мережевих запитів немає.
